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

package org.apache.paimon.flink;

import org.apache.paimon.utils.BlockingIterator;

import org.apache.flink.table.api.config.ExecutionConfigOptions;
import org.apache.flink.types.Row;
import org.apache.flink.types.RowKind;
import org.apache.flink.util.CloseableIterator;
import org.assertj.core.api.Assertions;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ITCase for partial update. */
public class PartialUpdateITCase extends CatalogITCaseBase {

    @Override
    protected List<String> ddl() {
        return Arrays.asList(
                "CREATE TABLE IF NOT EXISTS T ("
                        + "j INT, k INT, a INT, b INT, c STRING, PRIMARY KEY (j,k) NOT ENFORCED)"
                        + " WITH ('merge-engine'='partial-update');",
                "CREATE TABLE IF NOT EXISTS dwd_orders ("
                        + "OrderID INT, OrderNumber INT, PersonID INT, LastName STRING, FirstName STRING, Age INT, PRIMARY KEY (OrderID) NOT ENFORCED)"
                        + " WITH ('merge-engine'='partial-update', 'partial-update.ignore-delete'='true');",
                "CREATE TABLE IF NOT EXISTS ods_orders (OrderID INT, OrderNumber INT, PersonID INT, PRIMARY KEY (OrderID) NOT ENFORCED) WITH ('changelog-producer'='input', 'continuous.discovery-interval'='1s');",
                "CREATE TABLE IF NOT EXISTS dim_persons (PersonID INT, LastName STRING, FirstName STRING, Age INT, PRIMARY KEY (PersonID) NOT ENFORCED) WITH ('changelog-producer'='input', 'continuous.discovery-interval'='1s');");
    }

    @Test
    public void testMergeInMemory() {
        batchSql(
                "INSERT INTO T VALUES "
                        + "(1, 2, 3, CAST(NULL AS INT), '5'), "
                        + "(1, 2, CAST(NULL AS INT), 6, CAST(NULL AS STRING))");
        List<Row> result = batchSql("SELECT * FROM T");
        assertThat(result).containsExactlyInAnyOrder(Row.of(1, 2, 3, 6, "5"));
    }

    @Test
    public void testMergeRead() {
        batchSql("INSERT INTO T VALUES (1, 2, 3, CAST(NULL AS INT), CAST(NULL AS STRING))");
        batchSql("INSERT INTO T VALUES (1, 2, 4, 5, CAST(NULL AS STRING))");
        batchSql("INSERT INTO T VALUES (1, 2, 4, CAST(NULL AS INT), '6')");

        assertThat(batchSql("SELECT * FROM T")).containsExactlyInAnyOrder(Row.of(1, 2, 4, 5, "6"));

        // projection
        assertThat(batchSql("SELECT a FROM T")).containsExactlyInAnyOrder(Row.of(4));
    }

    @Test
    public void testMergeCompaction() {
        // Wait compaction
        batchSql("ALTER TABLE T SET ('commit.force-compact'='true')");

        // key 1 2
        batchSql("INSERT INTO T VALUES (1, 2, 3, CAST(NULL AS INT), CAST(NULL AS STRING))");
        batchSql("INSERT INTO T VALUES (1, 2, 4, 5, CAST(NULL AS STRING))");
        batchSql("INSERT INTO T VALUES (1, 2, 4, CAST(NULL AS INT), '6')");

        // key 1 3
        batchSql("INSERT INTO T VALUES (1, 3, CAST(NULL AS INT), 1, '1')");
        batchSql("INSERT INTO T VALUES (1, 3, 2, 3, CAST(NULL AS STRING))");
        batchSql("INSERT INTO T VALUES (1, 3, CAST(NULL AS INT), 4, CAST(NULL AS STRING))");

        assertThat(batchSql("SELECT * FROM T"))
                .containsExactlyInAnyOrder(Row.of(1, 2, 4, 5, "6"), Row.of(1, 3, 2, 4, "1"));
    }

    @Test
    public void testForeignKeyJoin() throws Exception {
        sEnv.getConfig()
                .set(
                        ExecutionConfigOptions.TABLE_EXEC_SINK_UPSERT_MATERIALIZE,
                        ExecutionConfigOptions.UpsertMaterialize.NONE);
        CloseableIterator<Row> iter =
                streamSqlIter(
                        "INSERT INTO dwd_orders "
                                + "SELECT OrderID, OrderNumber, PersonID, CAST(NULL AS STRING), CAST(NULL AS STRING), CAST(NULL AS INT) FROM ods_orders "
                                + "UNION ALL "
                                + "SELECT OrderID, CAST(NULL AS INT), dim_persons.PersonID, LastName, FirstName, Age FROM dim_persons JOIN ods_orders ON dim_persons.PersonID = ods_orders.PersonID;");

        batchSql("INSERT INTO ods_orders VALUES (1, 2, 3)");
        batchSql("INSERT INTO dim_persons VALUES (3, 'snow', 'jon', 23)");
        Awaitility.await()
                .pollInSameThread()
                .atMost(5, TimeUnit.SECONDS)
                .untilAsserted(
                        () ->
                                assertThat(rowsToList(batchSql("SELECT * FROM dwd_orders")))
                                        .containsExactly(
                                                Arrays.asList(1, 2, 3, "snow", "jon", 23)));

        batchSql("INSERT INTO ods_orders VALUES (1, 4, 3)");
        batchSql("INSERT INTO dim_persons VALUES (3, 'snow', 'targaryen', 23)");
        Awaitility.await()
                .pollInSameThread()
                .atMost(5, TimeUnit.SECONDS)
                .untilAsserted(
                        () ->
                                assertThat(rowsToList(batchSql("SELECT * FROM dwd_orders")))
                                        .containsExactly(
                                                Arrays.asList(1, 4, 3, "snow", "targaryen", 23)));

        iter.close();
    }

