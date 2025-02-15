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

package com.convergencelabs.convergence.server.backend.services.domain.activity

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import com.convergencelabs.convergence.common.Ok
import com.convergencelabs.convergence.server.api.realtime.ActivityClientActor._
import com.convergencelabs.convergence.server.api.realtime.ErrorCodes
import com.convergencelabs.convergence.server.backend.datastore.domain.permissions.ActivityPermissionTarget
import com.convergencelabs.convergence.server.backend.datastore.{DuplicateValueException, EntityNotFoundException}
import com.convergencelabs.convergence.server.backend.services.domain.activity.ActivityActor.Message
import com.convergencelabs.convergence.server.backend.services.domain.permissions._
import com.convergencelabs.convergence.server.backend.services.domain.{BaseDomainShardedActor, DomainPersistenceManager}
import com.convergencelabs.convergence.server.model.DomainId
import com.convergencelabs.convergence.server.model.domain.activity.{Activity, ActivityId}
import com.convergencelabs.convergence.server.model.domain.session.DomainSessionAndUserId
import com.convergencelabs.convergence.server.model.domain.user.DomainUserId
import com.convergencelabs.convergence.server.util.serialization.akka.CborSerializable
import com.fasterxml.jackson.annotation.{JsonSubTypes, JsonTypeInfo}
import org.json4s.JsonAST.JValue

import java.time.Instant
import scala.concurrent.duration.FiniteDuration
import scala.util.{Success, Try}

/**
 * The [[ActivityActor]] represents a single activity in the system. Activities
 * are generally used for sharing collaborative cues and presence in sub
 * sections of a collaborative application.
 *
 * @param context     The ActorContext this actor is created in.
 * @param shardRegion The shard region ActivityActors are created in.
 * @param shard       The specific shard this actor resides in.
 */
