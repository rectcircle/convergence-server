package com.convergencelabs.server.datastore.domain

import com.convergencelabs.server.domain.model.ModelFqn
import com.convergencelabs.server.domain.model.ot.ops.Operation
import org.json4s.JsonAST.JValue
import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import org.json4s.JsonAST.JValue
import com.convergencelabs.server.domain.model.ModelFqn
import com.convergencelabs.server.domain.model.ot.ops.Operation
import com.orientechnologies.orient.core.db.OPartitionedDatabasePool
import com.orientechnologies.orient.core.record.impl.ODocument
import com.orientechnologies.orient.core.sql.query.OResultSet
import com.orientechnologies.orient.core.sql.query.OSQLSynchQuery
import org.json4s.NoTypeHints
import com.fasterxml.jackson.databind.node.ObjectNode
import org.json4s.jackson.Serialization
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.json4s.jackson.Serialization.{ read, write }
import com.convergencelabs.server.domain.model.ot.ops.StringInsertOperation
import com.convergencelabs.server.domain.model.ot.ops.CompoundOperation
import com.orientechnologies.orient.core.sql.OCommandSQL
import com.orientechnologies.orient.core.metadata.schema.OType
import org.json4s.JsonAST.JNumber
import scala.collection.immutable.HashMap
import com.orientechnologies.orient.core.db.record.OTrackedMap
import java.time.LocalDateTime

object ModelStore {
  val CollectionId = "collectionId"
  val ModelId = "modelId"
  val CreatedTime = "createdTime"
  val ModifiedTime = "modifiedTime"
  val Version = "version"
  val Data = "data"

  def toOrientPath(path: List[Any]): String = {
    ???
  }
}

class ModelStore(dbPool: OPartitionedDatabasePool) {

  private[this] implicit val formats = Serialization.formats(NoTypeHints)

  def modelExists(fqn: ModelFqn): Boolean = {
    val db = dbPool.acquire()
    val query = new OSQLSynchQuery[ODocument]("SELECT modelId FROM model WHERE collectionId = :collectionId and modelId = :modelId")
    val params: java.util.Map[String, String] = HashMap("collectionId" -> fqn.collectionId, "modelId" -> fqn.modelId)
    val result: java.util.List[ODocument] = db.command(query).execute(params)
    db.close()
    !result.isEmpty()
  }

  def createModel(fqn: ModelFqn, data: JValue, creationTime: Long): Unit = {
    val db = dbPool.acquire()
    val dataObject = JObject(List((ModelStore.Data, data)))
    val doc = db.newInstance("model")
    doc.fromJSON(compact(render(dataObject)))
    doc.field(ModelStore.ModelId, fqn.modelId)
    doc.field(ModelStore.CollectionId, fqn.collectionId)
    doc.field(ModelStore.Version, 0)
    doc.field(ModelStore.CreatedTime, creationTime)
    doc.field(ModelStore.ModifiedTime, creationTime)
    db.save(doc)
    db.close()
  }

  def deleteModel(fqn: ModelFqn): Unit = {
    val db = dbPool.acquire()
    val command = new OCommandSQL("DELETE FROM model WHERE collectionId = :collectionId AND modelId = :modelId")
    val params = Map("collectionId" -> fqn.collectionId, "modelId" -> fqn.modelId)
    db.command(command).execute(params);
  }

  def getModelMetaData(fqn: ModelFqn): Option[ModelMetaData] = {
    val db = dbPool.acquire()
    val query = new OSQLSynchQuery[ODocument]("SELECT modelId, collectionId, version, created, modified FROM model WHERE collectionId = :collectionId AND modelId = :modelId")
    val params: java.util.Map[String, String] = HashMap("collectionId" -> fqn.collectionId, "modelId" -> fqn.modelId)

    val result: java.util.List[ODocument] = db.command(query).execute(params)
    result.asScala.toList match {
      case doc :: rest => Some(ModelMetaData(fqn, doc.field("version", OType.LONG), doc.field("created", OType.LONG), doc.field("modified", OType.LONG)))
      case Nil         => None
    }
  }

