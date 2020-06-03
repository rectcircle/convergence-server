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

package com.convergencelabs.convergence.server.api.rest.domain


import akka.actor.typed.{ActorRef, ActorSystem}
import akka.http.scaladsl.marshalling.ToResponseMarshallable.apply
import akka.http.scaladsl.server.Directive.addByNameNullaryApply
import akka.http.scaladsl.server.Directives.{_segmentStringToPathMatcher, complete, get, path}
import akka.http.scaladsl.server.Route
import akka.actor.typed.scaladsl.AskPattern._
import akka.util.Timeout
import com.convergencelabs.convergence.server.api.rest.{RestResponse, okResponse}
import com.convergencelabs.convergence.server.datastore.domain.DomainStatsActor._
import com.convergencelabs.convergence.server.domain.DomainId
import com.convergencelabs.convergence.server.domain.rest.DomainRestActor
import com.convergencelabs.convergence.server.domain.rest.DomainRestActor.DomainRestMessage
import com.convergencelabs.convergence.server.security.AuthorizationProfile

import scala.concurrent.{ExecutionContext, Future}

class DomainStatsService(private[this] val domainRestActor: ActorRef[DomainRestActor.Message],
                         private[this] val system: ActorSystem[_],
                         private[this] val executionContext: ExecutionContext,
                         private[this] val timeout: Timeout)
  extends AbstractDomainRestService(system, executionContext, timeout) {

  import DomainStatsService._

  def route(authProfile: AuthorizationProfile, domain: DomainId): Route =
    (path("stats") & get) {
      complete(getStats(domain))
    }

  private[this] def getStats(domain: DomainId): Future[RestResponse] = {
    domainRestActor.ask[GetStatsResponse](r => DomainRestMessage(domain,  GetStatsRequest(r))).flatMap {
      case GetStatsSuccess(stats) =>
        val DomainStats(activeSessionCount, userCount, modelCount, dbSize) = stats
        val response = DomainStatsRestData(activeSessionCount, userCount, modelCount, dbSize)
        Future.successful(okResponse(response))
      case RequestFailure(cause) =>
        Future.failed(cause)
    }
  }
}

object DomainStatsService {

  case class DomainStatsRestData(activeSessionCount: Long, userCount: Long, modelCount: Long, dbSize: Long)

}