final class ActivityActor(domainId: DomainId,
                          activityId: ActivityId,
                          context: ActorContext[Message],
                          shardRegion: ActorRef[Message],
                          shard: ActorRef[ClusterSharding.ShardCommand],
                          domainPersistenceManager: DomainPersistenceManager,
                          receiveTimeout: FiniteDuration)
  extends BaseDomainShardedActor[Message](
    domainId,
    context,
    shardRegion,
    shard,
    domainPersistenceManager,
    receiveTimeout,
    entityDescription = Some(s"${activityId.activityType}/${activityId.id}")) {

  import ActivityActor._

  private[this] val stateMap = new ActivityStateMap()

  private[this] var joinedClients = Map[ActorRef[OutgoingMessage], String]()
  private[this] var joinedSessions = Map[String, ActorRef[OutgoingMessage]]()
  private[this] var joinedUsers = Map[DomainUserId, Set[String]]()
  private[this] var sessionToUser = Map[String, DomainUserId]()

  private[this] var ephemeral: Boolean = false
  private[this] var created: Instant = Instant.now()
  private[this] var permissionsCache: PermissionsCache = _

  override protected def initializeState(msg: Message): Try[Unit] = {
    // TODO set ephemeral and created here perhaps.
    this.permissionsCache = new PermissionsCache(
      ActivityPermissionTarget(activityId),
      this.persistenceProvider.permissionsStore,
      ActivityPermissions.AllActivityPermissions
    )

    Success(())
  }

  override def receiveInitialized(msg: Message): Behavior[Message] = {
    msg match {
      case msg: GetParticipantsRequest =>
        onGetParticipantsRequest(msg)
      case msg: CreateRequest =>
        onCreateRequest(msg)
      case msg: DeleteRequest =>
        onDeleteRequest(msg)
      case msg: JoinRequest =>
        onJoinRequest(msg)
      case msg: LeaveRequest =>
        onLeave(msg)
      case msg: UpdateState =>
        onUpdateState(msg)
      case msg: AddPermissionsRequest =>
        onAddPermissions(msg)
      case msg: RemovePermissionsRequest =>
        onRemovePermissions(msg)
      case msg: SetPermissionsRequest =>
        onSetPermissions(msg)
      case msg: GetPermissionsRequest =>
        onGetPermissions(msg)
      case msg: ResolvePermissionsRequest =>
        onResolvePermissions(msg)
      case _: ReceiveTimeout =>
        passivate()
    }
  }

  override protected def onTerminated(actor: ActorRef[Nothing]): Behavior[Message] = {
    handleClientDeath(actor.asInstanceOf[ActorRef[OutgoingMessage]])
  }

  private[this] def onCreateRequest(msg: CreateRequest): Behavior[Message] = {
    val CreateRequest(_, _, _, permissions, replyTo) = msg
    val activity = Activity(activityId, ephemeral = false, Instant.now())
    persistenceProvider.activityStore
      .createActivity(activity)
      .map(_ => Right(Ok()))
      .recover {
        case _: DuplicateValueException =>
          Left(AlreadyExists())
        case t: Throwable =>
          error("unexpected error creating activity", t)
          Left(UnknownError())
      }
      .foreach(replyTo ! CreateResponse(_))

    Behaviors.same
  }


  private[this] def onDeleteRequest(msg: DeleteRequest): Behavior[Message] = {
    val DeleteRequest(_, _, requester, replyTo) = msg
    this.permissionsCache.hasPermission(requester.map(_.userId), ActivityPermissions.Remove).map {
      case true =>
        this.removeAllJoinedSessionsBeforeDelete()

        (for {
          _ <- persistenceProvider.permissionsStore
            .removeAllPermissionsForTarget(ActivityPermissionTarget(activityId))
          _ <- persistenceProvider.activityStore
            .deleteActivity(activityId)
        } yield Right(Ok()))
          .recover {
            case _: EntityNotFoundException =>
              Left(NotFoundError())
            case t: Throwable =>
              error("unexpected error deleting activity", t)
              Left(UnknownError())
          }
          .foreach(replyTo ! DeleteResponse(_))

      case false =>
        replyTo ! DeleteResponse(Left(UnauthorizedError(Some("The user does not have permissions to delete the specified activity"))))
    }

    Behaviors.same
  }

  private[this] def removeAllJoinedSessionsBeforeDelete(): Unit = {
    this.joinedSessions.foreach { case (sessionId, client) =>
      client ! ActivityDeleted(activityId)
      this.handleSessionLeft(sessionId, onDelete = true)
    }
  }

  private[this] def onJoinRequest(msg: JoinRequest): Behavior[Message] = {
    if (joinedSessions.isEmpty) {
      this.persistenceProvider.activityStore
        .findActivity(activityId)
        .map {
          case Some(activity) =>
            // This is the first joiner
            this.ephemeral = activity.ephemeral
            this.created = activity.created
            processJoinForExistingActivity(msg)
          case None =>
            processJoinForNewActivity(msg)
        }
        .recover {
          case t: Throwable =>
            error("Unexpected error joining an activity", t)
            msg.replyTo ! JoinResponse(Left(UnknownError()))
        }
    } else {
      // If there are other joined sessions that we can skip the existence
      // check since the activity clearly exists.
      processJoinForExistingActivity(msg)
    }


    Behaviors.same
  }

  private[this] def processJoinForAuthorizedUser(msg: JoinRequest): Unit = {
    val JoinRequest(_, _, session, lurk, state, _, client, replyTo) = msg

    (for {
      canView <- permissionsCache.hasPermission(msg.session.userId, ActivityPermissions.ViewState)
      canSet <- permissionsCache.hasPermission(msg.session.userId, ActivityPermissions.SetState)
    } yield {
      val sessionId = session.sessionId
      this.joinedSessions += (sessionId -> client)
      this.joinedClients += (client -> sessionId)
      this.sessionToUser += sessionId -> session.userId

      val userSessions = this.joinedUsers.getOrElse(session.userId, Set())
      this.joinedUsers += session.userId -> (userSessions + sessionId)

      disableReceiveTimeout()
      context.watch(client)

      if (!lurk) {
        this.stateMap.join(sessionId)

        if (canSet) {
          state.foreach {
            case (k, v) =>
              this.stateMap.setState(sessionId, k, v)
          }
        }

        val message = ActivitySessionJoined(activityId, sessionId, state)
        joinedSessions.values filter (_ != client) foreach (_ ! message)
      }

      val activityState = if (canView) {
        stateMap.getState
      } else {
        Map[String, Map[String, JValue]]()
      }
      val joined = Joined(this.ephemeral, this.created, activityState)

      replyTo ! JoinResponse(Right(joined))
    })
      .recover {
        case t: Throwable =>
          error("Unexpected error checking permissions during join", t)
          replyTo ! JoinResponse(Left(UnknownError()))
      }
  }

  private[this] def processJoinForExistingActivity(msg: JoinRequest): Unit = {
    this.joinedSessions.get(msg.session.sessionId) match {
      case Some(_) =>
        msg.replyTo ! JoinResponse(Left(AlreadyJoined()))

      case None =>
        (for {
          canJoin <- permissionsCache.hasPermission(msg.session.userId, ActivityPermissions.Join)
          canLurk <- permissionsCache.hasPermission(msg.session.userId, ActivityPermissions.Lurk)
        } yield {
          if (canJoin) {
            if (msg.lurk) {
              if (canLurk) {
                processJoinForAuthorizedUser(msg)
              } else {
                msg.replyTo ! JoinResponse(Left(UnauthorizedError(Some("The user does not have permissions to lurk in the requested activity"))))
              }
            } else {
              processJoinForAuthorizedUser(msg)
            }
          } else {
            msg.replyTo ! JoinResponse(Left(UnauthorizedError(Some("The user does not have permissions to join the requested activity"))))
          }
        }).recover {
          case t: Throwable =>
            val message = "Unexpected error checking permissions when joining an activity"
            error(message, t)
            msg.replyTo ! JoinResponse(Left(UnknownError()))
        }
    }
  }


  private[this] def processJoinForNewActivity(msg: JoinRequest): Unit = {
    msg.autoCreateData match {
      case Some(data) =>
        val activity = Activity(activityId, data.ephemeral, Instant.now())
        this.ephemeral = data.ephemeral

        // If no world permissions were set we set them to the default.
        val worldPermissions = msg.autoCreateData.map(_.worldPermissions)
          .orElse(Some(ActivityPermissions.DefaultWorldPermissions))

        val userPermissions = msg.autoCreateData.map { p =>
          // We need to ensure the user that is auto creating has all permissions if it is not
          // and admin user.
          if (!msg.session.userId.isConvergence) {
            var creatorPermissions = p.userPermissions.getOrElse(msg.session.userId, ActivityPermissions.AllActivityPermissions)
            creatorPermissions += ActivityPermissions.Join

            if (msg.lurk) {
              creatorPermissions += ActivityPermissions.Lurk
            }

            p.userPermissions + (msg.session.userId -> creatorPermissions)
          } else {
            p.userPermissions
          }
        }

        val groupPermissions = msg.autoCreateData.map(_.groupPermission)

        val target = ActivityPermissionTarget(activityId)

        (for {
          _ <- persistenceProvider.activityStore.createActivity(activity)
          _ <- persistenceProvider.permissionsStore.setPermissionsForTarget(
            target, userPermissions, replaceUsers = true, groupPermissions, replaceGroups = true, worldPermissions)
        } yield ())
          .map { _ =>
            this.processJoinForExistingActivity(msg)
          }
          .recover {
            case t: Throwable =>
              error("Unexpected error auto creating activity during a join", t)
              msg.replyTo ! JoinResponse(Left(UnknownError()))
          }
      case None =>
        msg.replyTo ! JoinResponse(Left(NotFoundError()))
    }
  }

  private[this] def onGetParticipantsRequest(msg: GetParticipantsRequest): Behavior[Message] = {
    val GetParticipantsRequest(_, _, replyTo) = msg
    replyTo ! GetParticipantsResponse(stateMap.getState)
    Behaviors.same
  }

  private[this] def isSessionJoined(sessionId: String): Boolean = {
    this.joinedSessions.contains(sessionId)
  }

  private[this] def onUpdateState(msg: UpdateState): Behavior[Message] = {
    val UpdateState(_, _, sessionId, setState, complete, removed) = msg

    if (isSessionJoined(sessionId)) {
      val userId = this.sessionToUser(sessionId)
      val setter = this.joinedSessions(sessionId)

      this.permissionsCache.hasPermission(userId, ActivityPermissions.SetState).map {
        case true =>
          if (complete) {
            stateMap.clear(sessionId)
          }

          removed.foreach(key => stateMap.removeState(sessionId, key))

          setState.foreach {
            case (key: String, value: Any) =>
              stateMap.setState(sessionId, key, value)
          }

          val message = ActivityStateUpdated(activityId, sessionId, setState, complete, removed)

          joinedSessions.filter(_._2 != setter) foreach { case (toSessionId, client) =>
            val user = sessionToUser(toSessionId)
            permissionsCache.hasPermission(user, ActivityPermissions.ViewState).map { ok =>
              if (ok) client ! message
            }.recover(e => error("Unexpected error getting permissions when sending state update", e))
          }

        case false =>
          setter ! ActivityErrorMessage(activityId, ErrorCodes.Unauthorized.toString, "The user does not have permissions to set state the specified activity")
      }.recover {
        case t: Throwable =>
          error("Unexpected error checking permissions when updating activity state", t)
          setter ! ActivityErrorMessage(activityId, ErrorCodes.Unauthorized.toString, "There was an unexpected error setting activity state")
      }
    } else {
      warn(s"Activity(${this.identityString}): Received a state update for a session($sessionId) that is not joined to the activity.")
    }

    Behaviors.same
  }

  private[this] def onAddPermissions(msg: AddPermissionsRequest): Behavior[Message] = {
    val AddPermissionsRequest(_, _, requester, permissions, replyTo) = msg
    (for {
      authorized <- permissionsCache.hasPermission(requester.map(_.userId), ActivityPermissions.Manage)
      result <- if (!authorized) {
        Success(Left(UnauthorizedError(Some("The user does not have permissions to add permissions to this activity"))))
      } else {
        val AddPermissions(world, user, group) = permissions

        val target = ActivityPermissionTarget(activityId)
        this.persistenceProvider.permissionsStore.addPermissionsForTarget(
          target,
          user,
          group,
          world
        ).map { _ =>
          clearPermissionsAndUpdateState()
          Right(Ok())
        }
      }
    } yield result)
      .recover {
        case _: EntityNotFoundException =>
          Left(NotFoundError())
        case t: Throwable =>
          error("Unexpected error adding activity permissions", t)
          Left(UnknownError())
      }
      .foreach(replyTo ! AddPermissionsResponse(_))

    Behaviors.same
  }

  private[this] def onRemovePermissions(msg: RemovePermissionsRequest): Behavior[Message] = {
    val RemovePermissionsRequest(_, _, requester, permissions, replyTo) = msg
    (for {
      authorized <- permissionsCache.hasPermission(requester.map(_.userId), ActivityPermissions.Manage)
      result <- if (!authorized) {
        Success(Left(UnauthorizedError(Some("The user does not have permissions to remove permissions from this activity"))))
      } else {
        val RemovePermissions(world, user, group) = permissions
        val target = ActivityPermissionTarget(activityId)
        this.persistenceProvider.permissionsStore.removePermissionsForTarget(
          target,
          user,
          group,
          world
        ).map { _ =>
          clearPermissionsAndUpdateState()
          Right(Ok())
        }
      }
    } yield result)
      .recover {
        case _: EntityNotFoundException =>
          Left(NotFoundError())
        case t: Throwable =>
          error("Unexpected error removing activity permissions", t)
          Left(UnknownError())
      }
      .foreach(replyTo ! RemovePermissionsResponse(_))

    Behaviors.same
  }

  private[this] def onSetPermissions(msg: SetPermissionsRequest): Behavior[Message] = {
    val SetPermissionsRequest(_, _, requester, permissions, replyTo) = msg
    (for {
      authorized <- permissionsCache.hasPermission(requester.map(_.userId), ActivityPermissions.Manage)
      result <- if (!authorized) {
        Success(Left(UnauthorizedError(Some("The user does not have permissions to set permissions for this activity"))))
      } else {
        val SetPermissions(world, user, group) = permissions
        val target = ActivityPermissionTarget(activityId)
        this.persistenceProvider.permissionsStore.setPermissionsForTarget(
          target,
          user.map(_.permissions),
          user.exists(_.replace),
          group.map(_.permissions),
          user.exists(_.replace),
          world
        ).map { _ =>
          clearPermissionsAndUpdateState()
          Right(Ok())
        }
      }
    } yield result)
      .recover {
        case _: EntityNotFoundException =>
          Left(NotFoundError())
        case t: Throwable =>
          error("Unexpected error setting activity permissions", t)
          Left(UnknownError())
      }
      .foreach(replyTo ! SetPermissionsResponse(_))

    Behaviors.same
  }

  private[this] def onGetPermissions(msg: GetPermissionsRequest): Behavior[Message] = {
    val GetPermissionsRequest(_, _, requester, replyTo) = msg
    (for {
      authorized <- permissionsCache.hasPermission(requester.map(_.userId), ActivityPermissions.Manage)
      result <- if (!authorized) {
        Success(Left(UnauthorizedError(Some("The user does not have permissions to get permissions from this activity"))))
      } else {
        val target = ActivityPermissionTarget(activityId)
        this.persistenceProvider.permissionsStore.getPermissionsForTarget(target)
          .map(permissions => Right(permissions))
          .recover {
            case _: EntityNotFoundException =>
              Left(NotFoundError())
            case t: Throwable =>
              error("Unexpected error getting activity permissions", t)
              Left(UnknownError())
          }
      }
    } yield result)
      .foreach(replyTo ! GetPermissionsResponse(_))

    Behaviors.same
  }

  private[this] def clearPermissionsAndUpdateState(): Unit = {
    this.permissionsCache.invalidate()
    debug(this.joinedUsers)
    this.joinedUsers.map { case (user, sessions) =>
      permissionsCache.getPermissionsForUser(user).map { permissions =>
        if (!permissions.contains(ActivityPermissions.Join)) {
          val message = ActivityForceLeave(activityId, "Join permissions for the activity were revoked")
          sessions.foreach { s =>
            joinedSessions(s) ! message
          }
        } else if (!permissions.contains(ActivityPermissions.Lurk)) {
          sessions.foreach(s => {
            if (!stateMap.hasSession(s)) {
              val message = ActivityForceLeave(activityId, "Lurk permissions for the activity were revoked")
              joinedSessions(s) ! message
            }
          })
        } else if (!permissions.contains(ActivityPermissions.ViewState)) {
          val message = ActivityForceLeave(activityId, "Permissions to view activity state have been revoked, you must rejoin the activity.")
          sessions.foreach(s => joinedSessions(s) ! message)
        }
      }
    }
  }

  private[this] def onResolvePermissions(msg: ResolvePermissionsRequest): Behavior[Message] = {
    this.permissionsCache.getPermissionsForUser(msg.session.userId)
      .map(permissions => Right(permissions))
      .recover {
        case _: EntityNotFoundException =>
          Left(NotFoundError())
        case t: Throwable =>
          error("Unexpected error resolving activity permissions", t)
          Left(UnknownError())
      }
      .foreach(msg.replyTo ! ResolvePermissionsResponse(_))

    Behaviors.same
  }

  private[this] def onLeave(msg: LeaveRequest): Behavior[Message] = {
    val LeaveRequest(_, _, sessionId, replyTo) = msg
    if (!isSessionJoined(sessionId)) {
      replyTo ! LeaveResponse(Left(NotJoinedError()))
      Behaviors.same
    } else {
      replyTo ! LeaveResponse(Right(Ok()))
      handleSessionLeft(sessionId, onDelete = false)
    }
  }

  private[this] def handleSessionLeft(sessionId: String, onDelete: Boolean): Behavior[Message] = {
    val leaver = this.joinedSessions(sessionId)
    if (this.stateMap.hasSession(sessionId)) {
      this.stateMap.leave(sessionId)
      if (!onDelete) {
        val message = ActivitySessionLeft(activityId, sessionId)
        joinedSessions.values filter (_ != leaver) foreach (_ ! message)
      }
    }

    this.joinedSessions -= sessionId
    this.joinedClients -= leaver

    val userId = this.sessionToUser(sessionId)

    val userSessions = this.joinedUsers(userId)
    val newUserSessions = userSessions.filter(_ != sessionId)
    if (newUserSessions.isEmpty) {
      this.permissionsCache.invalidateUser(userId)
      this.joinedUsers -= userId
    } else {
      this.joinedUsers += userId -> newUserSessions
    }

    this.sessionToUser -= sessionId

    this.context.unwatch(leaver)

    if (this.joinedSessions.isEmpty && !onDelete) {
      enableReceiveTimeout()
      if (this.ephemeral) {
        this.persistenceProvider.activityStore
          .deleteActivity(activityId)
          .recover(cause => error("Could not delete ephemeral activity", cause))
      }
    }

    Behaviors.same
  }

  private[this] def handleClientDeath(actor: ActorRef[OutgoingMessage]): Behavior[Message] = {
    this.joinedClients.get(actor) match {
      case Some(sessionId) =>
        debug(s"$identityString: Client with session $sessionId was terminated.  Leaving activity.")
        this.handleSessionLeft(sessionId, onDelete = false)
      case None =>
        warn(s"$identityString: Deathwatch on a client was triggered for an actor that did not have thi activity open")
        Behaviors.same
    }
  }

  override protected def getDomainId(msg: Message): DomainId = msg.domainId

  override protected def getReceiveTimeoutMessage(): Message = ReceiveTimeout(domainId, activityId)
}

