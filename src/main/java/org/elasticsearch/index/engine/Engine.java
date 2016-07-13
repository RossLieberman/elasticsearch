/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.index.engine;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.Filter;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.join.BitDocIdSetFilter;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.lease.Releasable;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.lucene.uid.Versions;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.index.VersionType;
import org.elasticsearch.index.deletionpolicy.SnapshotIndexCommit;
import org.elasticsearch.index.mapper.DocumentMapper;
import org.elasticsearch.index.mapper.ParseContext.Document;
import org.elasticsearch.index.mapper.ParsedDocument;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.index.translog.Translog;

import java.io.Closeable;
import java.util.List;

/**
 *
 */
public interface Engine extends Closeable {

    void updateIndexingBufferSize(ByteSizeValue indexingBufferSize);

    void create(Create create) throws EngineException;

    void index(Index index) throws EngineException;

    void delete(Delete delete) throws EngineException;

    void delete(DeleteByQuery delete) throws EngineException;

    GetResult get(Get get) throws EngineException;

    /**
     * Returns a new searcher instance. The consumer of this
     * API is responsible for releasing the returned seacher in a
     * safe manner, preferably in a try/finally block.
     *
     * @see Searcher#close()
     */
    Searcher acquireSearcher(String source) throws EngineException;

    /**
     * Global stats on segments.
     */
    SegmentsStats segmentsStats();

    /**
     * The list of segments in the engine.
     */
    List<Segment> segments(boolean verbose);

    /**
     * Returns <tt>true</tt> if a refresh is really needed.
     */
    boolean refreshNeeded();

    /**
     * Refreshes the engine for new search operations to reflect the latest
     * changes.
     */
    void refresh(String source) throws EngineException;

    /**
     * Flushes the state of the engine, clearing memory.
     */
    void flush(FlushType type, boolean force, boolean waitIfOngoing) throws EngineException;

    /**
     * Optimizes to 1 segment
     */
    void forceMerge(boolean flush, boolean waitForMerge);

    /**
     * Triggers a forced merge on this engine
     */
    void forceMerge(boolean flush, boolean waitForMerge, int maxNumSegments, boolean onlyExpungeDeletes, boolean upgrade) throws EngineException;

    /**
     * Snapshots the index and returns a handle to it. Will always try and "commit" the
     * lucene index to make sure we have a "fresh" copy of the files to snapshot.
     */
    SnapshotIndexCommit snapshotIndex() throws EngineException;

    void recover(RecoveryHandler recoveryHandler) throws EngineException;

    /** fail engine due to some error. the engine will also be closed. */
    void failEngine(String reason, Throwable failure);

    ByteSizeValue indexingBufferSize();

    static interface FailedEngineListener {
        void onFailedEngine(ShardId shardId, String reason, @Nullable Throwable t);
    }

    /**
     * Recovery allow to start the recovery process. It is built of three phases.
     * <p/>
     * <p>The first phase allows to take a snapshot of the master index. Once this
     * is taken, no commit operations are effectively allowed on the index until the recovery
     * phases are through.
     * <p/>
     * <p>The seconds phase takes a snapshot of the current transaction log.
     * <p/>
     * <p>The last phase returns the remaining transaction log. During this phase, no dirty
     * operations are allowed on the index.
     */
    static interface RecoveryHandler {

        void phase1(SnapshotIndexCommit snapshot) throws ElasticsearchException;

        void phase2(Translog.Snapshot snapshot) throws ElasticsearchException;

        void phase3(Translog.Snapshot snapshot) throws ElasticsearchException;
    }

    static interface Searcher extends Releasable {

        /**
         * The source that caused this searcher to be acquired.
         */
        String source();

        IndexReader reader();

        IndexSearcher searcher();
    }

    static class SimpleSearcher implements Searcher {

        private final String source;
        private final IndexSearcher searcher;

        public SimpleSearcher(String source, IndexSearcher searcher) {
            this.source = source;
            this.searcher = searcher;
        }

        @Override
        public String source() {
            return source;
        }

        @Override
        public IndexReader reader() {
            return searcher.getIndexReader();
        }

        @Override
        public IndexSearcher searcher() {
            return searcher;
        }

        @Override
        public void close() throws ElasticsearchException {
            // nothing to release here...
        }
    }

    public static enum FlushType {
        /**
         * A flush that causes a new writer to be created.
         */
        NEW_WRITER,
        /**
         * A flush that just commits the writer, without cleaning the translog.
         */
        COMMIT,
        /**
         * A flush that does a commit, as well as clears the translog.
         */
        COMMIT_TRANSLOG
    }

    static interface Operation {
        static enum Type {
            CREATE,
            INDEX,
            DELETE
        }

        static enum Origin {
            PRIMARY,
            REPLICA,
            RECOVERY
        }

        Type opType();

        Origin origin();
    }

    static abstract class IndexingOperation implements Operation {

        private final DocumentMapper docMapper;
        private final Analyzer analyzer;
        private final Term uid;
        private final ParsedDocument doc;
        private long version;
        private final VersionType versionType;
        private final Origin origin;
        private final boolean canHaveDuplicates;

