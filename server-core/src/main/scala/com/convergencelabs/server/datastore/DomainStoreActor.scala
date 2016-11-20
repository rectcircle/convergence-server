package com.convergencelabs.server.datastore

import scala.util.Failure
import scala.util.Success

import com.convergencelabs.server.datastore.DomainStoreActor.CreateDomainRequest
import com.convergencelabs.server.datastore.DomainStoreActor.DeleteDomainRequest
import com.convergencelabs.server.datastore.DomainStoreActor.UpdateDomainRequest
import com.convergencelabs.server.datastore.DomainStoreActor.GetDomainRequest
import com.convergencelabs.server.datastore.DomainStoreActor.ListDomainsRequest
import com.convergencelabs.server.domain.Domain
import com.convergencelabs.server.domain.DomainFqn
import com.orientechnologies.orient.core.db.OPartitionedDatabasePool
import com.typesafe.config.Config
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

import akka.actor.ActorLogging
import akka.actor.Props
import akka.pattern.ask
import scala.util.Try
import scala.concurrent.ExecutionContext
import com.convergencelabs.server.domain.DomainStatus
import java.util.UUID
import com.convergencelabs.server.domain.DomainDatabaseInfo
import java.io.StringWriter
import java.io.PrintWriter
import com.convergencelabs.server.datastore.DomainStoreActor.DeleteDomainsForUserRequest
import akka.actor.ActorRef
import com.convergencelabs.server.db.provision.DomainProvisionerActor.ProvisionDomain
import akka.util.Timeout
import com.convergencelabs.server.db.provision.DomainProvisionerActor.DomainProvisioned
import com.convergencelabs.server.db.provision.DomainProvisionerActor.DestroyDomain
import com.convergencelabs.server.db.provision.DomainProvisionerActor.DomainDeleted
import com.convergencelabs.server.db.provision.DomainProvisionerActor
import com.convergencelabs.server.util.ExceptionUtils