object ActivityActor {
  def apply(domainId: DomainId,
            activityId: ActivityId,
            shardRegion: ActorRef[Message],
            shard: ActorRef[ClusterSharding.ShardCommand],
            domainPersistenceManager: DomainPersistenceManager,
            receiveTimeout: FiniteDuration): Behavior[Message] = Behaviors.setup(context =>
    new ActivityActor(
      domainId,
      activityId,
      context,
      shardRegion,
      shard,
      domainPersistenceManager,
      receiveTimeout
    ))

  /////////////////////////////////////////////////////////////////////////////
  // Message Protocol
  /////////////////////////////////////////////////////////////////////////////

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
  @JsonSubTypes(Array(
    new JsonSubTypes.Type(value = classOf[CreateRequest], name = "create"),
    new JsonSubTypes.Type(value = classOf[DeleteRequest], name = "delete"),
    new JsonSubTypes.Type(value = classOf[JoinRequest], name = "join"),
    new JsonSubTypes.Type(value = classOf[LeaveRequest], name = "leave"),
    new JsonSubTypes.Type(value = classOf[UpdateState], name = "update_state"),
    new JsonSubTypes.Type(value = classOf[GetParticipantsRequest], name = "get_participants")
  ))
  sealed trait Message extends CborSerializable {
    val domainId: DomainId
    val activityId: ActivityId
  }