  def getModelData(fqn: ModelFqn): Option[ModelData] = {
    val db = dbPool.acquire()
    val query = new OSQLSynchQuery[ODocument]("SELECT FROM model WHERE collectionId = :collectionId and modelId = :modelId")
    val params: java.util.Map[String, String] = HashMap("collectionId" -> fqn.collectionId, "modelId" -> fqn.modelId)
    val result: java.util.List[ODocument] = db.command(query).execute(params)
    result.asScala.toList match {
      case doc :: rest => Some(ModelData(ModelMetaData(fqn, doc.field("version", OType.LONG), doc.field("creationTime", OType.LONG), doc.field("modifiedTime", OType.LONG)), parse(doc.toJSON()) \\ ModelStore.Data))
      case Nil         => None
    }
  }

  def getModelJsonData(fqn: ModelFqn): Option[JValue] = {
    val db = dbPool.acquire()
    val query = new OSQLSynchQuery[ODocument]("SELECT data FROM model WHERE collectionId = :collectionId and modelId = :modelId")
    val params: java.util.Map[String, String] = HashMap("collectionId" -> fqn.collectionId, "modelId" -> fqn.modelId)
    val result: java.util.List[ODocument] = db.command(query).execute(params)
    result.asScala.toList match {
      case doc :: rest => Some(parse(doc.toJSON()) \\ ModelStore.Data)
      case Nil         => None
    }
  }

  def applyOperationToModel(fqn: ModelFqn, operation: Operation, version: Long, timestamp: Long, username: String): Unit = {
    operation match {
      case compoundOp: CompoundOperation => compoundOp.operations foreach { op => applyOperationToModel(fqn, op, version, timestamp, username) }
      case _                             => // FIXME
    }
  }

  def getModelFieldDataType(fqn: ModelFqn, path: List[Any]): Option[DataType.Value] = {
    val db = dbPool.acquire()
    val pathString = ModelStore.toOrientPath(path)
    val query = new OSQLSynchQuery[ODocument](s"SELECT pathString FROM model WHERE collectionId = :collectionId and modelId = :modelId")
    val params: java.util.Map[String, String] = HashMap("collectionId" -> fqn.collectionId, "modelId" -> fqn.modelId)
    val result: java.util.List[ODocument] = db.command(query).execute(params)
    result.asScala.toList match {
      case doc :: rest => {
        (parse(doc.toJSON()) \\ ModelStore.Data) match {
          case data: JObject => Some(DataType.OBJECT)
          case data: JArray  => Some(DataType.ARRAY)
          case data: JString => Some(DataType.STRING)
          case data: JNumber => Some(DataType.NUMBER)
          case data: JBool   => Some(DataType.BOOLEAN)
          case _             => Some(DataType.NULL)
        }
      }
      case Nil => None
    }
  }

  def getAllModels(orderBy: String, ascending: Boolean, offset: Int, limit: Int): List[ModelMetaData] = {
    val db = dbPool.acquire()
    val query = new OSQLSynchQuery[ODocument]("SELECT modelId, collectionId, version, created, modified FROM model")
    val result: java.util.List[ODocument] = db.command(query).execute()
    result.asScala.toList map { doc =>
      ModelMetaData(
        ModelFqn(
          doc.field(ModelStore.CollectionId),
          doc.field(ModelStore.ModelId)),
        doc.field(ModelStore.Version, OType.LONG),
        doc.field(ModelStore.CreatedTime, OType.LONG),
        doc.field(ModelStore.ModifiedTime, OType.LONG))
    }
  }

  def getAllModelsInCollection(collectionId: String, orderBy: String, ascending: Boolean, offset: Int, limit: Int): List[ModelMetaData] = {
    val db = dbPool.acquire()
    val query = new OSQLSynchQuery[ODocument]("SELECT modelId, collectionId, version, created, modified FROM model where collectionId = :collectionId")
    val params: java.util.Map[String, String] = HashMap("collectionid" -> collectionId)
    val result: java.util.List[ODocument] = db.command(query).execute(params)
    result.asScala.toList map { doc => ModelMetaData(ModelFqn(collectionId, doc.field("modelId")), doc.field("version", OType.LONG), doc.field("created", OType.LONG), doc.field("modified", OType.LONG)) }
  }
}

case class ModelData(metaData: ModelMetaData, data: JValue)
case class ModelMetaData(fqn: ModelFqn, version: Long, createdTime: Long, modifiedTime: Long)

object DataType extends Enumeration {
  val ARRAY, OBJECT, STRING, NUMBER, BOOLEAN, NULL = Value
}
