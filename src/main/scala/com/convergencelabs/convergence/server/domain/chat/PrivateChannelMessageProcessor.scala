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

package com.convergencelabs.convergence.server.domain.chat


import akka.actor.typed.scaladsl.ActorContext
import com.convergencelabs.convergence.server.domain.DomainUserId
import com.convergencelabs.convergence.server.domain.chat.ChatActor._

import scala.util.{Failure, Try}

/**
 * Processes messages for a Private Chats. The main things this cl
 *
 * @param stateManager The state manager that controls the persistence of chat state.
 * @param context      The actor context that the ChatActors are deployed into.
 */
private[chat] class PrivateChannelMessageProcessor(stateManager: ChatStateManager,
                                                   context: ActorContext[_])
  extends MembershipChatMessageProcessor(stateManager, context) {

  override def processChatMessage(message: RequestMessage): Try[ChatMessageProcessingResult[_]] = {
    message match {
      case message: AddUserToChatRequest =>
        notFoundOrDeliver(message, message.requester.userId)
      case message: RemoveUserFromChatRequest =>
        notFoundOrDeliver(message, message.requester.userId)
      case _: JoinChatRequest =>
        Failure(InvalidChatMessageException("Can not join a private channel"))
      case message: Message =>
        super.processChatMessage(message)
    }
  }

  /**
   * A helper method that will return a not found error if the requester
   * is not part of the private channel.
   *
   * @param message The message to process.
   * @param userId  The user id of the user requesting the action.
   * @return the processing result.
   */
  private[this] def notFoundOrDeliver(message: RequestMessage, userId: DomainUserId): Try[ChatMessageProcessingResult[_]] = {
    val state = stateManager.state()
    if (state.members.contains(userId)) {
      super.processChatMessage(message)
    } else {
      Failure(ChatNotFoundException(state.id))
    }
  }
}
