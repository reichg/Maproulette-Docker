// Copyright (C) 2016 MapRoulette contributors (see CONTRIBUTORS.md).
// Licensed under the Apache License, Version 2.0 (see LICENSE).
package org.maproulette.provider

import play.api.libs.mailer._
import java.io.File
import org.apache.commons.mail.EmailAttachment
import javax.inject.{Inject, Singleton}
import org.maproulette.Config
import org.maproulette.models.{UserNotification, UserNotificationEmail}
import scala.concurrent.{Future}

/**
  * @author nrotstan
  *
  * TODO: internationalize these messages and move them out into templates
  */
@Singleton
class EmailProvider @Inject() (mailerClient: MailerClient, config: Config) {

  import scala.concurrent.ExecutionContext.Implicits.global

  def emailNotification(toAddress: String, notification: UserNotificationEmail) = {
    val notificationName = UserNotification.notificationTypeMap.get(notification.notificationType).get
    val emailSubject = s"New MapRoulette notification: ${notificationName}"
    val notificationDetails = notification.extra match {
      case Some(details) => s"\n${details}"
      case None => ""
    }

    val emailBody = s"""
      |You have received a new MapRoulette notification:
      |
      |${notificationName}
      |${notificationDetails}
      |${this.notificationFooter}""".stripMargin

    val email = Email(emailSubject, config.getEmailFrom.get, Seq(toAddress), bodyText = Some(emailBody))
    mailerClient.send(email)
  }

  def emailNotificationDigest(toAddress: String, notifications: List[UserNotificationEmail]) = {
    val notificationNames = notifications.map(notification =>
      UserNotification.notificationTypeMap.get(notification.notificationType).get
    )
    val notificationNameCounts = notificationNames.groupBy(identity).mapValues(_.size)
    val notificationLines = notificationNameCounts.foldLeft("") {
      (s: String, pair: (String, Int)) => s + pair._1 + " (" + pair._2 + ")\n"
    }
    val emailSubject = s"MapRoulette Notifications Daily Digest"
    val emailBody = s"""
      |You have received new MapRoulette notifications over the past day:
      |
      |${notificationLines}${this.notificationFooter}""".stripMargin

    val email = Email(emailSubject, config.getEmailFrom.get, Seq(toAddress), bodyText = Some(emailBody))
    mailerClient.send(email)
  }

  private def notificationFooter: String = {
    val urlPrefix = config.getPublicOrigin.get
    s"""
      |You can view your notifications by visiting your MapRoulette Inbox at:
      |${urlPrefix}/inbox
      |
      |Happy mapping!
      |--The MapRoulette Team
      |
      |
      |P.S. You received this because you asked to be emailed when you
      |received this type of notification in MapRoulette. You can manage
      |your notification subscriptions and email preferences at:
      |${urlPrefix}/profile""".stripMargin
  }
}
