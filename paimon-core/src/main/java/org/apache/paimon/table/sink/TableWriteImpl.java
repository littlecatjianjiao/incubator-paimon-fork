/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.paimon.table.sink;

import org.apache.paimon.FileStore;
import org.apache.paimon.annotation.VisibleForTesting;
import org.apache.paimon.data.BinaryRow;
import org.apache.paimon.data.InternalRow;
import org.apache.paimon.disk.IOManager;
import org.apache.paimon.io.DataFileMeta;
import org.apache.paimon.memory.MemoryPoolFactory;
import org.apache.paimon.memory.MemorySegmentPool;
import org.apache.paimon.metrics.MetricRegistry;
import org.apache.paimon.operation.FileStoreWrite;
import org.apache.paimon.operation.FileStoreWrite.State;
import org.apache.paimon.table.BucketMode;
import org.apache.paimon.utils.Restorable;

import java.util.List;
import java.util.concurrent.ExecutorService;

import static org.apache.paimon.utils.Preconditions.checkState;

/**
 * {@link TableWrite} implementation.
 *
 * @param <T> type of record to write into {@link FileStore}.
 */
public class TableWriteImpl<T> implements InnerTableWrite, Restorable<List<State<T>>> {

    private final FileStoreWrite<T> write;
    private final KeyAndBucketExtractor<InternalRow> keyAndBucketExtractor;
    private final RecordExtractor<T> recordExtractor;

    private boolean batchCommitted = false;
    private BucketMode bucketMode;

    public TableWriteImpl(
            FileStoreWrite<T> write,
            KeyAndBucketExtractor<InternalRow> keyAndBucketExtractor,
            RecordExtractor<T> recordExtractor) {
        this.write = write;
        this.keyAndBucketExtractor = keyAndBucketExtractor;
        this.recordExtractor = recordExtractor;
    }

    @Override
    public TableWriteImpl<T> withIgnorePreviousFiles(boolean ignorePreviousFiles) {
        write.withIgnorePreviousFiles(ignorePreviousFiles);
        return this;
    }

    @Override
    public TableWriteImpl<T> withExecutionMode(boolean isStreamingMode) {
        write.withExecutionMode(isStreamingMode);
        return this;
    }

    @Override
    public TableWriteImpl<T> withIOManager(IOManager ioManager) {
        write.withIOManager(ioManager);
        return this;
    }

    @Override
    public TableWriteImpl<T> withMemoryPool(MemorySegmentPool memoryPool) {
        write.withMemoryPool(memoryPool);
        return this;
    }

    public TableWriteImpl<T> withMemoryPoolFactory(MemoryPoolFactory memoryPoolFactory) {
        write.withMemoryPoolFactory(memoryPoolFactory);
        return this;
    }

    public TableWriteImpl<T> withCompactExecutor(ExecutorService compactExecutor) {
        write.withCompactExecutor(compactExecutor);
        return this;
    }

    public TableWriteImpl<T> withBucketMode(BucketMode bucketMode) {
        this.bucketMode = bucketMode;
        return this;
    }

    @Override
    public BinaryRow getPartition(InternalRow row) {
        keyAndBucketExtractor.setRecord(row);
        return keyAndBucketExtractor.partition();
    }

    @Override
    public int getBucket(InternalRow row) {
        keyAndBucketExtractor.setRecord(row);
        return keyAndBucketExtractor.bucket();
    }

    @Override
    public void write(InternalRow row) throws Exception {
        writeAndReturn(row);
    }

    public SinkRecord writeAndReturn(InternalRow row) throws Exception {
        SinkRecord record = toSinkRecord(row);
        write.write(record.partition(), record.bucket(), recordExtractor.extract(record));
        return record;
    }

    @VisibleForTesting
    public T writeAndReturnData(InternalRow row) throws Exception {
        SinkRecord record = toSinkRecord(row);
        T data = recordExtractor.extract(record);
        write.write(record.partition(), record.bucket(), data);
        return data;
    }

    private SinkRecord toSinkRecord(InternalRow row) {
        keyAndBucketExtractor.setRecord(row);
        return new SinkRecord(
                keyAndBucketExtractor.partition(),
                keyAndBucketExtractor.bucket(),
                keyAndBucketExtractor.trimmedPrimaryKey(),
                row);
    }

    public SinkRecord toLogRecord(SinkRecord record) {
        keyAndBucketExtractor.setRecord(record.row());
        return new SinkRecord(
                record.partition(),
                bucketMode == BucketMode.UNAWARE ? -1 : record.bucket(),
                keyAndBucketExtractor.logPrimaryKey(),
                record.row());
    }

    @Override
    public void compact(BinaryRow partition, int bucket, boolean fullCompaction) throws Exception {
        write.compact(partition, bucket, fullCompaction);
    }

    @Override
    public TableWriteImpl<T> withMetricRegistry(MetricRegistry metricRegistry) {
        write.withMetricRegistry(metricRegistry);
        return this;
    }

    /**
     * Notify that some new files are created at given snapshot in given bucket.
     *
     * <p>Most probably, these files are created by another job. Currently this method is only used
     * by the dedicated compact job to see files created by writer jobs.
     */
    public void notifyNewFiles(
            long snapshotId, BinaryRow partition, int bucket, List<DataFileMeta> files) {
        write.notifyNewFiles(snapshotId, partition, bucket, files);
    }

    @Override
    public List<CommitMessage> prepareCommit(boolean waitCompaction, long commitIdentifier)
            throws Exception {
        return write.prepareCommit(waitCompaction, commitIdentifier);
    }

    @Override
    public List<CommitMessage> prepareCommit() throws Exception {
        checkState(!batchCommitted, "BatchTableWrite only support one-time committing.");
        batchCommitted = true;
        return prepareCommit(true, BatchWriteBuilder.COMMIT_IDENTIFIER);
    }

    @Override
    public void close() throws Exception {
        write.close();
    }

    @Override
    public List<State<T>> checkpoint() {
        return write.checkpoint();
    }

    @Override
    public void restore(List<State<T>> state) {
        write.restore(state);
    }

    @VisibleForTesting
    public FileStoreWrite<T> getWrite() {
        return write;
    }

    /** Extractor to extract {@link T} from the {@link SinkRecord}. */
    public interface RecordExtractor<T> {

        T extract(SinkRecord record);
    }
}
