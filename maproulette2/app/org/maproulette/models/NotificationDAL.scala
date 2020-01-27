// Copyright (C) 2019 MapRoulette contributors (see CONTRIBUTORS.md).
// Licensed under the Apache License, Version 2.0 (see LICENSE).
package org.maproulette.models.dal

import java.sql.Connection
import anorm.SqlParser._
import anorm._
import anorm.JodaParameterMetaData._
import javax.inject.{Inject, Provider, Singleton}
import org.apache.commons.lang3.StringUtils
import org.joda.time.{DateTime, DateTimeZone}
import org.maproulette.exception.{InvalidException, NotFoundException}
import org.maproulette.Config
import org.maproulette.session.dal.{UserDAL}
import org.maproulette.models.{UserNotification, UserNotificationEmail, NotificationSubscriptions, Challenge, Task, Comment}
import org.maproulette.models.utils.DALHelper
import org.maproulette.session.User
import org.maproulette.data.UserType
import org.maproulette.permissions.Permission
import org.maproulette.provider.websockets.WebSocketProvider
import org.maproulette.provider.websockets.WebSocketMessages
import org.slf4j.LoggerFactory
import play.api.db.Database


/**
  * @author nrotstan
  */
@Singleton
class NotificationDAL @Inject()(db: Database,
                                userDAL: Provider[UserDAL],
                                projectDAL: Provider[ProjectDAL],
                                webSocketProvider: WebSocketProvider,
                                config: Config,
                                permission: Permission)
  extends DALHelper {

  import org.maproulette.utils.AnormExtension._

  // The anorm row parser for user notifications
  val userNotificationParser: RowParser[UserNotification] = {
    get[Long]("user_notifications.id") ~
    get[Long]("user_notifications.user_id") ~
    get[Int]("user_notifications.notification_type") ~
    get[DateTime]("user_notifications.created") ~
    get[DateTime]("user_notifications.modified") ~
    get[Option[String]]("user_notifications.description") ~
    get[Option[String]]("user_notifications.from_username") ~
    get[Option[String]]("challenges.name") ~
    get[Boolean]("user_notifications.is_read") ~
    get[Int]("user_notifications.email_status") ~
    get[Option[Long]]("user_notifications.task_id") ~
    get[Option[Long]]("user_notifications.challenge_id") ~
    get[Option[Long]]("user_notifications.project_id") ~
    get[Option[Long]]("user_notifications.target_id") ~
    get[Option[String]]("user_notifications.extra") map {
      case id ~ userId ~ notificationType ~ created ~ modified ~ description ~ fromUsername ~ challengeName ~ isRead ~ emailStatus ~ taskId ~ challengeId ~ projectId ~ targetId ~ extra =>
        new UserNotification(id, userId, notificationType, created, modified, description, fromUsername, challengeName, isRead, emailStatus, taskId, challengeId, projectId, targetId, extra)
    }
  }

  val userNotificationEmailParser: RowParser[UserNotificationEmail] = {
    get[Long]("user_notifications.id") ~
    get[Long]("user_notifications.user_id") ~
    get[Int]("user_notifications.notification_type") ~
    get[Option[String]]("user_notifications.extra") ~
    get[DateTime]("user_notifications.created") ~
    get[Int]("user_notifications.email_status") map {
      case id ~ userId ~ notificationType ~ extra ~ created ~ emailStatus =>
        new UserNotificationEmail(id, userId, notificationType, extra, created, emailStatus)
    }
  }

  // The anorm row parser for user's subscriptions to notifications
  val notificationSubscriptionParser: RowParser[NotificationSubscriptions] = {
    get[Long]("id") ~
    get[Long]("user_id") ~
    get[Int]("system") ~
    get[Int]("mention") ~
    get[Int]("review_approved") ~
    get[Int]("review_rejected") ~
    get[Int]("review_again") ~
    get[Int]("challenge_completed") map {
      case id ~ userId ~ system ~ mention ~ reviewApproved ~ reviewRejected ~ reviewAgain ~ challengeCompleted =>
        NotificationSubscriptions(id, userId, system, mention, reviewApproved, reviewRejected, reviewAgain, challengeCompleted),
    }
  }

  def createMentionNotifications(fromUser:User, comment:Comment, task:Task) = {
    // match [@username] (username may contain spaces) or @username (no spaces allowed)
    val mentionRegex = """\[@([^\]]+)\]|@([\w\d_-]+)""".r.unanchored

    for (m <- mentionRegex.findAllMatchIn(comment.comment)) {
      // use first non-null group
      val username = m.subgroups.filter(_ != null).head

      // Retrieve and notify mentioned user
      userDAL.get().retrieveByOSMUsername(username, User.superUser) match {
        case Some(mentionedUser) =>
          this.addNotification(UserNotification(
            -1,
            userId=mentionedUser.id,
            notificationType=UserNotification.NOTIFICATION_TYPE_MENTION,
            fromUsername=Some(fromUser.osmProfile.displayName),
            taskId=Some(task.id),
            challengeId=Some(task.parent),
            targetId=Some(comment.id),
            extra=Some(comment.comment),
          ), User.superUser)
        case None => None
      }
    }
  }

  def createReviewNotification(user: User, forUserId: Long, reviewStatus: Int, task: Task, comment: Option[Comment]) = {
    val notificationType = reviewStatus match {
      case Task.REVIEW_STATUS_REQUESTED => UserNotification.NOTIFICATION_TYPE_REVIEW_AGAIN
      case Task.REVIEW_STATUS_APPROVED => UserNotification.NOTIFICATION_TYPE_REVIEW_APPROVED
      case Task.REVIEW_STATUS_ASSISTED => UserNotification.NOTIFICATION_TYPE_REVIEW_APPROVED
      case Task.REVIEW_STATUS_REJECTED => UserNotification.NOTIFICATION_TYPE_REVIEW_REJECTED
      case Task.REVIEW_STATUS_DISPUTED => UserNotification.NOTIFICATION_TYPE_REVIEW_AGAIN
    }

    this.addNotification(UserNotification(
      -1,
      userId=forUserId,
      notificationType=notificationType,
      fromUsername=Some(user.osmProfile.displayName),
      description=Some(reviewStatus.toString()),
      taskId=Some(task.id),
      challengeId=Some(task.parent),
      extra=comment match {
        case Some(c) => Some(c.comment)
        case None => None
      }
    ), User.superUser)
  }

  def createChallengeCompletionNotification(challenge: Challenge) = {
    projectDAL.get().retrieveById(challenge.general.parent) match {
      case Some(parentProject) =>
        userDAL.get().getUsersManagingProject(parentProject.id, None, User.superUser).foreach { manager =>
          this.addNotification(UserNotification(
            -1,
            userId=manager.userId,
            notificationType=UserNotification.NOTIFICATION_TYPE_CHALLENGE_COMPLETED,
            challengeId=Some(challenge.id),
            projectId=Some(parentProject.id),
            description=Some(challenge.name),
            extra=Some(s""""${challenge.name}" from project "${parentProject.displayName.getOrElse(parentProject.name)}"""")
          ), User.superUser)
        }
      case None =>
        throw new NotFoundException(s"Parent project ${challenge.general.parent} not found for challenge ${challenge.id}")
    }
  }

  /**
   * Add/insert a notification. The email setting of the notification will be automatically
   * set based on the recipient's email settings. If the recipient is not subscribed to
   * the type of notification given then it is simply ignored
   *
   * @param notification The notification to add
   * @param user The user making the request
   * @return
   */
  def addNotification(notification: UserNotification, user: User) = {
    permission.hasWriteAccess(UserType(), user)(notification.userId)
    val subscriptions = this.getNotificationSubscriptions(notification.userId, user)
    val subscriptionType = notification.notificationType match {
      case UserNotification.NOTIFICATION_TYPE_SYSTEM => subscriptions.system
      case UserNotification.NOTIFICATION_TYPE_MENTION => subscriptions.mention
      case UserNotification.NOTIFICATION_TYPE_REVIEW_APPROVED => subscriptions.reviewApproved
      case UserNotification.NOTIFICATION_TYPE_REVIEW_REJECTED => subscriptions.reviewRejected
      case UserNotification.NOTIFICATION_TYPE_REVIEW_AGAIN => subscriptions.reviewAgain
      case UserNotification.NOTIFICATION_TYPE_CHALLENGE_COMPLETED => subscriptions.challengeCompleted
      case _ => throw new InvalidException("Invalid notification type")
    }

    // Guard against ignored notification type
    subscriptionType match {
      case UserNotification.NOTIFICATION_IGNORE => None // nothing to do
      case _ =>
        notification.emailStatus = subscriptionType
        notification.isRead = false
        db.withConnection { implicit c =>
          val query =
            """INSERT INTO user_notifications (user_id, notification_type, description, from_username, is_read,
                                               email_status, task_id, challenge_id, project_id, target_id, extra)
               VALUES ({user_id}, {notification_type}, {description}, {from_username}, {is_read},
                       {email_status}, {task_id}, {challenge_id}, {project_id}, {target_id}, {extra})
               RETURNING *"""

          val newNotification = SQL(query).on(
            'user_id -> notification.userId,
            'notification_type -> notification.notificationType,
            'description -> notification.description,
            'from_username -> notification.fromUsername,
            'is_read -> false,
            'email_status -> notification.emailStatus,
            'task_id -> notification.taskId,
            'challenge_id -> notification.challengeId,
            'project_id -> notification.projectId,
            'target_id -> notification.targetId,
            'extra -> notification.extra
          ).execute()
        }
        webSocketProvider.sendMessage(WebSocketMessages.notificationNew(
          WebSocketMessages.NotificationData(notification.userId, notification.notificationType)
        ))
    }
  }

  /**
   * Retrieves notification subscriptions for a user
   *
   * @param userId The id of the subscribing user
   * @param user The user making the request
   * @return
   */
  def getNotificationSubscriptions(userId:Long, user:User): NotificationSubscriptions = {
    permission.hasReadAccess(UserType(), user)(userId)
    db.withConnection { implicit c =>
      SQL(
        s"""SELECT * FROM user_notification_subscriptions
            WHERE user_id=${userId} LIMIT 1"""
      ).as(notificationSubscriptionParser.*).headOption match {
        case Some(subscription) => subscription
        case None =>
          // Default to subscribing to all notifications, but with no emails
          NotificationSubscriptions(-1, userId, UserNotification.NOTIFICATION_EMAIL_NONE,
                                                UserNotification.NOTIFICATION_EMAIL_NONE,
                                                UserNotification.NOTIFICATION_EMAIL_NONE,
                                                UserNotification.NOTIFICATION_EMAIL_NONE,
                                                UserNotification.NOTIFICATION_EMAIL_NONE,
                                                UserNotification.NOTIFICATION_EMAIL_NONE)
      }
    }
  }

  /**
   * Updates notification subscriptions for a user
   *
   * @param userId The id of the subscribing user
   * @param user The user making the request
   * @param subscriptions The updated subscriptions
   * @return
   */
  def updateNotificationSubscriptions(userId: Long, user: User, subscriptions: NotificationSubscriptions) = {
    permission.hasWriteAccess(UserType(), user)(userId)
    db.withConnection { implicit c =>
      // Upsert new subscription settings
      SQL(
        s"""INSERT INTO user_notification_subscriptions (user_id, system, mention, review_approved, review_rejected, review_again, challenge_completed)
            VALUES({userId}, {system}, {mention}, {reviewApproved}, {reviewRejected}, {reviewAgain}, {challengeCompleted})
            ON CONFLICT (user_id) DO
            UPDATE SET system=EXCLUDED.system, mention=EXCLUDED.mention, review_approved=EXCLUDED.review_approved, review_rejected=EXCLUDED.review_rejected, review_again=EXCLUDED.review_again, challenge_completed=EXCLUDED.challenge_completed"""
      ).on(
        'userId -> userId,
        'system -> subscriptions.system,
        'mention -> subscriptions.mention,
        'reviewApproved -> subscriptions.reviewApproved,
        'reviewRejected -> subscriptions.reviewRejected,
        'reviewAgain -> subscriptions.reviewAgain,
        'challengeCompleted -> subscriptions.challengeCompleted,
      ).executeUpdate()
    }
  }

  /**
   * Marks as read the given notifications owned by the given userId
   *
   * @param userId The id of the user that owns the notifications
   * @param user The user making the request
   * @param notificationIds The ids of the notifications to be marked read
   */
  def markNotificationsRead(userId: Long, user: User, notificationIds: List[Long]) = {
    permission.hasWriteAccess(UserType(), user)(userId)
    db.withConnection { implicit c =>
      val query =
        s"""UPDATE user_notifications SET is_read=true
            WHERE user_notifications.user_id={user_id}
            ${this.getLongListFilter(Some(notificationIds), "user_notifications.id")}"""
      SQL(query).on('user_id -> userId).execute()
    }
  }

  /**
   * Deletes the given notifications owned by the given userId
   *
   * @param userId The id of the user that owns the notifications
   * @param user The user making the request
   * @param notificationIds The ids of the notifications to delete
   * @return
   */
  def deleteNotifications(userId: Long, user: User, notificationIds: List[Long]) = {
    permission.hasWriteAccess(UserType(), user)(userId)
    db.withConnection { implicit c =>
      val query =
        s"""DELETE from user_notifications
            WHERE user_notifications.user_id={user_id}
            ${this.getLongListFilter(Some(notificationIds), "user_notifications.id")}"""
      SQL(query).on('user_id -> userId).execute()
    }
  }

  /**
   * Retrieves the user notifications sent to the given userId
   */
  def getUserNotifications(userId: Long, user: User, limit: Int=Config.DEFAULT_LIST_SIZE, offset: Int=0,
                           orderColumn: String="is_read", orderDirection: String="ASC",
                           notificationType: Option[Int]=None, isRead: Option[Boolean]=None,
                           fromUsername: Option[String]=None, challengeId: Option[Long]=None): List[UserNotification] = {
    permission.hasReadAccess(UserType(), user)(userId)
    db.withConnection { implicit c =>
      val whereClause = new StringBuilder("WHERE user_id = {userId}")
      appendInWhereClause(whereClause, getOptionalFilter(notificationType, "notification_type", "notificationType"))
      appendInWhereClause(whereClause, getOptionalFilter(isRead, "is_read", "isRead"))
      appendInWhereClause(whereClause, getOptionalFilter(challengeId, "challenge_id", "challengeId"))
      appendInWhereClause(whereClause, getOptionalMatchFilter(fromUsername, "from_username", "fromUsername"))

      // In addition to the requested sort, we always add a sort by created desc
      // (unless created was the requested sort column)
      var orderClause = this.order(Some(orderColumn), orderDirection)
      if (orderColumn != "created") {
        orderClause ++= ", created desc"
      }

      val query = s"""
         |SELECT user_notifications.*, challenges.name
         |FROM user_notifications
         |LEFT OUTER JOIN challenges on user_notifications.challenge_id = challenges.id
         |${whereClause}
         |${orderClause}
         |LIMIT ${sqlLimit(limit)} OFFSET $offset
       """.stripMargin
       SQL(query).on(
         'userId -> userId,
         'notificationType -> notificationType,
         'isRead -> isRead,
         'challengeId -> challengeId,
         'fromUsername -> fromUsername,
       ).as(userNotificationParser.*)
    }
  }
}