    private List<List<Object>> rowsToList(List<Row> rows) {
        return rows.stream().map(this::toList).collect(Collectors.toList());
    }

    private List<Object> toList(Row row) {
        assertThat(row.getKind()).isIn(RowKind.INSERT, RowKind.UPDATE_AFTER);
        List<Object> result = new ArrayList<>();
        for (int i = 0; i < row.getArity(); i++) {
            result.add(row.getField(i));
        }
        return result;
    }

    @Test
    public void testStreamingRead() {
        assertThatThrownBy(
                () -> sEnv.from("T").execute().print(),
                "Partial update continuous reading is not supported");
    }

    @Test
    public void testStreamingReadChangelogInput() throws TimeoutException {
        sql(
                "CREATE TABLE INPUT_T ("
                        + "a INT, b INT, c INT, PRIMARY KEY (a) NOT ENFORCED)"
                        + " WITH ('merge-engine'='partial-update', 'changelog-producer'='input');");
        BlockingIterator<Row, Row> iterator =
                BlockingIterator.of(streamSqlIter("SELECT * FROM INPUT_T"));
        sql("INSERT INTO INPUT_T VALUES (1, CAST(NULL AS INT), 1)");
        assertThat(iterator.collect(1)).containsExactlyInAnyOrder(Row.of(1, null, 1));
        sql("INSERT INTO INPUT_T VALUES (1, 1, CAST(NULL AS INT)), (2, 2, 2)");
        assertThat(iterator.collect(2))
                .containsExactlyInAnyOrder(Row.of(1, 1, null), Row.of(2, 2, 2));
    }

    @Test
    public void testSequenceGroup() {
        sql(
                "CREATE TABLE SG ("
                        + "k INT, a INT, b INT, g_1 INT, c INT, d INT, g_2 INT, PRIMARY KEY (k) NOT ENFORCED)"
                        + " WITH ("
                        + "'merge-engine'='partial-update', "
                        + "'fields.g_1.sequence-group'='a,b', "
                        + "'fields.g_2.sequence-group'='c,d');");

        sql("INSERT INTO SG VALUES (1, 1, 1, 1, 1, 1, 1)");

        // g_2 should not be updated
        sql("INSERT INTO SG VALUES (1, 2, 2, 2, 2, 2, CAST(NULL AS INT))");

        // select *
        assertThat(sql("SELECT * FROM SG")).containsExactlyInAnyOrder(Row.of(1, 2, 2, 2, 1, 1, 1));

        // projection
        assertThat(sql("SELECT c, d FROM SG")).containsExactlyInAnyOrder(Row.of(1, 1));

        // g_1 should not be updated
        sql("INSERT INTO SG VALUES (1, 3, 3, 1, 3, 3, 3)");

        assertThat(sql("SELECT * FROM SG")).containsExactlyInAnyOrder(Row.of(1, 2, 2, 2, 3, 3, 3));

        // d should be updated by null
        sql("INSERT INTO SG VALUES (1, 3, 3, 3, 2, 2, CAST(NULL AS INT))");
        sql("INSERT INTO SG VALUES (1, 4, 4, 4, 2, 2, CAST(NULL AS INT))");
        sql("INSERT INTO SG VALUES (1, 5, 5, 3, 5, CAST(NULL AS INT), 4)");

        assertThat(sql("SELECT a, b FROM SG")).containsExactlyInAnyOrder(Row.of(4, 4));
        assertThat(sql("SELECT c, d FROM SG")).containsExactlyInAnyOrder(Row.of(5, null));
    }