  private case class ReceiveTimeout(domainId: DomainId, activityId: ActivityId) extends Message

  //
  // Create
  //

  final case class CreateRequest(domainId: DomainId,
                                 activityId: ActivityId,
                                 session: Option[DomainSessionAndUserId],
                                 permissions: AllPermissions,
                                 replyTo: ActorRef[CreateResponse]) extends Message

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
  @JsonSubTypes(Array(
    new JsonSubTypes.Type(value = classOf[AlreadyExists], name = "already_exists"),
    new JsonSubTypes.Type(value = classOf[UnauthorizedError], name = "unauthorized")
  ))
  sealed trait CreateError

  final case class AlreadyExists() extends CreateError

  final case class CreateResponse(response: Either[CreateError, Ok]) extends CborSerializable

  //
  // Delete
  //

  final case class DeleteRequest(domainId: DomainId,
                                 activityId: ActivityId,
                                 requester: Option[DomainSessionAndUserId],
                                 replyTo: ActorRef[DeleteResponse]) extends Message

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
  @JsonSubTypes(Array(
    new JsonSubTypes.Type(value = classOf[NotFoundError], name = "not_found"),
    new JsonSubTypes.Type(value = classOf[UnauthorizedError], name = "unauthorized")
  ))
  sealed trait DeleteError

