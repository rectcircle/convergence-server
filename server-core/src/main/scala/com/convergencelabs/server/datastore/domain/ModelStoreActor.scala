package com.convergencelabs.server.datastore

import scala.util.Success

import com.convergencelabs.server.datastore.ModelStoreActor.CreateModel
import com.convergencelabs.server.datastore.ModelStoreActor.CreateOrUpdateModel
import com.convergencelabs.server.datastore.ModelStoreActor.DeleteModel
import com.convergencelabs.server.datastore.domain.CollectionStore
import com.convergencelabs.server.datastore.domain.ModelStore
import com.convergencelabs.server.domain.model.ModelFqn
import com.convergencelabs.server.domain.model.data.ArrayValue
import com.convergencelabs.server.domain.model.data.BooleanValue
import com.convergencelabs.server.domain.model.data.DataValue
import com.convergencelabs.server.domain.model.data.DoubleValue
import com.convergencelabs.server.domain.model.data.NullValue
import com.convergencelabs.server.domain.model.data.ObjectValue
import com.convergencelabs.server.domain.model.data.StringValue

import ModelStoreActor.GetModel
import ModelStoreActor.GetModels
import ModelStoreActor.GetModelsInCollection
import akka.actor.ActorLogging
import akka.actor.Props
import com.convergencelabs.server.domain.model.data.DateValue
import java.time.Instant
import com.convergencelabs.server.datastore.domain.ModelPermissions
import com.convergencelabs.server.domain.model.GetModelPermissionsRequest
import com.convergencelabs.server.domain.model.ModelCreator
import com.convergencelabs.server.datastore.domain.DomainPersistenceProvider

object ModelStoreActor {
  def props(
    persistenceProvider: DomainPersistenceProvider): Props =
    Props(new ModelStoreActor(persistenceProvider))

  trait ModelStoreRequest

  case class CreateOrUpdateModel(collectionId: String, modelId: String, data: Map[String, Any], overridePermissions: Option[Boolean], worldPermissions: Option[ModelPermissions]) extends ModelStoreRequest
  case class CreateModel(collectionId: String, data: Map[String, Any], overridePermissions: Option[Boolean], worldPermissions: Option[ModelPermissions]) extends ModelStoreRequest

  case class GetModels(offset: Option[Int], limit: Option[Int]) extends ModelStoreRequest
  case class GetModelsInCollection(collectionId: String, offset: Option[Int], limit: Option[Int]) extends ModelStoreRequest
  case class GetModel(modelFqn: ModelFqn) extends ModelStoreRequest

  case class DeleteModel(modelFqn: ModelFqn) extends ModelStoreRequest

  case class CollectionInfo(id: String, name: String)
}

class ModelStoreActor private[datastore] (private[this] val persistenceProvider: DomainPersistenceProvider)
    extends StoreActor with ActorLogging {

  def receive: Receive = {
    case GetModels(offset, limit) =>
      getModels(offset, limit)
    case GetModelsInCollection(collectionId, offset, limit) =>
      getModelsInCollection(collectionId, offset, limit)
    case GetModel(modelFqn) =>
      getModel(modelFqn)
    case DeleteModel(modelFqn) =>
      deleteModel(modelFqn)
    case CreateModel(collectionId, data, overridePermissions, worldPermissions) =>
      createModel(collectionId, data, overridePermissions, worldPermissions)
    case CreateOrUpdateModel(collectionId, modelId, data, overridePermissions, worldPermissions) =>
      createOrUpdateModel(collectionId, modelId, data, overridePermissions, worldPermissions)

    case message: Any => unhandled(message)
  }

  def getModels(offset: Option[Int], limit: Option[Int]): Unit = {
    reply(persistenceProvider.modelStore.getAllModelMetaData(offset, limit))
  }

  def getModelsInCollection(collectionId: String, offset: Option[Int], limit: Option[Int]): Unit = {
    reply(persistenceProvider.modelStore.getAllModelMetaDataInCollection(collectionId, offset, limit))
  }

  def getModel(modelFqn: ModelFqn): Unit = {
    reply(persistenceProvider.modelStore.getModel(modelFqn))
  }

  def createModel(collectionId: String, data: Map[String, Any], overridePermissions: Option[Boolean], worldPermissions: Option[ModelPermissions]): Unit = {
    val root = ModelDataGenerator(data)
    // FIXME should we have an owner
    val result = ModelCreator.createModel(
            persistenceProvider,
            None,
            collectionId,
            None,
            root,
            overridePermissions,
            worldPermissions
          ).map (model => model.metaData.fqn)
    
    reply(result)
  }

  def createOrUpdateModel(collectionId: String, modelId: String, data: Map[String, Any], overridePermissions: Option[Boolean], worldPermissions: Option[ModelPermissions]): Unit = {
    //FIXME If the model is open this could cause problems.
    val root = ModelDataGenerator(data)
    val result = persistenceProvider.collectionStore.ensureCollectionExists(collectionId) flatMap { _ =>
      persistenceProvider.modelStore.createModel(collectionId, Some(modelId), root, overridePermissions.getOrElse(false),
        worldPermissions.getOrElse(ModelPermissions(false, false, false, false))) map { _ =>
          ModelFqn(collectionId, modelId)
        } recoverWith {
          case e: DuplicateValueExcpetion =>
            persistenceProvider.modelStore.updateModel(ModelFqn(collectionId, modelId), root, worldPermissions) map { _ =>
              ModelFqn(collectionId, modelId)
            }
        }
    }
    reply(result)
  }

  def deleteModel(modelFqn: ModelFqn): Unit = {
    // FIXME If the model is open this could cause problems.
    reply(persistenceProvider.modelStore.deleteModel(modelFqn))
  }
}

object ModelDataGenerator {
  def apply(data: Map[String, Any]): ObjectValue = {
    val gen = new ModelDataGenerator()
    gen.create(data)
  }
}

class ModelDataGenerator() {
  val ServerIdPrefix = "0:";
  var id: Int = 0;

  def create(data: Map[String, Any]): ObjectValue = {
    map(data).asInstanceOf[ObjectValue]
  }

  private[this] def map(value: Any): DataValue = {
    value match {
      case obj: Map[Any, Any] =>
        if (obj.contains("$convergenceType")) {
          DateValue(nextId(), Instant.parse(obj.get("value").toString()))
        } else {
          val children = obj map {
            case (k, v) => (k.toString, this.map(v))
          }
          ObjectValue(nextId(), children)
        }
      case arr: List[_] =>
        val array = arr.map(v => this.map(v))
        ArrayValue(nextId(), array)
      case num: Double =>
        DoubleValue(nextId(), num)
      case num: Int =>
        DoubleValue(nextId(), num.doubleValue())
      case num: BigInt =>
        DoubleValue(nextId(), num.doubleValue())
      case num: Long =>
        DoubleValue(nextId(), num.doubleValue())
      case bool: Boolean =>
        BooleanValue(nextId(), bool)
      case str: String =>
        StringValue(nextId(), str)
      case date: Instant =>
        DateValue(nextId(), date)
      case null =>
        NullValue(nextId())
    }
  }

  private[this] def nextId(): String = {
    id = id + 1
    this.ServerIdPrefix + id
  }
}
