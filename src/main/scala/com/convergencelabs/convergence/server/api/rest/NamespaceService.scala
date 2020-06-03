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

package com.convergencelabs.convergence.server.api.rest

import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.http.scaladsl.marshalling.ToResponseMarshallable.apply
import akka.http.scaladsl.server.Directive.{addByNameNullaryApply, addDirectiveApply}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import com.convergencelabs.convergence.server.datastore.convergence.NamespaceStoreActor._
import com.convergencelabs.convergence.server.security.{AuthorizationProfile, Permissions}
import grizzled.slf4j.Logging

import scala.concurrent.{ExecutionContext, Future}


private[rest] class NamespaceService(private[this] val namespaceActor: ActorRef[Message],
                                     private[this] val system: ActorSystem[_],
                                     private[this] val executionContext: ExecutionContext,
                                     private[this] val defaultTimeout: Timeout)
  extends JsonSupport with Logging {

  import NamespaceService._

  private[this] implicit val ec: ExecutionContext = executionContext
  private[this] implicit val t: Timeout = defaultTimeout
  private[this] implicit val s: ActorSystem[_] = system

  val route: AuthorizationProfile => Route = { authProfile: AuthorizationProfile =>
    pathPrefix("namespaces") {
      pathEnd {
        get {
          parameters("filter".?, "limit".as[Int].?, "offset".as[Int].?) { (filter, limit, offset) =>
            complete(getNamespaces(authProfile, filter, offset, limit))
          }
        } ~ post {
          authorize(canManageNamespaces(authProfile)) {
            entity(as[CreateNamespacePost]) { request =>
              complete(createNamespace(authProfile, request))
            }
          }
        }
      } ~ pathPrefix(Segment) { namespace =>
        pathEnd {
          get {
            complete(getNamespace(namespace))
          } ~ put {
            authorize(canManageNamespaces(authProfile)) {
              entity(as[UpdateNamespacePut]) { request =>
                complete(updateNamespace(authProfile, namespace, request))
              }
            }
          } ~ delete {
            authorize(canManageNamespaces(authProfile)) {
              complete(deleteNamespace(authProfile, namespace))
            }
          }
        }
      }
    }
  }

  private[this] def getNamespaces(authProfile: AuthorizationProfile, filter: Option[String], offset: Option[Int], limit: Option[Int]): Future[RestResponse] = {
    namespaceActor.ask[GetAccessibleNamespacesResponse](GetAccessibleNamespacesRequest(authProfile.data, filter, offset, limit, _)).map {
      case GetAccessibleNamespacesSuccess(namespaces) =>
        val response = namespaces.map { n =>
          val domainData = n.domains.map(d => DomainRestData(d.displayName, d.domainFqn.namespace, d.domainFqn.domainId, d.status.toString))
          NamespaceAndDomainsRestData(n.id, n.displayName, domainData)
        }
        okResponse(response)
      case _ =>
        InternalServerError
    }
  }

  private[this] def getNamespace(namespaceId: String): Future[RestResponse] = {
    namespaceActor.ask[GetNamespaceResponse](GetNamespaceRequest(namespaceId, _)) map {
      case GetNamespaceSuccess(Some(namespace)) =>
        okResponse(namespace)
      case GetNamespaceSuccess(None) =>
        notFoundResponse(Some(s"A namespace with the id '$namespaceId' does not exist"))
      case _ =>
        InternalServerError
    }
  }

  private[this] def createNamespace(authProfile: AuthorizationProfile, create: CreateNamespacePost): Future[RestResponse] = {
    val CreateNamespacePost(id, displayName) = create
    namespaceActor.ask[CreateNamespaceResponse](CreateNamespaceRequest(authProfile.username, id, displayName, _)) map {
      case RequestSuccess() =>
        OkResponse
      case _ =>
        InternalServerError
    }
  }

  private[this] def updateNamespace(authProfile: AuthorizationProfile, namespaceId: String, update: UpdateNamespacePut): Future[RestResponse] = {
    val UpdateNamespacePut(displayName) = update
    namespaceActor.ask[UpdateNamespaceResponse](UpdateNamespaceRequest(authProfile.username, namespaceId, displayName, _)) map {
      case RequestSuccess() =>
        OkResponse
      case _ =>
        InternalServerError
    }
  }

  private[this] def deleteNamespace(authProfile: AuthorizationProfile, namespaceId: String): Future[RestResponse] = {
    namespaceActor.ask[DeleteNamespaceResponse](DeleteNamespaceRequest(authProfile.username, namespaceId, _)) map {
      case RequestSuccess() =>
        OkResponse
      case _ =>
        InternalServerError
    }
  }

  private[this] def canManageNamespaces(authProfile: AuthorizationProfile): Boolean = {
    authProfile.hasGlobalPermission(Permissions.Global.ManageDomains)
  }
}

private[rest] object NamespaceService {

  case class CreateNamespacePost(id: String, displayName: String)

  case class UpdateNamespacePut(displayName: String)

  case class NamespaceRestData(id: String, displayName: String)

  case class NamespaceAndDomainsRestData(id: String, displayName: String, domains: Set[DomainRestData])

}