  final case class DeleteResponse(response: Either[DeleteError, Ok]) extends CborSerializable


  //
  // Join
  //

  final case class JoinRequest(domainId: DomainId,
                               activityId: ActivityId,
                               session: DomainSessionAndUserId,
                               lurk: Boolean,
                               state: Map[String, JValue],
                               autoCreateData: Option[ActivityAutoCreationOptions],
                               client: ActorRef[OutgoingMessage],
                               replyTo: ActorRef[JoinResponse]) extends Message

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
  @JsonSubTypes(Array(
    new JsonSubTypes.Type(value = classOf[AlreadyJoined], name = "already_joined"),
    new JsonSubTypes.Type(value = classOf[UnauthorizedError], name = "unauthorized"),
    new JsonSubTypes.Type(value = classOf[NotFoundError], name = "not_found")
  ))
  sealed trait JoinError

  final case class AlreadyJoined() extends JoinError

  final case class Joined(ephemeral: Boolean,
                          created: Instant,
                          state: Map[String, Map[String, JValue]])

  final case class JoinResponse(response: Either[JoinError, Joined]) extends CborSerializable


  //
  // Leave
  //
  final case class LeaveRequest(domainId: DomainId,
                                activityId: ActivityId,
                                sessionId: String,
                                replyTo: ActorRef[LeaveResponse]) extends Message

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
  @JsonSubTypes(Array(
    new JsonSubTypes.Type(value = classOf[NotJoinedError], name = "not_joined")
  ))
  sealed trait LeaveError

