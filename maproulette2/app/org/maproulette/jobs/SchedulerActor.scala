// Copyright (C) 2019 MapRoulette contributors (see CONTRIBUTORS.md).
// Licensed under the Apache License, Version 2.0 (see LICENSE).
package org.maproulette.jobs

import akka.actor.{Actor, Props}
import anorm.JodaParameterMetaData._
import anorm._
import javax.inject.{Inject, Singleton}
import org.joda.time.DateTime
import org.maproulette.Config
import org.maproulette.jobs.SchedulerActor.RunJob
import org.maproulette.jobs.utils.LeaderboardHelper
import org.maproulette.metrics.Metrics
import org.maproulette.models.{Task, UserNotification, UserNotificationEmail, UserNotificationEmailDigest}
import org.maproulette.models.Task.STATUS_CREATED
import org.maproulette.models.dal.DALManager
import org.maproulette.provider.{KeepRightBox, KeepRightError, KeepRightProvider, EmailProvider}
import org.maproulette.session.User
import org.maproulette.utils.BoundingBoxFinder
import org.slf4j.LoggerFactory
import play.api.Application
import play.api.db.Database

import scala.util.{Failure, Success}

/**
  * The main actor that handles all scheduled activities
  *
  * @author cuthbertm
  * @author davis_20
  */