class DomainStoreActor private[datastore] (
  private[this] val dbPool: OPartitionedDatabasePool,
  private[this] val domainProvisioner: ActorRef)
    extends StoreActor with ActorLogging {

  private[this] val RandomizeCredentials = context.system.settings.config.getBoolean("convergence.domain-databases.randomize-credentials")

  private[this] val domainStore: DomainStore = new DomainStore(dbPool)
  private[this] implicit val ec = context.system.dispatcher

  def receive: Receive = {
    case createRequest: CreateDomainRequest => createDomain(createRequest)
    case deleteRequest: DeleteDomainRequest => deleteDomain(deleteRequest)
    case updateRequest: UpdateDomainRequest => updateDomain(updateRequest)
    case getRequest: GetDomainRequest => getDomain(getRequest)
    case listRequest: ListDomainsRequest => listDomains(listRequest)
    case deleteForUser: DeleteDomainsForUserRequest => deleteDomainsForUser(deleteForUser)
    case message: Any => unhandled(message)
  }

  def createDomain(createRequest: CreateDomainRequest): Unit = {
    val CreateDomainRequest(namespace, domainId, displayName, ownerUsername) = createRequest
    
    val dbName = Math.abs(UUID.randomUUID().getLeastSignificantBits()).toString()
    val (dbUsername, dbPassword, dbAdminUsername, dbAdminPassword) = RandomizeCredentials match {
      case false =>
        ("writer", "writer", "admin", "admin")
      case true =>
        (UUID.randomUUID().toString(),
          UUID.randomUUID().toString(),
          UUID.randomUUID().toString(),
          UUID.randomUUID().toString())
    }

    val domainDbInfo = DomainDatabaseInfo(dbName, dbUsername, dbPassword, dbAdminUsername, dbAdminPassword)
    val domainFqn = DomainFqn(namespace, domainId)

    val currentSender = sender
    
    domainStore.createDomain(domainFqn, displayName, ownerUsername, domainDbInfo) map {
      case CreateSuccess(()) =>
        implicit val requstTimeout = Timeout(4 minutes) // FXIME hardcoded timeout
        val message = ProvisionDomain(dbName, dbPassword, dbPassword, dbAdminUsername, dbAdminPassword)
        (domainProvisioner ? message).mapTo[DomainProvisioned] onComplete {
          case Success(DomainProvisioned()) =>
            log.debug(s"Domain created, setting status to online: $dbName")
            domainStore.getDomainByFqn(domainFqn) map (_.map { domain =>
              val updated = domain.copy(status = DomainStatus.Online)
              domainStore.updateDomain(updated)
            })
            reply(Success(CreateSuccess(domainDbInfo)), currentSender)

          case Failure(cause) =>
            log.error(cause, s"Domain was not created successfully: $dbName")
            val statusMessage = ExceptionUtils.stackTraceToString(cause)
            domainStore.getDomainByFqn(domainFqn) map (_.map { domain =>
              val updated = domain.copy(status = DomainStatus.Error, statusMessage = statusMessage)
              domainStore.updateDomain(updated)
            })
            reply(Failure(cause), currentSender)
        }
      case InvalidValue =>
        reply(Success(InvalidValue), currentSender)
      case DuplicateValue =>
        reply(Success(InvalidValue), currentSender)
    } recover {
      case cause: Exception => reply(Failure(cause), currentSender)
    }
  }

  def updateDomain(request: UpdateDomainRequest): Unit = {
    val UpdateDomainRequest(namespace, domainId, displayName) = request
    reply(
      domainStore.getDomainByFqn(DomainFqn(namespace, domainId)).flatMap {
        case Some(domain) =>
          val updated = domain.copy(displayName = displayName)
          domainStore.updateDomain(updated)
        case None =>
          Success(NotFound)
      })
  }

  def deleteDomain(deleteRequest: DeleteDomainRequest): Unit = {
    val DeleteDomainRequest(namespace, domainId) = deleteRequest
    val domainFqn = DomainFqn(namespace, domainId)
    val domain = domainStore.getDomainByFqn(domainFqn)
    val databaseConfig = domainStore.getDomainDatabaseInfo(domainFqn)
    reply((domain, databaseConfig) match {
      case (Success(Some(domain)), Success(Some(databaseConfig))) => {
        domainStore.removeDomain(domainFqn)
        // FIXME we don't seem to care about the response?
        implicit val requstTimeout = Timeout(4 minutes) // FXIME hardcoded timeout
        (domainProvisioner ? DestroyDomain(databaseConfig.database))
        Success(DeleteSuccess)
      }
      case _ => Success(NotFound)
    })
  }

  def deleteDomainsForUser(request: DeleteDomainsForUserRequest): Unit = {
    val DeleteDomainsForUserRequest(username) = request
    log.debug(s"Deleting domains for user: ${username}")

    domainStore.getAllDomainInfoForUser(username) map { domains =>
      // FIXME we need to review what happens when something fails.
      // we will eventually delete the user and then we won't be
      // able to look up the domains again.
      sender ! (())

      domains.foreach {
        case (domain, info) =>
          log.debug(s"Deleting domain database for ${domain.domainFqn}: ${info.database}")
          // FIXME we don't seem to care about the response?
          implicit val requstTimeout = Timeout(4 minutes) // FXIME hardcoded timeout
          (domainProvisioner ? DestroyDomain(info.database)) onComplete {
            case Success(_) =>
              log.debug(s"Domain database deleted: ${info.database}")
              log.debug(s"Removing domain record: ${domain.domainFqn}")
              domainStore.removeDomain(domain.domainFqn) match {
                case Success(_) =>
                  log.debug(s"Domain record removed: ${domain.domainFqn}")
                case Failure(cause) =>
                  log.error(cause, s"Error deleting domain record: ${domain.domainFqn}")
              }
            case Failure(f) =>
              log.error(f, s"Could not desstroy domain database: ${domain.domainFqn}")
          }
      }
    } recover {
      case cause: Exception =>
        log.error(cause, s"Error deleting domains for user: ${username}")
    }
  }

  def getDomain(getRequest: GetDomainRequest): Unit = {
    val GetDomainRequest(namespace, domainId) = getRequest
    reply(domainStore.getDomainByFqn(DomainFqn(namespace, domainId)))
  }

  def listDomains(listRequest: ListDomainsRequest): Unit = {
    reply(domainStore.getDomainsByOwner(listRequest.username))
  }
}

object DomainStoreActor {
  def props(dbPool: OPartitionedDatabasePool,
    provisionerActor: ActorRef): Props =
    Props(new DomainStoreActor(dbPool, provisionerActor))

  case class CreateDomainRequest(namespace: String, domainId: String, displayName: String, owner: String)
  case class UpdateDomainRequest(namespace: String, domainId: String, displayName: String)
  case class DeleteDomainRequest(namespace: String, domainId: String)
  case class GetDomainRequest(namespace: String, domainId: String)
  case class ListDomainsRequest(username: String)
  case class DeleteDomainsForUserRequest(username: String)
}