    @Test
    public void testInvalidSequenceGroup() {
        Assertions.assertThatThrownBy(
                        () ->
                                sql(
                                        "CREATE TABLE SG ("
                                                + "k INT, a INT, b INT, g_1 INT, c INT, d INT, g_2 INT, PRIMARY KEY (k) NOT ENFORCED)"
                                                + " WITH ("
                                                + "'merge-engine'='partial-update', "
                                                + "'fields.g_0.sequence-group'='a,b', "
                                                + "'fields.g_2.sequence-group'='c,d');"))
                .hasRootCauseMessage(
                        "The sequence field group: g_0 can not be found in table schema.");

        Assertions.assertThatThrownBy(
                        () ->
                                sql(
                                        "CREATE TABLE SG ("
                                                + "k INT, a INT, b INT, g_1 INT, c INT, d INT, g_2 INT, PRIMARY KEY (k) NOT ENFORCED)"
                                                + " WITH ("
                                                + "'merge-engine'='partial-update', "
                                                + "'fields.g_1.sequence-group'='a1,b', "
                                                + "'fields.g_2.sequence-group'='c,d');"))
                .hasRootCauseMessage("Field a1 can not be found in table schema.");

        Assertions.assertThatThrownBy(
                        () ->
                                sql(
                                        "CREATE TABLE SG ("
                                                + "k INT, a INT, b INT, g_1 INT, c INT, d INT, g_2 INT, PRIMARY KEY (k) NOT ENFORCED)"
                                                + " WITH ("
                                                + "'merge-engine'='partial-update', "
                                                + "'fields.g_1.sequence-group'='a,b', "
                                                + "'fields.g_2.sequence-group'='a,d');"))
                .hasRootCauseMessage(
                        "Field a is defined repeatedly by multiple groups: [g_1, g_2].");
    }

    @Test
    public void testProjectPushDownWithLookupChangelogProducer() {
        sql(
                "CREATE TABLE IF NOT EXISTS T_P ("
                        + "j INT, k INT, a INT, b INT, c STRING, PRIMARY KEY (j,k) NOT ENFORCED)"
                        + " WITH ('merge-engine'='partial-update', 'changelog-producer' = 'lookup', "
                        + "'fields.a.sequence-group'='j', 'fields.b.sequence-group'='c');");
        batchSql("INSERT INTO T_P VALUES (1, 1, 1, 1, '1')");
        assertThat(sql("SELECT k, c FROM T_P")).containsExactlyInAnyOrder(Row.of(1, "1"));
    }

    @Test
    public void testLocalMerge() {
        sql(
                "CREATE TABLE T1 ("
                        + "k INT,"
                        + "v INT,"
                        + "d INT,"
                        + "PRIMARY KEY (k, d) NOT ENFORCED) PARTITIONED BY (d) "
                        + " WITH ('merge-engine'='partial-update', "
                        + "'local-merge-buffer-size'='1m'"
                        + ");");

        sql("INSERT INTO T1 VALUES (1, CAST(NULL AS INT), 1), (2, 1, 1), (1, 2, 1)");
        assertThat(batchSql("SELECT * FROM T1"))
                .containsExactlyInAnyOrder(Row.of(1, 2, 1), Row.of(2, 1, 1));
    }

    @Test
    public void testPartialUpdateWithAggregation() {
        sql(
                "CREATE TABLE AGG ("
                        + "k INT, a INT, b INT, g_1 INT, c VARCHAR, g_2 INT, PRIMARY KEY (k) NOT ENFORCED)"
                        + " WITH ("
                        + "'merge-engine'='partial-update', "
                        + "'fields.a.aggregate-function'='sum', "
                        + "'fields.g_1.sequence-group'='a', "
                        + "'fields.g_2.sequence-group'='c');");
        // a in group g_1 with sum agg
        // b not in group
        // c in group g_2 without agg

        sql("INSERT INTO AGG VALUES (1, 1, 1, 1, '1', 1)");

        // g_2 should not be updated
        sql("INSERT INTO AGG VALUES (1, 2, 2, 2, '2', CAST(NULL AS INT))");

        // select *
        assertThat(sql("SELECT * FROM AGG")).containsExactlyInAnyOrder(Row.of(1, 3, 2, 2, "1", 1));

        // projection
        assertThat(sql("SELECT a, c FROM AGG")).containsExactlyInAnyOrder(Row.of(3, "1"));

        // g_1 should not be updated
        sql("INSERT INTO AGG VALUES (1, 3, 3, 1, '3', 3)");

        assertThat(sql("SELECT * FROM AGG")).containsExactlyInAnyOrder(Row.of(1, 6, 3, 2, "3", 3));

        sql(
                "INSERT INTO AGG VALUES (1, CAST(NULL AS INT), CAST(NULL AS INT), 2, CAST(NULL AS VARCHAR), 4)");

        // a keep the last accumulator
        // b is not updated to null
        // c updated to null
        assertThat(sql("SELECT a, b, c FROM AGG")).containsExactlyInAnyOrder(Row.of(6, 3, null));
    }

    @Test
    public void testFirstValuePartialUpdate() {
        sql(
                "CREATE TABLE AGG ("
                        + "k INT, a INT, g_1 INT, PRIMARY KEY (k) NOT ENFORCED)"
                        + " WITH ("
                        + "'merge-engine'='partial-update', "
                        + "'fields.g_1.sequence-group'='a', "
                        + "'fields.a.aggregate-function'='first_value');");

        sql("INSERT INTO AGG VALUES (1, 1, 1), (1, 2, 2)");

        assertThat(sql("SELECT * FROM AGG")).containsExactlyInAnyOrder(Row.of(1, 1, 2));

        // old sequence
        sql("INSERT INTO AGG VALUES (1, 0, 0)");

        assertThat(sql("SELECT * FROM AGG")).containsExactlyInAnyOrder(Row.of(1, 0, 2));
    }
}