        private final long startTime;
        private long endTime;

        public IndexingOperation(DocumentMapper docMapper, Analyzer analyzer, Term uid, ParsedDocument doc, long version, VersionType versionType, Origin origin, long startTime, boolean canHaveDuplicates) {
            this.docMapper = docMapper;
            this.analyzer = analyzer;
            this.uid = uid;
            this.doc = doc;
            this.version = version;
            this.versionType = versionType;
            this.origin = origin;
            this.startTime = startTime;
            this.canHaveDuplicates = canHaveDuplicates;
        }

        public IndexingOperation(DocumentMapper docMapper, Analyzer analyzer, Term uid, ParsedDocument doc) {
            this(docMapper, analyzer, uid, doc, Versions.MATCH_ANY, VersionType.INTERNAL, Origin.PRIMARY, System.nanoTime(), true);
        }

        public DocumentMapper docMapper() {
            return this.docMapper;
        }

        @Override
        public Origin origin() {
            return this.origin;
        }

        public ParsedDocument parsedDoc() {
            return this.doc;
        }

        public Term uid() {
            return this.uid;
        }

        public String type() {
            return this.doc.type();
        }

        public String id() {
            return this.doc.id();
        }

        public String routing() {
            return this.doc.routing();
        }

        public long timestamp() {
            return this.doc.timestamp();
        }

        public long ttl() {
            return this.doc.ttl();
        }

        public long version() {
            return this.version;
        }

        public void updateVersion(long version) {
            this.version = version;
            this.doc.version().setLongValue(version);
        }

        public VersionType versionType() {
            return this.versionType;
        }

        public boolean canHaveDuplicates() {
            return this.canHaveDuplicates;
        }

        public String parent() {
            return this.doc.parent();
        }

        public List<Document> docs() {
            return this.doc.docs();
        }

        public Analyzer analyzer() {
            return this.analyzer;
        }

        public BytesReference source() {
            return this.doc.source();
        }

        /**
         * Returns operation start time in nanoseconds.
         */
        public long startTime() {
            return this.startTime;
        }

        public void endTime(long endTime) {
            this.endTime = endTime;
        }

        /**
         * Returns operation end time in nanoseconds.
         */
        public long endTime() {
            return this.endTime;
        }
    }

    static final class Create extends IndexingOperation {
        private final boolean autoGeneratedId;

        public Create(DocumentMapper docMapper, Analyzer analyzer, Term uid, ParsedDocument doc, long version, VersionType versionType, Origin origin, long startTime, boolean canHaveDuplicates, boolean autoGeneratedId) {
            super(docMapper, analyzer, uid, doc, version, versionType, origin, startTime, canHaveDuplicates);
            this.autoGeneratedId = autoGeneratedId;
        }

        public Create(DocumentMapper docMapper, Analyzer analyzer, Term uid, ParsedDocument doc, long version, VersionType versionType, Origin origin, long startTime) {
            this(docMapper, analyzer, uid, doc, version, versionType, origin, startTime, true, false);
        }

        public Create(DocumentMapper docMapper,Analyzer analyzer, Term uid, ParsedDocument doc) {
            super(docMapper, analyzer, uid, doc);
            autoGeneratedId = false;
        }

        @Override
        public Type opType() {
            return Type.CREATE;
        }


        public boolean autoGeneratedId() {
            return this.autoGeneratedId;
        }
    }

    static final class Index extends IndexingOperation {
        private boolean created;

        public Index(DocumentMapper docMapper, Analyzer analyzer, Term uid, ParsedDocument doc, long version, VersionType versionType, Origin origin, long startTime, boolean canHaveDuplicates) {
            super(docMapper, analyzer, uid, doc, version, versionType, origin, startTime, canHaveDuplicates);
        }

        public Index(DocumentMapper docMapper, Analyzer analyzer, Term uid, ParsedDocument doc, long version, VersionType versionType, Origin origin, long startTime) {
            super(docMapper, analyzer, uid, doc, version, versionType, origin, startTime, true);
        }

        public Index(DocumentMapper docMapper, Analyzer analyzer, Term uid, ParsedDocument doc) {
            super(docMapper, analyzer, uid, doc);
        }

        @Override
        public Type opType() {
            return Type.INDEX;
        }

        /**
         * @return true if object was created
         */
        public boolean created() {
            return created;
        }

        public void created(boolean created) {
            this.created = created;
        }
    }

    static class Delete implements Operation {
        private final String type;
        private final String id;
        private final Term uid;
        private long version;
        private final VersionType versionType;
        private final Origin origin;
        private boolean found;

        private final long startTime;
        private long endTime;

        public Delete(String type, String id, Term uid, long version, VersionType versionType, Origin origin, long startTime, boolean found) {
            this.type = type;
            this.id = id;
            this.uid = uid;
            this.version = version;
            this.versionType = versionType;
            this.origin = origin;
            this.startTime = startTime;
            this.found = found;
        }

