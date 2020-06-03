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
import akka.http.scaladsl.server.Directives.{Segment, _enhanceRouteWithConcatenation, _segmentStringToPathMatcher, as, authorize, complete, concat, delete, entity, get, path, pathEnd, pathPrefix, post}
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import com.convergencelabs.convergence.server.datastore.convergence.RoleStoreActor._
import com.convergencelabs.convergence.server.datastore.convergence.{DomainRoleTarget, NamespaceRoleTarget, RoleTarget, ServerRoleTarget}
import com.convergencelabs.convergence.server.domain.DomainId
import com.convergencelabs.convergence.server.security.{AuthorizationProfile, Permissions}

import scala.concurrent.{ExecutionContext, Future}


private[rest] class UserRoleService(private[this] val roleActor: ActorRef[Message],
                                    private[this] val system: ActorSystem[_],
                                    private[this] val executionContext: ExecutionContext,
                                    private[this] val defaultTimeout: Timeout) extends JsonSupport {

  private[this] implicit val ec: ExecutionContext = executionContext
  private[this] implicit val t: Timeout = defaultTimeout
  private[this] implicit val s: ActorSystem[_] = system

  val route: AuthorizationProfile => Route = { authProfile: AuthorizationProfile =>
    pathPrefix("roles") {
      concat(
        pathPrefix("server") {
          authorize(canManageUsers(authProfile)) {
            pathEnd {
              get {
                complete(getUserRolesForTarget(authProfile, ServerRoleTarget()))
              } ~ post {
                entity(as[Map[String, String]]) { userRoles =>
                  complete(updateUserRolesForTarget(authProfile, ServerRoleTarget(), userRoles))
                }
              }
            } ~ (delete & path(Segment)) { username =>
              complete(deleteUserRoleForTarget(authProfile, ServerRoleTarget(), username))
            }
          }
        },
        pathPrefix("namespace" / Segment) { namespace =>
          authorize(canManageNamespaceUsers(namespace, authProfile)) {
            pathEnd {
              get {
                complete(getUserRolesForTarget(authProfile, NamespaceRoleTarget(namespace)))
              } ~ post {
                entity(as[Map[String, String]]) { userRoles =>
                  complete(updateUserRolesForTarget(authProfile, NamespaceRoleTarget(namespace), userRoles))
                }
              }
            } ~ path(Segment) { username =>
              complete(deleteUserRoleForTarget(authProfile, NamespaceRoleTarget(namespace), username))
            }
          }
        },
        path("domain" / Segment / Segment) { (namespace, domain) =>
          authorize(canManageDomainUsers(namespace, domain, authProfile)) {
            pathEnd {
              get {
                complete(getUserRolesForTarget(authProfile, DomainRoleTarget(DomainId(namespace, domain))))
              } ~ post {
                entity(as[Map[String, String]]) { userRoles =>
                  complete(updateUserRolesForTarget(authProfile, DomainRoleTarget(DomainId(namespace, domain)), userRoles))
                }
              }
            } ~ path(Segment) { username =>
              complete(deleteUserRoleForTarget(authProfile, DomainRoleTarget(DomainId(namespace, domain)), username))
            }
          }
        })
    }
  }

  private[this] def getUserRolesForTarget(authProfile: AuthorizationProfile, target: RoleTarget): Future[RestResponse] = {
    roleActor.ask[GetAllUserRolesResponse](GetAllUserRolesRequest(target, _)).flatMap {
      case GetAllUserRolesSuccess(userRoles) =>
        val response = userRoles.filter(_.roles.nonEmpty)
          .map(userRole => (userRole.username, userRole.roles.head.role.name))
        Future.successful(okResponse(response))
      case RequestFailure(cause) =>
        Future.failed(cause)
    }
  }

  private[this] def updateUserRolesForTarget(authProfile: AuthorizationProfile, target: RoleTarget, userRoles: Map[String, String]): Future[RestResponse] = {
    val mappedRoles = userRoles.map(entry => entry._1 -> Set(entry._2))
    roleActor.ask[UpdateRolesForTargetResponse](UpdateRolesForTargetRequest(target, mappedRoles, _)).flatMap {
      case RequestSuccess() =>
        Future.successful(OkResponse)
      case RequestFailure(cause) =>
        Future.failed(cause)
    }
  }

  private[this] def deleteUserRoleForTarget(authProfile: AuthorizationProfile, target: RoleTarget, username: String): Future[RestResponse] = {
    roleActor.ask[RemoveUserFromResponse](RemoveUserFromRequest(target, username, _)).flatMap {
      case RequestSuccess() =>
        Future.successful(OkResponse)
      case RequestFailure(cause) =>
        Future.failed(cause)
    }
  }

  private[this] def canManageUsers(authProfile: AuthorizationProfile): Boolean = {
    authProfile.hasGlobalPermission(Permissions.Global.ManageUsers)
  }

  private[this] def canManageNamespaceUsers(namespace: String, authProfile: AuthorizationProfile): Boolean = {
    authProfile.hasGlobalPermission(Permissions.Global.ManageDomains) ||
      authProfile.hasNamespacePermission(Permissions.Namespace.ManageUsers, namespace)
  }

  private[this] def canManageDomainUsers(namespace: String, domain: String, authProfile: AuthorizationProfile): Boolean = {
    authProfile.hasGlobalPermission(Permissions.Global.ManageDomains) ||
      authProfile.hasNamespacePermission(Permissions.Namespace.ManageUsers, namespace) ||
      authProfile.hasDomainPermission(Permissions.Domain.ManageUsers, namespace, domain)
  }
}


private[rest] object UserRoleService {

  case class CreateNamespacePost(id: String, displayName: String)

  case class UpdateNamespacePut(displayName: String)

  case class NamespaceRestData(id: String, displayName: String)

  case class NamespaceAndDomainsRestData(id: String, displayName: String, domains: Set[DomainRestData])

  case class UserRoleData(username: String, role: String)

}
