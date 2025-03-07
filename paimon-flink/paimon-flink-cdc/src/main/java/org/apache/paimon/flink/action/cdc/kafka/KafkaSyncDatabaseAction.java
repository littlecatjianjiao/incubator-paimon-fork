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

package org.apache.paimon.flink.action.cdc.kafka;

import org.apache.paimon.flink.action.cdc.MessageQueueSyncDatabaseActionBase;
import org.apache.paimon.flink.action.cdc.format.DataFormat;

import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.streaming.api.datastream.DataStreamSource;

import java.util.Map;

/** Synchronize database from Kafka. */
public class KafkaSyncDatabaseAction extends MessageQueueSyncDatabaseActionBase {

    public KafkaSyncDatabaseAction(
            String warehouse,
            String database,
            Map<String, String> catalogConfig,
            Map<String, String> kafkaConfig) {
        super(warehouse, database, catalogConfig, kafkaConfig);
    }

    @Override
    protected DataStreamSource<String> buildSource() throws Exception {
        return env.fromSource(
                KafkaActionUtils.buildKafkaSource(cdcSourceConfig),
                WatermarkStrategy.noWatermarks(),
                sourceName());
    }

    @Override
    protected String sourceName() {
        return "Kafka Source";
    }

    @Override
    protected DataFormat getDataFormat() {
        return KafkaActionUtils.getDataFormat(cdcSourceConfig);
    }

    @Override
    protected String jobName() {
        return String.format("Kafka-Paimon Database Sync: %s", database);
    }
}
