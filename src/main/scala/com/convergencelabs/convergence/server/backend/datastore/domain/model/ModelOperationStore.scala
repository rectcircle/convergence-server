/*
 * Copyright (c) 2019 - Convergence Labs, Inc.
 *
 * This file is part of the Convergence Server, which is released under
 * the terms of the GNU General Public License version 3 (GPLv3). A copy
 * of the GPLv3 should have been provided along with this file, typically
 * located in the "LICENSE" file, which is part of this source code package.
 * Alternatively, see <https://www.gnu.org/licenses/gpl-3.0.html> for the
 * full text of the GPLv3 license, if it was not provided.
 */

package com.convergencelabs.convergence.server.backend.datastore.domain.model

import com.convergencelabs.convergence.server.backend.datastore.domain.model.mapper.OrientDBOperationMapper
import com.convergencelabs.convergence.server.backend.datastore.domain.schema
import com.convergencelabs.convergence.server.backend.datastore.{AbstractDatabasePersistence, OrientDBUtil}
import com.convergencelabs.convergence.server.backend.db.DatabaseProvider
import com.convergencelabs.convergence.server.backend.services.domain.model.{ModelOperation, NewModelOperation}
import com.convergencelabs.convergence.server.model.domain.user.DomainUserId
import com.convergencelabs.convergence.server.util.{QueryLimit, QueryOffset}
import com.orientechnologies.orient.core.db.document.ODatabaseDocument
import com.orientechnologies.orient.core.record.impl.ODocument

import java.time.Instant
import java.util.Date
import scala.util.Try