@Singleton
class SchedulerActor @Inject()(config: Config,
                               application: Application,
                               db: Database,
                               dALManager: DALManager,
                               keepRightProvider: KeepRightProvider,
                               boundingBoxFinder: BoundingBoxFinder,
                               emailProvider: EmailProvider) extends Actor {
  // cleanOldTasks configuration
  lazy val oldTasksStatusFilter = appConfig.getOptional[Seq[Int]](Config.KEY_SCHEDULER_CLEAN_TASKS_STATUS_FILTER).getOrElse(
    Seq[Int](new Integer(STATUS_CREATED))
  )

  import scala.concurrent.ExecutionContext.Implicits.global

  val appConfig = application.configuration
  private val logger = LoggerFactory.getLogger(this.getClass)

  override def receive: Receive = {
    case RunJob("rebuildChallengesLeaderboard", action) => this.rebuildChallengesLeaderboard(action)
    case RunJob("rebuildCountryLeaderboard", action) => this.rebuildCountryLeaderboard(action)
    case RunJob("cleanLocks", action) => this.cleanLocks(action)
    case RunJob("cleanClaimLocks", action) => this.cleanClaimLocks(action)
    case RunJob("runChallengeSchedules", action) => this.runChallengeSchedules(action)
    case RunJob("updateLocations", action) => this.updateLocations(action)
    case RunJob("cleanOldTasks", action) => this.cleanOldTasks(action)
    case RunJob("cleanExpiredVirtualChallenges", action) => this.cleanExpiredVirtualChallenges(action)
    case RunJob("FindChangeSets", action) => this.findChangeSets(action)
    case RunJob("OSMChangesetMatcher", action) => this.matchChangeSets(action)
    case RunJob("cleanDeleted", action) => this.cleanDeleted(action)
    case RunJob("KeepRightUpdate", action) => this.keepRightUpdate(action)
    case RunJob("snapshotUserMetrics", action) => this.snapshotUserMetrics(action)
    case RunJob("sendImmediateNotificationEmails", action) => this.sendImmediateNotificationEmails(action)
    case RunJob("sendDigestNotificationEmails", action) => this.sendDigestNotificationEmails(action)
  }

  /**
    * This job will remove all stale locks from the system. A stale lock is a lock that has maintained
    * the lock for over an hour. To enable, set:
    *    osm.scheduler.cleanLocks.interval=FiniteDuration
    */
  def cleanLocks(action: String): Unit = {
    logger.info(action)
    this.db.withTransaction { implicit c =>
      val query = s"DELETE FROM locked WHERE AGE(NOW(), locked_time) > '${config.taskLockExpiry}'"
      val locksDeleted = SQL(query).executeUpdate()
      logger.info(s"$locksDeleted were found and deleted.")
    }
  }

  /**
    * This job will remove all stale (older than 1 day) review claim locks from the system
    */
  def cleanClaimLocks(action:String) : Unit = {
    logger.info(action)
    this.db.withTransaction { implicit c =>
      val claimsRemoved = SQL"""UPDATE task_review
                                SET review_claimed_by = NULL, review_claimed_at = NULL
                                WHERE AGE(NOW(), review_claimed_at) > '12 hours'""".executeUpdate()
      logger.info(s"$claimsRemoved stale review claims were found and deleted.")
    }
  }

  /**
    * This job will update the challenges from remote geojson or overpass query based on the supplied
    * schedules in the challenge. To enable, set:
    *    osm.scheduler.runChallengeSchedules.interval=FiniteDuration
    */
  def runChallengeSchedules(action: String): Unit = {
    logger.info(action)
  }


  /**
    * This job will update the locations of all the challenges periodically. To enable, set:
    *    osm.scheduler.updateLocations.interval=FiniteDuration
    */
  def updateLocations(action: String): Unit = {
    logger.info(action)
    val currentTime = DateTime.now()
    val staleChallengeIds = db.withTransaction { implicit c =>
      SQL("SELECT id FROM challenges WHERE modified > last_updated OR last_updated IS NULL")
        .as(SqlParser.long("id").*)
    }

    staleChallengeIds.foreach(id => {
      db.withTransaction { implicit c =>
        try {
          val query =
            s"""UPDATE challenges SET
                          location = (SELECT ST_Centroid(ST_Collect(ST_Makevalid(location)))
                                      FROM tasks
                                      WHERE parent_id = ${id}),
                          bounding = (SELECT ST_Envelope(ST_Buffer((ST_SetSRID(ST_Extent(location), 4326))::geography,2)::geometry)
                                      FROM tasks
                                      WHERE parent_id = ${id}),
                          last_updated = NOW()
                      WHERE id = ${id};"""
          SQL(query).executeUpdate()
          c.commit()
        } catch {
          case e: Exception => {
            logger.error("Unable to update location on challenge " + id, e)
          }
        }
      }
    })

    db.withTransaction { implicit c =>
      SQL("SELECT id FROM challenges WHERE last_updated > {currentTime}")
        .on('currentTime -> ToParameterValue.apply[DateTime].apply(currentTime))
        .as(SqlParser.long("id").*)
        .foreach(id => {
          logger.debug(s"Flushing challenge cache of challenge with id $id")
          this.dALManager.challenge.cacheManager.cache.remove(id)
        })
    }
    logger.info("Completed updating challenge locations.")
  }

  /**
    * This job will delete old tasks, filtered by the statusFilter. To enable, set:
    *    osm.scheduler.cleanOldTasks.interval=FiniteDuration
    *    osm.scheduler.cleanOldTasks.olderThan=FiniteDuration
    */
  def cleanOldTasks(action: String): Unit = {
    config.withFiniteDuration(Config.KEY_SCHEDULER_CLEAN_TASKS_OLDER_THAN) { duration =>
      Metrics.timer("Cleaning old challenge tasks") { () =>
        db.withTransaction { implicit c =>
          logger.info(s"Cleaning old challenge tasks older than $duration with status [$oldTasksStatusFilter]...")
          val tasksDeleted =
            SQL(
              """DELETE FROM tasks t USING challenges c
                    WHERE t.parent_id = c.id AND c.updateTasks = true AND t.status IN ({statuses})
                     AND AGE(NOW(), c.modified) > {duration}::INTERVAL
                     AND AGE(NOW(), t.modified) > {duration}::INTERVAL""").on(
              'duration -> ToParameterValue.apply[String].apply(String.valueOf(duration)),
              'statuses -> ToParameterValue.apply[Seq[Int]].apply(oldTasksStatusFilter)
            ).executeUpdate()
          logger.info(s"$tasksDeleted old challenge tasks were found and deleted.")
          // Clear the task cache if any were deleted
          if (tasksDeleted > 0) {
            this.dALManager.task.cacheManager.clearCaches
          }
        }
      }
    }
  }

  /**
    * This job will delete expired Virtual Challenges. To enable, set:
    *    osm.scheduler.cleanExpiredVCs.interval=FiniteDuration
    */
  def cleanExpiredVirtualChallenges(str: String): Unit = {
    db.withConnection { implicit c =>
      val numberOfDeleted = SQL"""DELETE FROM virtual_challenges WHERE expired < NOW()""".executeUpdate()
      logger.info(s"$numberOfDeleted Virtual Challenges expired and removed from database")
      // Clear the task cache if any were deleted
      if (numberOfDeleted > 0) {
        this.dALManager.virtualChallenge.cacheManager.clearCaches
      }
    }
  }

  /**
    * Run through all the tasks and match OSM Changesets to fixed tasks. This will run through tasks
    * 5000 at a time, and limit the tasks returned to only tasks that have actually had their status
    * set to FIXED and changeset value not set to -2. If the value is -2 then it assumes that we have
    * already tried to match the changeset and couldn't find any viable option for it.
    *
    * @param str
    */
  def matchChangeSets(str: String): Unit = {
    if (config.osmMatcherEnabled) {
      db.withConnection { implicit c =>
        val query =
          s"""
             |SELECT ${dALManager.task.retrieveColumns} FROM tasks
             |WHERE status = 1 AND changeset_id = -1
             |LIMIT ${config.osmMatcherBatchSize}
         """.stripMargin
        SQL(query).as(dALManager.task.parser.*).foreach(t => {
          dALManager.task.matchToOSMChangeSet(t, User.superUser)
        })
      }
    }
  }

  /**
    * Task that manually matches the OSM changesets to tasks
    *
    * @param str
    */
  def findChangeSets(str: String): Unit = {
    if (config.osmMatcherManualOnly) {
      val values = str.split("=")
      if (values.size == 2) {
        implicit val id = values(1).toLong
        values(0) match {
          case "p" =>
            dALManager.project.listChildren(-1).foreach(c => {
              dALManager.challenge.listChildren(-1)(c.id).filter(_.status.contains(Task.STATUS_FIXED)).foreach(t =>
                dALManager.task.matchToOSMChangeSet(t, User.superUser, false)
              )
            })
          case "c" =>
            dALManager.challenge.listChildren(-1).foreach(t => {
              dALManager.task.matchToOSMChangeSet(t, User.superUser, false)
            })
          case "t" =>
            dALManager.task.retrieveById match {
              case Some(t) => dALManager.task.matchToOSMChangeSet(t, User.superUser, false)
              case None =>
            }
          case _ => // Do nothing because there is nothing to do
        }
      }
    }
  }

  def cleanDeleted(action: String): Unit = {
    logger.info(action)
    db.withConnection { implicit c =>
      val deletedProjects = SQL"DELETE FROM projects WHERE deleted = true RETURNING id".as(SqlParser.int("id").*)
      if (deletedProjects.nonEmpty) {
        logger.debug(s"Finalized deletion of projects with id [${deletedProjects.mkString(",")}]")
      }
      val deletedChallenges = SQL"DELETE FROM challenges WHERE deleted = true RETURNING id".as(SqlParser.int("id").*)
      if (deletedChallenges.nonEmpty) {
        logger.debug(s"Finalized deletion of challenges with id [${deletedChallenges.mkString(",")}]")
      }
    }
  }

  def keepRightUpdate(action: String): Unit = {
    logger.info(action)
    val slidingValue = this.config.config.getOptional[Int](KeepRightProvider.KEY_SLIDING).getOrElse(KeepRightProvider.DEFAULT_SLIDING)
    val slidingErrors = keepRightProvider.errorList.sliding(slidingValue, slidingValue).toList

    val integrationList: List[(List[KeepRightError], KeepRightBox)] =
      if (config.config.getOptional[Boolean](KeepRightProvider.KEY_ENABLED).getOrElse(false)) {
        if (keepRightProvider.boundingBoxes.nonEmpty && keepRightProvider.errorList.nonEmpty) {
          slidingErrors.flatMap(error =>
            keepRightProvider.boundingBoxes map { bounding =>
              (error, bounding)
            }
          )
        } else {
          List.empty
        }
      } else {
        List.empty
      }
    integrationList.headOption match {
      case Some(h) => this.integrateKeepRight(h, integrationList.tail)
      case None => //just do nothing
    }
  }

  /**
    * Rebuilds the user_leaderboard table.
    *
    * @param action - action string
    */
  def rebuildChallengesLeaderboard(action: String): Unit = {
    val start = System.currentTimeMillis
    logger.info(action)

    db.withConnection { implicit c =>
      // Clear TABLEs
      SQL("DELETE FROM user_leaderboard WHERE country_code IS NULL").executeUpdate()
      SQL("DELETE FROM user_top_challenges WHERE country_code IS NULL").executeUpdate()

      // Past Month
      SQL(LeaderboardHelper.rebuildChallengesLeaderboardSQL(SchedulerActor.ONE_MONTH, config)).executeUpdate()
      SQL(LeaderboardHelper.rebuildTopChallengesSQL(SchedulerActor.ONE_MONTH, config)).executeUpdate()

      // Past 3 Months
      SQL(LeaderboardHelper.rebuildChallengesLeaderboardSQL(SchedulerActor.THREE_MONTHS, config)).executeUpdate()
      SQL(LeaderboardHelper.rebuildTopChallengesSQL(SchedulerActor.THREE_MONTHS, config)).executeUpdate()

      // Past 6 Months
      SQL(LeaderboardHelper.rebuildChallengesLeaderboardSQL(SchedulerActor.SIX_MONTHS, config)).executeUpdate()
      SQL(LeaderboardHelper.rebuildTopChallengesSQL(SchedulerActor.SIX_MONTHS, config)).executeUpdate()

      // Past Year
      SQL(LeaderboardHelper.rebuildChallengesLeaderboardSQL(SchedulerActor.TWELVE_MONTHS, config)).executeUpdate()
      SQL(LeaderboardHelper.rebuildTopChallengesSQL(SchedulerActor.TWELVE_MONTHS, config)).executeUpdate()

      // All Time
      SQL(LeaderboardHelper.rebuildChallengesLeaderboardSQL(SchedulerActor.ALL_TIME, config)).executeUpdate()
      SQL(LeaderboardHelper.rebuildTopChallengesSQL(SchedulerActor.ALL_TIME, config)).executeUpdate()

      logger.info(s"Rebuilt Challenges Leaderboard succesfully.")
      val totalTime = System.currentTimeMillis - start
      logger.debug("Time to rebuild leaderboard: %1d ms".format(totalTime))
    }
  }

  /**
    * Rebuilds the user_leaderboard table.
    *
    * @param action - action string
    */
  def rebuildCountryLeaderboard(action: String): Unit = {
    val start = System.currentTimeMillis
    logger.info(action)

    db.withConnection { implicit c =>
      // Clear TABLEs
      SQL("DELETE FROM user_leaderboard WHERE country_code IS NOT NULL").executeUpdate()
      SQL("DELETE FROM user_top_challenges WHERE country_code IS NOT NULL").executeUpdate()

      val countryCodeMap = boundingBoxFinder.boundingBoxforAll()
      for ((countryCode, boundingBox) <- countryCodeMap) {
        SQL(LeaderboardHelper.rebuildChallengesLeaderboardSQLCountry(SchedulerActor.ONE_MONTH, countryCode, boundingBox, config)).executeUpdate()
        SQL(LeaderboardHelper.rebuildChallengesLeaderboardSQLCountry(SchedulerActor.THREE_MONTHS, countryCode, boundingBox, config)).executeUpdate()
        SQL(LeaderboardHelper.rebuildChallengesLeaderboardSQLCountry(SchedulerActor.SIX_MONTHS, countryCode, boundingBox, config)).executeUpdate()
        SQL(LeaderboardHelper.rebuildChallengesLeaderboardSQLCountry(SchedulerActor.TWELVE_MONTHS, countryCode, boundingBox, config)).executeUpdate()
        SQL(LeaderboardHelper.rebuildChallengesLeaderboardSQLCountry(SchedulerActor.ALL_TIME, countryCode, boundingBox, config)).executeUpdate()
        SQL(LeaderboardHelper.rebuildTopChallengesSQLCountry(SchedulerActor.TWELVE_MONTHS, countryCode, boundingBox, config)).executeUpdate()
        SQL(LeaderboardHelper.rebuildTopChallengesSQLCountry(SchedulerActor.ALL_TIME, countryCode, boundingBox, config)).executeUpdate()
      }

      logger.info(s"Rebuilt Country Leaderboard succesfully.")
      val totalTime = System.currentTimeMillis - start
      logger.debug("Time to rebuild country leaderboard: %1d ms".format(totalTime))
    }
  }

  def sendImmediateNotificationEmails(action: String) = {
    logger.info(action)

    // Gather notifications needing an immediate email and send email for each
    db.withConnection { implicit c =>
      SQL(s"""
        |UPDATE user_notifications
        |SET email_status = ${UserNotification.NOTIFICATION_EMAIL_SENT}
        |WHERE id in (
        |  SELECT id from user_notifications
        |  WHERE email_status = ${UserNotification.NOTIFICATION_EMAIL_IMMEDIATE}
        |  ORDER BY created ASC
        |  LIMIT ${config.notificationImmediateEmailBatchSize}
        |) RETURNING *
      """.stripMargin).as(dALManager.notification.userNotificationEmailParser.*).foreach(notification => {
        // Send email if user has an email address on file
        try {
          dALManager.user.retrieveById(notification.userId) match {
            case Some(user) =>
              user.settings.email match {
                case Some(address) if (!address.isEmpty) =>
                  this.emailProvider.emailNotification(address, notification)
                case _ => None
              }
            case None => None
          }
        } catch {
          case e: Exception => logger.error("Failed to send immediate email: " + e)
        }
      })
    }
  }

  def sendDigestNotificationEmails(action: String) = {
    logger.info(action)
    var digests: List[UserNotificationEmailDigest] = List.empty

    db.withConnection { implicit c =>
      // Gather up users with unsent digest notifications and build digests for each
      digests = SQL(s"""
        |SELECT distinct(user_id) from user_notifications
        |WHERE email_status = ${UserNotification.NOTIFICATION_EMAIL_DIGEST}
      """.stripMargin).as(SqlParser.int("user_id").*).map(recipientId => {
        val digestNotifications = SQL(s"""
            |UPDATE user_notifications
            |SET email_status = ${UserNotification.NOTIFICATION_EMAIL_SENT}
            |WHERE id in (
            |  SELECT id from user_notifications
            |  WHERE email_status = ${UserNotification.NOTIFICATION_EMAIL_DIGEST} AND
            |       user_id=${recipientId}
            |) RETURNING *
        """.stripMargin).as(dALManager.notification.userNotificationEmailParser.*)

        UserNotificationEmailDigest(recipientId, digestNotifications)
      })
    }

    // Email each digest if recipient has an email address on file
    digests.foreach(digest => {
      try {
        dALManager.user.retrieveById(digest.userId) match {
          case Some(user) =>
            user.settings.email match {
              case Some(address) if (!address.isEmpty) =>
                this.emailProvider.emailNotificationDigest(address, digest.notifications)
              case _ => None
            }
          case None => None
        }
      } catch {
        case e: Exception => logger.error("Failed to send digest email: " + e)
      }
    })
  }

  /**
    * We essentially create this recursive function, so that we don't take down the KeepRight servers
    * by bombarding it with tons of API requests.
    *
    * @param head The head of the list, which is a tuple containing a KeepRightError and a KeepRightBox
    * @param tail The tail list of box objects
    */
  private def integrateKeepRight(head: (List[KeepRightError], KeepRightBox),
                                 tail: List[(List[KeepRightError], KeepRightBox)]): Unit = {
    keepRightProvider.integrate(head._1.map(_.id), head._2) onComplete {
      case Success(x) =>
        if (!x) {
          logger.warn(s"KeepRight challenge failed, but continuing to next one")
        }
        tail.headOption match {
          case Some(head) => this.integrateKeepRight(head, tail.tail)
          case None => // just do nothing because we are finished
        }
      case Failure(f) =>
        // something went wrong, we should bail out immediately
        logger.warn(s"The KeepRight challenge creation failed. ${f.getMessage}")
    }
  }

  /**
   * Snapshots the user_metrics table and stores in in user_metrics_history
   *
   * @param action - action string
   */
  def snapshotUserMetrics(action:String) : Unit = {
    logger.info(action)

    db.withConnection { implicit c =>
      SQL(s"""UPDATE user_metrics set score=data.score, total_fixed=data.total_fixed,
              total_false_positive=data.total_false_positive, total_already_fixed=data.total_already_fixed,
              total_too_hard=data.total_too_hard, total_skipped=data.total_skipped
              FROM (
              SELECT users.id,
                       SUM(CASE sa.status
                           WHEN ${Task.STATUS_FIXED} THEN ${config.taskScoreFixed}
                           WHEN ${Task.STATUS_FALSE_POSITIVE} THEN ${config.taskScoreFalsePositive}
                           WHEN ${Task.STATUS_ALREADY_FIXED} THEN ${config.taskScoreAlreadyFixed}
                           WHEN ${Task.STATUS_TOO_HARD} THEN ${config.taskScoreTooHard}
                           WHEN ${Task.STATUS_SKIPPED} THEN ${config.taskScoreSkipped}
                           ELSE 0
                       END) AS score,
                       SUM(CASE WHEN sa.status = ${Task.STATUS_FIXED} THEN ${config.taskScoreFixed} else 0 end) total_fixed,
                       SUM(CASE WHEN sa.status = ${Task.STATUS_FALSE_POSITIVE} THEN ${config.taskScoreFalsePositive} else 0 end) total_false_positive,
                       SUM(CASE WHEN sa.status = ${Task.STATUS_ALREADY_FIXED} THEN ${config.taskScoreAlreadyFixed} else 0 end) total_already_fixed,
                       SUM(CASE WHEN sa.status = ${Task.STATUS_TOO_HARD} THEN ${config.taskScoreTooHard} end) total_too_hard,
                       SUM(CASE WHEN sa.status = ${Task.STATUS_SKIPPED} THEN ${config.taskScoreSkipped} else 0 end) total_skipped
               FROM status_actions sa, users
               WHERE users.osm_id = sa.osm_user_id AND sa.old_status <> sa.status
               GROUP BY sa.osm_user_id, users.id) AS data
              WHERE user_metrics.user_id = data.id
           """).executeUpdate()
      logger.info(s"Refreshed user metrics from status actions.")
    }

    db.withConnection { implicit c =>
      SQL(s"""INSERT INTO user_metrics_history
              SELECT user_id, score, total_fixed, total_false_positive, total_already_fixed,
                     total_too_hard, total_skipped, now(), initial_rejected, initial_approved,
                     initial_assisted, total_rejected, total_approved, total_assisted,
                     total_disputed_as_mapper, total_disputed_as_reviewer
              FROM user_metrics
           """).executeUpdate()


      logger.info(s"Succesfully created snapshot of user metrics.")
    }
  }

}

object SchedulerActor {
  private val ONE_MONTH = 1
  private val THREE_MONTHS = 3
  private val SIX_MONTHS = 6
  private val TWELVE_MONTHS = 12
  private val ALL_TIME = -1

  def props = Props[SchedulerActor]

  case class RunJob(name: String, action: String = "")

}
