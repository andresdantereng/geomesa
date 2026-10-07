/***********************************************************************
 * Copyright (c) 2013-2025 General Atomics Integrated Intelligence, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * https://www.apache.org/licenses/LICENSE-2.0
 ***********************************************************************/

package org.locationtech.geomesa.gt.partition.postgis.dialect
package tables

import org.locationtech.geomesa.gt.partition.postgis.dialect.auths.SessionDataSource
import org.locationtech.geomesa.gt.partition.postgis.dialect.PartitionedPostgisDialect.SftUserData

/**
 * Main view of all the partitions and write ahead table. This should accept and reads and writes.
 */
object MainView extends SqlStatements {

  import SessionDataSource.AuthConfigName

  override protected def createStatements(info: TypeInfo): Seq[String] = {
    val filter = info.cols.vis.fold("") { vis =>
      val cap = SftUserData.VisibilityDecisionArrayMaxValues.get(info.userData)
      s""" WHERE (
         |    (SELECT count <= $cap FROM sidecar_count) AND (
         |      ${vis.quoted} = ANY(ARRAY(SELECT unnest(values) FROM allowed_values)) OR
         |      (${vis.quoted} IS NULL AND (SELECT pg_vis(NULL::varchar, values) FROM auths))
         |    )
         |  ) OR (
         |    NOT (SELECT count <= $cap FROM sidecar_count) AND
         |    pg_vis(${vis.quoted}, (SELECT values FROM auths))
         |  )""".stripMargin.replace('\n', ' ')
    }
    val ctes = info.cols.vis.fold("") { _ =>
      val table = info.tables.visibilityValues.name.qualified
      s"""WITH auths AS MATERIALIZED (
         |  SELECT string_to_array(current_setting('$AuthConfigName', true), ',') AS values
         |), sidecar_count AS MATERIALIZED (
         |  SELECT count(*) AS count FROM $table
         |), allowed_values AS MATERIALIZED (
         |  SELECT array_agg(vis) AS values
         |  FROM $table CROSS JOIN auths
         |  WHERE (SELECT count <= ${SftUserData.VisibilityDecisionArrayMaxValues.get(info.userData)} FROM sidecar_count)
         |    AND pg_vis(vis, auths.values)
         |)
         |""".stripMargin
    }
    val select =
      s"""SELECT * FROM ${info.tables.writeAhead.name.qualified}$filter UNION ALL
         |SELECT * FROM ${info.tables.writeAheadPartitions.name.qualified}$filter UNION ALL
         |SELECT * FROM ${info.tables.mainPartitions.name.qualified}$filter UNION ALL
         |SELECT * FROM ${info.tables.spillPartitions.name.qualified}$filter;""".stripMargin
    val body = if (ctes.isEmpty) { select } else { s"$ctes$select" }
    Seq(s"CREATE OR REPLACE VIEW ${info.tables.view.name.qualified} AS\n$body")
  }

  override protected def dropStatements(info: TypeInfo): Seq[String] =
    Seq(s"DROP VIEW IF EXISTS ${info.tables.view.name.qualified};")
}
