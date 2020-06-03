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

package com.convergencelabs.convergence.server.api.realtime

import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.{AbstractBehavior, ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.util.Timeout
import com.convergencelabs.convergence.proto._
import com.convergencelabs.convergence.proto.model._
import com.convergencelabs.convergence.server.actor.{AskUtils, CborSerializable}
import com.convergencelabs.convergence.server.datastore.domain.{ModelOperationStoreActor, ModelStoreActor}
import com.convergencelabs.convergence.server.domain.model.RealtimeModelActor
import com.convergencelabs.convergence.server.domain.{DomainId, DomainUserSessionId}
import grizzled.slf4j.Logging
import scalapb.GeneratedMessage

import scala.concurrent.ExecutionContextExecutor
import scala.language.postfixOps

class HistoricModelClientActor private(context: ActorContext[HistoricModelClientActor.Message],
                                       domain: DomainId,
                                       session: DomainUserSessionId,
                                       clientActor: ActorRef[ClientActor.SendServerMessage],
                                       modelStoreActor: ActorRef[ModelStoreActor.Message],
                                       operationStoreActor: ActorRef[ModelOperationStoreActor.Message],
                                       modelShardRegion: ActorRef[RealtimeModelActor.Message],
                                       defaultTimeout: Timeout)
  extends AbstractBehavior[HistoricModelClientActor.Message](context) with Logging with AskUtils {

  import HistoricModelClientActor._

  private[this] implicit val timeout: Timeout = defaultTimeout
  private[this] implicit val ec: ExecutionContextExecutor = context.executionContext
  private[this] implicit val system: ActorSystem[_] = context.system

  override def onMessage(msg: HistoricModelClientActor.Message): Behavior[HistoricModelClientActor.Message] = {
    msg match {
      case IncomingProtocolRequest(message, replyCallback) =>
        onRequestReceived(message, replyCallback)
    }
    Behaviors.same
  }

  private[this] def onRequestReceived(message: IncomingRequest, replyCallback: ReplyCallback): Unit = {
    message match {
      case dataRequest: HistoricalDataRequestMessage =>
        onDataRequest(dataRequest, replyCallback)
      case operationRequest: HistoricalOperationRequestMessage =>
        onOperationRequest(operationRequest, replyCallback)
    }
  }

  private[this] def onDataRequest(request: HistoricalDataRequestMessage, cb: ReplyCallback): Unit = {
    modelShardRegion.ask[RealtimeModelActor.GetRealtimeModelResponse](RealtimeModelActor.GetRealtimeModelRequest(domain, request.modelId, None, _))
      .map(_.model.fold(
        {
          case RealtimeModelActor.ModelNotFoundError() =>
            cb.expectedError(ErrorCodes.ModelNotFound, s"A model with id '${request.modelId}' does not exist.")
          case RealtimeModelActor.UnauthorizedError(message) =>
            cb.expectedError(ErrorCodes.Unauthorized, message)
          case RealtimeModelActor.UnknownError() =>
            cb.unexpectedError("Unexpected error getting historical model data.")
        },
        { model =>
          val response = HistoricalDataResponseMessage(
            model.metaData.collection,
            Some(ImplicitMessageConversions.objectValueToMessage(model.data)),
            model.metaData.version,
            Some(ImplicitMessageConversions.instanceToTimestamp(model.metaData.createdTime)),
            Some(ImplicitMessageConversions.instanceToTimestamp(model.metaData.modifiedTime))
          )
          cb.reply(response)
        }))
      .recoverWith(handleAskFailure(_, cb))
  }

  private[this] def onOperationRequest(request: HistoricalOperationRequestMessage, cb: ReplyCallback): Unit = {
    val HistoricalOperationRequestMessage(modelId, first, last, _) = request
    operationStoreActor.ask[ModelOperationStoreActor.GetOperationsResponse](ModelOperationStoreActor.GetOperationsRequest(request.modelId, first, last, _))
      .map(_.operations.fold(
        {
          case ModelOperationStoreActor.ModelNotFoundError() =>
            cb.expectedError(ErrorCodes.ModelNotFound, s"A model with id '$modelId' does not exist.")
          case ModelOperationStoreActor.UnknownError() =>
            cb.unexpectedError("Unexpected error getting historical model operations.")
        },
        { operations =>
          val response = HistoricalOperationsResponseMessage(operations map ModelOperationMapper.mapOutgoing)
          cb.reply(response)
        }))
      .recoverWith(handleAskFailure(_, cb))
  }
}

object HistoricModelClientActor {
  def apply(domain: DomainId,
            session: DomainUserSessionId,
            clientActor: ActorRef[ClientActor.SendServerMessage],
            modelStoreActor: ActorRef[ModelStoreActor.Message],
            operationStoreActor: ActorRef[ModelOperationStoreActor.Message],
            modelShardRegion: ActorRef[RealtimeModelActor.Message],
            defaultTimeout: Timeout): Behavior[Message] =
    Behaviors.setup(context => new HistoricModelClientActor(
      context, domain, session, clientActor, modelStoreActor, operationStoreActor, modelShardRegion, defaultTimeout))

  /////////////////////////////////////////////////////////////////////////////
  // Message Protocol
  /////////////////////////////////////////////////////////////////////////////

  sealed trait Message extends CborSerializable

  sealed trait IncomingMessage extends Message

  type IncomingRequest = GeneratedMessage with RequestMessage with HistoricalModelMessage with ClientMessage

  case class IncomingProtocolRequest(message: IncomingRequest, replyCallback: ReplyCallback) extends IncomingMessage

}