        public Delete(String type, String id, Term uid) {
            this(type, id, uid, Versions.MATCH_ANY, VersionType.INTERNAL, Origin.PRIMARY, System.nanoTime(), false);
        }

        public Delete(Delete template, VersionType versionType) {
            this(template.type(), template.id(), template.uid(), template.version(), versionType, template.origin(), template.startTime(), template.found());
        }

        @Override
        public Type opType() {
            return Type.DELETE;
        }

        @Override
        public Origin origin() {
            return this.origin;
        }

        public String type() {
            return this.type;
        }

        public String id() {
            return this.id;
        }

        public Term uid() {
            return this.uid;
        }

        public void updateVersion(long version, boolean found) {
            this.version = version;
            this.found = found;
        }

        /**
         * before delete execution this is the version to be deleted. After this is the version of the "delete" transaction record.
         */
        public long version() {
            return this.version;
        }

        public VersionType versionType() {
            return this.versionType;
        }

        public boolean found() {
            return this.found;
        }

        /**
         * Returns operation start time in nanoseconds.
         */
        public long startTime() {
            return this.startTime;
        }

        public void endTime(long endTime) {
            this.endTime = endTime;
        }

        /**
         * Returns operation end time in nanoseconds.
         */
        public long endTime() {
            return this.endTime;
        }
    }

    static class DeleteByQuery {
        private final Query query;
        private final BytesReference source;
        private final String[] filteringAliases;
        private final Filter aliasFilter;
        private final String[] types;
        private final BitDocIdSetFilter parentFilter;
        private final Operation.Origin origin;

        private final long startTime;
        private long endTime;

        public DeleteByQuery(Query query, BytesReference source, @Nullable String[] filteringAliases, @Nullable Filter aliasFilter, BitDocIdSetFilter parentFilter, Operation.Origin origin, long startTime, String... types) {
            this.query = query;
            this.source = source;
            this.types = types;
            this.filteringAliases = filteringAliases;
            this.aliasFilter = aliasFilter;
            this.parentFilter = parentFilter;
            this.startTime = startTime;
            this.origin = origin;
        }

        public Query query() {
            return this.query;
        }

        public BytesReference source() {
            return this.source;
        }

        public String[] types() {
            return this.types;
        }

        public String[] filteringAliases() {
            return filteringAliases;
        }

        public Filter aliasFilter() {
            return aliasFilter;
        }

        public boolean nested() {
            return parentFilter != null;
        }

        public BitDocIdSetFilter parentFilter() {
            return parentFilter;
        }

        public Operation.Origin origin() {
            return this.origin;
        }

        /**
         * Returns operation start time in nanoseconds.
         */
        public long startTime() {
            return this.startTime;
        }

        public DeleteByQuery endTime(long endTime) {
            this.endTime = endTime;
            return this;
        }

        /**
         * Returns operation end time in nanoseconds.
         */
        public long endTime() {
            return this.endTime;
        }
    }


    static class Get {
        private final boolean realtime;
        private final Term uid;
        private boolean loadSource = true;
        private long version = Versions.MATCH_ANY;
        private VersionType versionType = VersionType.INTERNAL;

        public Get(boolean realtime, Term uid) {
            this.realtime = realtime;
            this.uid = uid;
        }

        public boolean realtime() {
            return this.realtime;
        }

        public Term uid() {
            return uid;
        }

        public boolean loadSource() {
            return this.loadSource;
        }

        public Get loadSource(boolean loadSource) {
            this.loadSource = loadSource;
            return this;
        }

        public long version() {
            return version;
        }

        public Get version(long version) {
            this.version = version;
            return this;
        }

        public VersionType versionType() {
            return versionType;
        }

        public Get versionType(VersionType versionType) {
            this.versionType = versionType;
            return this;
        }
    }

    static class GetResult {
        private final boolean exists;
        private final long version;
        private final Translog.Source source;
        private final Versions.DocIdAndVersion docIdAndVersion;
        private final Searcher searcher;

        public static final GetResult NOT_EXISTS = new GetResult(false, Versions.NOT_FOUND, null);

        public GetResult(boolean exists, long version, @Nullable Translog.Source source) {
            this.source = source;
            this.exists = exists;
            this.version = version;
            this.docIdAndVersion = null;
            this.searcher = null;
        }

        public GetResult(Searcher searcher, Versions.DocIdAndVersion docIdAndVersion) {
            this.exists = true;
            this.source = null;
            this.version = docIdAndVersion.version;
            this.docIdAndVersion = docIdAndVersion;
            this.searcher = searcher;
        }

        public boolean exists() {
            return exists;
        }

        public long version() {
            return this.version;
        }

        @Nullable
        public Translog.Source source() {
            return source;
        }

        public Searcher searcher() {
            return this.searcher;
        }

        public Versions.DocIdAndVersion docIdAndVersion() {
            return docIdAndVersion;
        }

        public void release() {
            if (searcher != null) {
                searcher.close();
            }
        }
    }
}