  final case class LeaveResponse(response: Either[LeaveError, Ok]) extends CborSerializable


  //
  // Update State
  //
  final case class UpdateState(domainId: DomainId,
                               activityId: ActivityId,
                               sessionId: String,
                               state: Map[String, JValue],
                               complete: Boolean,
                               removed: List[String]) extends Message

  //
  // GetParticipants
  //
  final case class GetParticipantsRequest(domainId: DomainId,
                                          activityId: ActivityId,
                                          replyTo: ActorRef[GetParticipantsResponse]) extends Message

  final case class GetParticipantsResponse(state: Map[String, Map[String, JValue]]) extends CborSerializable


  /////////////////////////////////////////////////////////////////////////////
  // Activity Permissions Messages
  /////////////////////////////////////////////////////////////////////////////

  sealed trait ActivityPermissionsRequest[R] extends Message {
    val replyTo: ActorRef[R]
    val requester: Option[DomainSessionAndUserId]
  }

  //
  // AddPermissions
  //
  case class AddPermissionsRequest(domainId: DomainId,
                                   activityId: ActivityId,
                                   requester: Option[DomainSessionAndUserId],
                                   permissions: AddPermissions,
                                   replyTo: ActorRef[AddPermissionsResponse]
                                  ) extends ActivityPermissionsRequest[AddPermissionsResponse]

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
  @JsonSubTypes(Array(
    new JsonSubTypes.Type(value = classOf[UnauthorizedError], name = "unauthorized"),
    new JsonSubTypes.Type(value = classOf[UnauthorizedError], name = "unknown"),
    new JsonSubTypes.Type(value = classOf[NotFoundError], name = "not_found")
  ))
  sealed trait AddPermissionsError