class ModelOperationStore private[domain](dbProvider: DatabaseProvider)
  extends AbstractDatabasePersistence(dbProvider) {

  import ModelOperationStore._
  import schema.ModelOperationClass._

  private[this] val GetMaxVersionQuery = "SELECT max(version) as max FROM ModelOperation WHERE model.id = :modelId"

  def getMaxVersion(id: String): Try[Option[Long]] = withDb { db =>
    val params = Map(Constants.ModelId -> id)
    OrientDBUtil
      .findDocument(db, GetMaxVersionQuery, params)
      .map(_.flatMap(doc => Option(doc.getProperty("max"))))
  }

  private[this] val GetVersionAtOrBeforeTime = "SELECT max(version) as max FROM ModelOperation WHERE model.id = :modelId AND timestamp <= :time"

  def getVersionAtOrBeforeTime(id: String, time: Instant): Try[Option[Long]] = withDb { db =>
    val params = Map(Constants.ModelId -> id, "time" -> new java.util.Date(time.toEpochMilli))
    OrientDBUtil
      .findDocument(db, GetVersionAtOrBeforeTime, params)
      .map(_.flatMap(doc => Option(doc.getProperty("max"))))
  }

  private[this] val GetModelOperationQuery = "SELECT FROM ModelOperation WHERE model.id = :modelId AND version = :version"

  def getModelOperation(id: String, version: Long): Try[Option[ModelOperation]] = withDb { db =>
    val params = Map(Constants.ModelId -> id, "version" -> version)
    OrientDBUtil
      .findDocument(db, GetModelOperationQuery, params)
      .map(_.map(docToModelOperation))
  }

  private[this] val GetMaxOperationForSessionAfterVersionQuery =
    """SELECT
      |  version
      |FROM ModelOperation
      |WHERE
      |  model.id = :modelId AND
      |  version >= :version AND
      |  session.id = :sessionId
      |ORDER BY version DESC LIMIT 1""".stripMargin

  def getMaxOperationForSessionAfterVersion(id: String, sessionId: String, version: Long): Try[Option[Long]] = withDb { db =>
    val params = Map(Constants.ModelId -> id, Fields.Version -> version, "sessionId" -> sessionId)
    OrientDBUtil
      .findDocumentAndMap(db, GetMaxOperationForSessionAfterVersionQuery, params) { doc =>
        doc.getProperty(Fields.Version).asInstanceOf[Long]
      }
  }

  private[this] val GetOperationsAfterVersionQuery =
    """SELECT *
      |FROM ModelOperation
      |WHERE
      |  model.id = :modelId AND
      |  version >= :version
      |ORDER BY version ASC""".stripMargin

  def getOperationsAfterVersion(id: String, version: Long, limit: Option[Long] = None): Try[List[ModelOperation]] = withDb { db =>
    val query = OrientDBUtil.buildPagedQuery(GetOperationsAfterVersionQuery, QueryLimit(limit), QueryOffset())
    val params = Map(Constants.ModelId -> id, Fields.Version -> version)
    OrientDBUtil
      .query(db, query, params)
      .map(_.map(docToModelOperation))
  }

  private[this] val GetOperationsInVersionRangeQuery =
    """SELECT *
      |FROM ModelOperation
      |WHERE
      |  model.id = :modelId AND
      |  version >= :firstVersion AND
      |  version <= :lastVersion
      |ORDER BY version ASC""".stripMargin

  /**
   * Gets operations in an inclusive version range.
   *
   * @param modelId      The id of the model to get the operations for.
   * @param firstVersion The version (inclusive) of the first in the range.
   * @param lastVersion  The version (inclusive) of the last operation in the range.
   * @return A list of ordered (ascending version numbers) operations within the specified range.
   */
  def getOperationsInVersionRange(modelId: String, firstVersion: Long, lastVersion: Long): Try[List[ModelOperation]] = withDb { db =>
    val params = Map(Constants.ModelId -> modelId, "firstVersion" -> firstVersion, "lastVersion" -> lastVersion)
    OrientDBUtil
      .query(db, GetOperationsInVersionRangeQuery, params)
      .map(_.map(docToModelOperation))
  }

  private[this] val DeleteAllOperationsForModelCommand = "DELETE FROM ModelOperation WHERE model.id = :modelId"

  def deleteAllOperationsForModel(modelId: String, db: Option[ODatabaseDocument] = None): Try[Unit] = withDb(db) { db =>
    val params = Map(Constants.ModelId -> modelId)
    OrientDBUtil.commandReturningCount(db, DeleteAllOperationsForModelCommand, params).map(_ => ())
  }

  private[this] val CreateModelOperationCommand =
    """
      |INSERT INTO
      |  ModelOperation
      |SET
      |  model = (SELECT FROM Model WHERE id = :modelId),
      |  version = :version,
      |  timestamp = :timestamp,
      |  session = (SELECT FROM DomainSession WHERE id = :sessionId),
      |  operation = :operation
      |""".stripMargin

  def createModelOperation(modelOperation: NewModelOperation, db: Option[ODatabaseDocument] = None): Try[Unit] = withDb(db) { db =>
    val opDoc = OrientDBOperationMapper.operationToODocument(modelOperation.op)
    opDoc.save()

    val params = Map(
      "modelId" -> modelOperation.modelId,
      "sessionId" -> modelOperation.sessionId,
      "version" -> modelOperation.version,
      "timestamp" -> Date.from(modelOperation.timestamp),
      "operation" -> opDoc.getIdentity
    )

    OrientDBUtil.command(db, CreateModelOperationCommand, params).map(_ => ())
  }
}

object ModelOperationStore {

  import schema.ModelOperationClass._

  object Constants {
    val CollectionId = "collectionId"
    val ModelId = "modelId"
    val Username = "username"
  }

  private def docToModelOperation(doc: ODocument): ModelOperation = {
    val docDate: java.util.Date = doc.getProperty(Fields.Timestamp)
    val timestamp = Instant.ofEpochMilli(docDate.getTime)
    val opDoc: ODocument = doc.getProperty(Fields.Operation)
    val op = OrientDBOperationMapper.oDocumentToOperation(opDoc)
    val userType = doc.eval("session.user.userType").toString
    val username = doc.eval("session.user.username").toString
    val userId = DomainUserId(userType, username)

    ModelOperation(
      doc.eval("model.id").toString,
      doc.getProperty(Fields.Version),
      timestamp,
      userId,
      doc.eval("session.id").toString,
      op)
  }

  def deleteAllOperationsForCollection(collectionId: String, db: ODatabaseDocument): Try[Unit] = {
    val command = "DELETE FROM ModelOperation WHERE model.collection.id = :collectionId"
    val params = Map(Constants.CollectionId -> collectionId)
    OrientDBUtil.commandReturningCount(db, command, params).map(_ => ())
  }
}
