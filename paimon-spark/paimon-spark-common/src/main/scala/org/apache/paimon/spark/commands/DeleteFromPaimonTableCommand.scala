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
package org.apache.paimon.spark.commands

import org.apache.paimon.options.Options
import org.apache.paimon.predicate.OnlyPartitionKeyEqualVisitor
import org.apache.paimon.spark.{InsertInto, SparkTable}
import org.apache.paimon.spark.schema.SparkSystemColumns.ROW_KIND_COL
import org.apache.paimon.table.FileStoreTable
import org.apache.paimon.table.sink.BatchWriteBuilder
import org.apache.paimon.types.RowKind

import org.apache.spark.sql.{Row, SparkSession}
import org.apache.spark.sql.Utils.createDataset
import org.apache.spark.sql.catalyst.expressions.Literal.TrueLiteral
import org.apache.spark.sql.catalyst.plans.logical.{DeleteFromTable, Filter, LogicalPlan, SupportsSubquery}
import org.apache.spark.sql.execution.command.LeafRunnableCommand
import org.apache.spark.sql.functions.lit

import java.util.{Collections, UUID}

import scala.util.control.NonFatal

case class DeleteFromPaimonTableCommand(v2Table: SparkTable, delete: DeleteFromTable)
  extends LeafRunnableCommand
  with PaimonCommand {

  override def table: FileStoreTable = v2Table.getTable.asInstanceOf[FileStoreTable]

  private val relation = delete.table
  private val condition = delete.condition

  private lazy val (deletePredicate, forceDeleteByRows) =
    if (condition == null || condition == TrueLiteral) {
      (None, false)
    } else {
      try {
        (Some(convertConditionToPaimonPredicate(condition, relation.output)), false)
      } catch {
        case NonFatal(_) =>
          (None, true)
      }
    }

  override def run(sparkSession: SparkSession): Seq[Row] = {
    val commit = table.store.newCommit(UUID.randomUUID.toString)

    if (forceDeleteByRows) {
      deleteRowsByCondition(sparkSession)
    } else if (deletePredicate.isEmpty) {
      commit.purgeTable(BatchWriteBuilder.COMMIT_IDENTIFIER)
    } else {
      val visitor = new OnlyPartitionKeyEqualVisitor(table.partitionKeys)
      if (deletePredicate.get.visit(visitor)) {
        val dropPartitions = visitor.partitions()
        commit.dropPartitions(
          Collections.singletonList(dropPartitions),
          BatchWriteBuilder.COMMIT_IDENTIFIER)
      } else {
        deleteRowsByCondition(sparkSession)
      }
    }

    Seq.empty[Row]
  }

  private def deleteRowsByCondition(sparkSession: SparkSession): Unit = {
    val df = createDataset(sparkSession, Filter(condition, relation))
      .withColumn(ROW_KIND_COL, lit(RowKind.DELETE.toByteValue))

    WriteIntoPaimonTable(table, InsertInto, df, new Options()).run(sparkSession)
  }
}