  final case class AddPermissionsResponse(response: Either[AddPermissionsError, Ok]) extends CborSerializable


  //
  // RemovePermissions
  //
  case class RemovePermissionsRequest(domainId: DomainId,
                                      activityId: ActivityId,
                                      requester: Option[DomainSessionAndUserId],
                                      permissions: RemovePermissions,
                                      replyTo: ActorRef[RemovePermissionsResponse]
                                     ) extends ActivityPermissionsRequest[RemovePermissionsResponse]

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
  @JsonSubTypes(Array(
    new JsonSubTypes.Type(value = classOf[UnauthorizedError], name = "unauthorized"),
    new JsonSubTypes.Type(value = classOf[UnauthorizedError], name = "unknown"),
    new JsonSubTypes.Type(value = classOf[NotFoundError], name = "not_found")
  ))
  sealed trait RemovePermissionsError

  final case class RemovePermissionsResponse(response: Either[RemovePermissionsError, Ok]) extends CborSerializable


  //
  // SetPermissions
  //
  case class SetPermissionsRequest(domainId: DomainId,
                                   activityId: ActivityId,
                                   requester: Option[DomainSessionAndUserId],
                                   permissions: SetPermissions,
                                   replyTo: ActorRef[SetPermissionsResponse]
                                  ) extends ActivityPermissionsRequest[SetPermissionsResponse]

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
  @JsonSubTypes(Array(
    new JsonSubTypes.Type(value = classOf[UnauthorizedError], name = "unauthorized"),
    new JsonSubTypes.Type(value = classOf[UnauthorizedError], name = "unknown"),
    new JsonSubTypes.Type(value = classOf[NotFoundError], name = "not_found")
  ))
  sealed trait SetPermissionsError

  final case class SetPermissionsResponse(response: Either[SetPermissionsError, Ok]) extends CborSerializable

  //
  // GetPermissions
  //
  case class GetPermissionsRequest(domainId: DomainId,
                                   activityId: ActivityId,
                                   requester: Option[DomainSessionAndUserId],
                                   replyTo: ActorRef[GetPermissionsResponse]
                                  ) extends ActivityPermissionsRequest[GetPermissionsResponse]

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
  @JsonSubTypes(Array(
    new JsonSubTypes.Type(value = classOf[UnauthorizedError], name = "unauthorized"),
    new JsonSubTypes.Type(value = classOf[UnauthorizedError], name = "unknown"),
    new JsonSubTypes.Type(value = classOf[NotFoundError], name = "not_found")
  ))
  sealed trait GetPermissionsError

  final case class GetPermissionsResponse(permissions: Either[GetPermissionsError, AllPermissions]) extends CborSerializable

  //
  // ResolvedPermissionsForSession
  //
  case class ResolvePermissionsRequest(domainId: DomainId,
                                       activityId: ActivityId,
                                       requester: Option[DomainSessionAndUserId],
                                       session: DomainSessionAndUserId,
                                       replyTo: ActorRef[ResolvePermissionsResponse]
                                      ) extends ActivityPermissionsRequest[ResolvePermissionsResponse]

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
  @JsonSubTypes(Array(
    new JsonSubTypes.Type(value = classOf[UnauthorizedError], name = "unauthorized"),
    new JsonSubTypes.Type(value = classOf[UnauthorizedError], name = "unknown"),
    new JsonSubTypes.Type(value = classOf[NotFoundError], name = "not_found")
  ))
  sealed trait ResolvePermissionsError

  final case class ResolvePermissionsResponse(permissions: Either[ResolvePermissionsError, Set[String]]) extends CborSerializable


  //
  // Common Errors
  //
  sealed trait CommonErrors


  final case class NotJoinedError() extends AnyRef
    with LeaveError
    with CommonErrors

  final case class UnauthorizedError(message: Option[String] = None) extends AnyRef
    with CommonErrors
    with CreateError
    with DeleteError
    with JoinError
    with GetPermissionsError
    with SetPermissionsError
    with AddPermissionsError
    with RemovePermissionsError
    with ResolvePermissionsError

  final case class NotFoundError() extends AnyRef
    with CommonErrors
    with DeleteError
    with JoinError
    with ResolvePermissionsError
    with AddPermissionsError
    with RemovePermissionsError
    with SetPermissionsError
    with GetPermissionsError

  final case class UnknownError() extends AnyRef
    with CommonErrors
    with CreateError
    with DeleteError
    with JoinError
    with ResolvePermissionsError
    with AddPermissionsError
    with RemovePermissionsError
    with SetPermissionsError
    with GetPermissionsError
}
