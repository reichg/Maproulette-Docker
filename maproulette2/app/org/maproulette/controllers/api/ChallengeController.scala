// Copyright (C) 2019 MapRoulette contributors (see CONTRIBUTORS.md).
// Licensed under the Apache License, Version 2.0 (see LICENSE).
package org.maproulette.controllers.api

import java.io._
import java.sql.Connection
import java.util.zip.{ZipEntry, ZipOutputStream}

import org.joda.time.DateTime
import akka.util.ByteString
import javax.inject.Inject
import org.apache.commons.lang3.StringUtils
import org.maproulette.Config
import org.maproulette.controllers.ParentController
import org.maproulette.data._
import org.maproulette.exception.{InvalidException, MPExceptionUtil, NotFoundException, StatusMessage}
import org.maproulette.models._
import org.maproulette.models.dal._
import org.maproulette.permissions.Permission
import org.maproulette.provider.ChallengeProvider
import org.maproulette.session.{SearchParameters, SessionManager, User}
import org.maproulette.utils.Utils
import play.api.http.HttpEntity
import play.api.libs.Files
import play.api.libs.json._
import play.api.libs.ws.WSClient
import play.api.mvc._
import play.shaded.oauth.oauth.signpost.exception.OAuthNotAuthorizedException

import scala.concurrent.{Future, Promise}
import scala.io.Source
import scala.util.{Failure, Success}

/**
  * The challenge controller handles all operations for the Challenge objects.
  * This includes CRUD operations and searching/listing.
  * See ParentController for more details on parent object operations
  * See CRUDController for more details on CRUD object operations
  *
  * @author cuthbertm
  */
class ChallengeController @Inject()(override val childController: TaskController,
                                    override val sessionManager: SessionManager,
                                    override val actionManager: ActionManager,
                                    override val dal: ChallengeDAL,
                                    dalManager: DALManager,
                                    override val tagDAL: TagDAL,
                                    challengeProvider: ChallengeProvider,
                                    wsClient: WSClient,
                                    permission: Permission,
                                    config: Config,
                                    components: ControllerComponents,
                                    override val bodyParsers: PlayBodyParsers)
  extends AbstractController(components) with ParentController[Challenge, Task] with TagsMixin[Challenge] {

  import scala.concurrent.ExecutionContext.Implicits.global

  // json reads for automatically reading Challenges from a posted json body
  override implicit val tReads: Reads[Challenge] = Challenge.reads.challengeReads
  // json writes for automatically writing Challenges to a json body response
  override implicit val tWrites: Writes[Challenge] = Challenge.writes.challengeWrites
  // json writes for automatically writing Tasks to a json body response
  override protected val cWrites: Writes[Task] = Task.TaskFormat
  // json reads for automatically reading tasks from a posted json body
  override protected val cReads: Reads[Task] = Task.TaskFormat
  // The type of object that this controller deals with.
  override implicit val itemType: ItemType = ChallengeType()

  // implicit writes used for various JSON responses
  implicit val answerWrites = Challenge.answerWrites
  implicit val commentWrites = Comment.commentWrites
  implicit val pointWrites = ClusteredPoint.pointWrites
  implicit val clusteredPointWrites = ClusteredPoint.clusteredPointWrites
  implicit val taskClusterWrites = TaskCluster.taskClusterWrites
  implicit val searchLocationWrites = SearchParameters.locationWrites
  implicit val challengeListingWrites: Writes[ChallengeListing] = Json.writes[ChallengeListing]

  override def dalWithTags: TagDALMixin[Challenge] = dal

  /**
    * Function can be implemented to extract more information than just the default create data,
    * to build other objects with the current object at the core. No data will be returned from this
    * function, it purely does work in the background AFTER creating the current object
    *
    * @param body          The Json body of data
    * @param createdObject The object that was created by the create function
    * @param user          The user that is executing the function
    */
  override def extractAndCreate(body: JsValue, createdObject: Challenge, user: User)(implicit c: Option[Connection] = None): Unit = {
    val localJson = (body \ "localGeoJSON").asOpt[JsValue] match {
      case Some(local) => Some(Json.stringify(local))
      case None => None
    }
    if (!this.challengeProvider.buildTasks(user, createdObject, localJson)) {
      super.extractAndCreate(body, createdObject, user)
    }
    // we need to elevate the user permissions to super users to extract and create the tags
    this.extractTags(body, createdObject, User.superUser)
  }

  /**
    * Gets a json list of tags of the challenge
    *
    * @param id The id of the challenge containing the tags
    * @return The html Result containing json array of tags
    */
  def getTagsForChallenge(id: Long): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      Ok(Json.toJson(this.getTags(id)))
    }
  }

  /**
    * Gets the geo json for all the tasks associated with the challenge
    *
    * @param challengeId  The challenge with the geojson
    * @param statusFilter Filtering by status of the tasks
    * @param reviewStatusFilter Filtering by review status of the tasks
    * @param priorityFilter Filtering by priority of the tasks
    * @return
    */
  def getChallengeGeoJSON(challengeId: Long, statusFilter: String, reviewStatusFilter: String,
                          priorityFilter: String): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      this.dal.retrieveById(challengeId) match {
        case Some(c) =>
          val status = if (StringUtils.isEmpty(statusFilter)) {
            None
          } else {
            Some(Utils.split(statusFilter).map(_.toInt))
          }
          val reviewStatus = if (StringUtils.isEmpty(reviewStatusFilter)) {
            None
          } else {
            Some(Utils.split(reviewStatusFilter).map(_.toInt))
          }
          val priority = if (StringUtils.isEmpty(priorityFilter)) {
            None
          } else {
            Some(Utils.split(priorityFilter).map(_.toInt))
          }

          Result(
            header = ResponseHeader(OK, Map(CONTENT_DISPOSITION -> s"attachment; filename=challenge_geojson.json")),
            body = HttpEntity.Strict(
              ByteString(this.dal.getChallengeGeometry(challengeId, status, reviewStatus, priority)),
              Some("application/json;charset=utf-8;header=present"))
          )
        case None => throw new NotFoundException(s"No challenge with id $challengeId found.")
      }
    }
  }

  /**
    * Gets the tasks in the form of ClusteredPoints, which is just a task with limited information,
    * and the geometry associated with it is just the centroid of the task geometry
    *
    * @param challengeId  The challenge id, ie. the parent of the tasks
    * @param statusFilter Filter by status of the task (@deprecated - please use search paramter tStatus)
    * @param limit        limit the number of tasks returned
    * @param excludeLocked Don't cluster locked tasks
    * @return A list of ClusteredPoint's
    */
  def getClusteredPoints(challengeId: Long, statusFilter: String, limit: Int, excludeLocked: Boolean): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      SearchParameters.withSearch { implicit params =>
        var searchParams = params

        // For Backward compatibility
        val filter = if (StringUtils.isEmpty(statusFilter)) {
          None
        } else {
          searchParams = params.copy(taskStatus = Some(Utils.split(statusFilter).map(_.toInt)))
        }

        val result = this.dal.getClusteredPoints(User.userOrMocked(user), challengeId, searchParams, limit, excludeLocked)
        Ok(_insertReviewJSON(result))
      }
    }
  }

  /**
   * Fetches and inserts usernames for 'reviewRequestedBy' and 'reviewBy'
   */
  private def _insertReviewJSON(tasks: List[ClusteredPoint]): JsValue = {
    if (tasks.isEmpty) {
      Json.toJson(List[JsValue]())
    } else {
      val mappers = Some(this.dalManager.user.retrieveListById(-1, 0)(tasks.map(
        t => t.pointReview.reviewRequestedBy.getOrElse(0L))).map(u =>
          u.id -> Json.obj("username" -> u.name, "id" -> u.id)).toMap)

      val reviewers = Some(this.dalManager.user.retrieveListById(-1, 0)(tasks.map(
        t => t.pointReview.reviewedBy.getOrElse(0L))).map(u =>
          u.id -> Json.obj("username" -> u.name, "id" -> u.id)).toMap)

      val jsonList = tasks.map { task =>
        var updated = Json.toJson(task)
        if (task.pointReview.reviewRequestedBy.getOrElse(0) != 0) {
          val mapperJson = Json.toJson(mappers.get(task.pointReview.reviewRequestedBy.get.toLong)).as[JsObject]
          updated = Utils.insertIntoJson(updated, "reviewRequestedBy", mapperJson, true)
        }
        if (task.pointReview.reviewedBy.getOrElse(0) != 0) {
          val reviewerJson = Json.toJson(reviewers.get(task.pointReview.reviewedBy.get.toLong)).as[JsObject]
          updated = Utils.insertIntoJson(updated, "reviewedBy", reviewerJson, true)
        }

        updated
      }
      Json.toJson(jsonList)
    }
  }


  /**
    * Gets a random task that is a child of the challenge, includes the notion of priority
    *
    * @param challengeId The challenge id that is the parent of the tasks that you would be searching for.
    * @param taskSearch  Filter based on the name of the task
    * @param tags        A comma separated list of tags that optionally can be used to further filter the tasks
    * @param limit       Limit of how many tasks should be returned
    * @param proximityId Id of task that you wish to find the next task based on the proximity of that task
    * @return A list of Tasks that match the supplied filters
    */
  def getRandomTasksWithPriority(challengeId: Long, taskSearch: String, tags: String, limit: Int,
                                 proximityId: Long): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      SearchParameters.withSearch { p =>
        val params = p.copy(
          challengeParams = p.challengeParams.copy(challengeIds = Some(List(challengeId))),
          taskSearch = Some(taskSearch),
          taskTags = Some(Utils.split(tags))
        )
        val result = this.dalManager.task.getRandomTasksWithPriority(User.userOrMocked(user), params, limit, Utils.negativeToOption(proximityId))
        result.foreach(task => this.actionManager.setAction(user, this.itemType.convertToItem(task.id), TaskViewed(), ""))
        Ok(Json.toJson(result))
      }
    }
  }

  /**
    * Gets a random task that is a child of the challenge.
    *
    * @param challengeId The challenge id that is the parent of the tasks that you would be searching for.
    * @param taskSearch  Filter based on the name of the task
    * @param tags        A comma separated list of tags that optionally can be used to further filter the tasks
    * @param limit       Limit of how many tasks should be returned
    * @param proximityId Id of task that you wish to find the next task based on the proximity of that task
    * @return A list of Tasks that match the supplied filters
    */
  def getRandomTasks(challengeId: Long, taskSearch: String, tags: String, limit: Int,
                     proximityId: Long): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      SearchParameters.withSearch { p =>
        val params = p.copy(
          challengeParams = p.challengeParams.copy(challengeIds = Some(List(challengeId))),
          taskSearch = Some(taskSearch),
          taskTags = Some(Utils.split(tags))
        )
        val result = this.dalManager.task.getRandomTasks(User.userOrMocked(user), params, limit, None, Utils.negativeToOption(proximityId))
        result.foreach(task => this.actionManager.setAction(user, this.itemType.convertToItem(task.id), TaskViewed(), ""))
        Ok(Json.toJson(result))
      }
    }
  }

  /**
    * Gets tasks near the given task id within the given challenge
    *
    * @param challengeId  The challenge id that is the parent of the tasks that you would be searching for
    * @param proximityId  Id of task for which nearby tasks are desired
    * @param excludeSelfLocked Also exclude tasks locked by requesting user
    * @param limit        The maximum number of nearby tasks to return
    * @return
    */
  def getNearbyTasks(challengeId: Long, proximityId: Long, excludeSelfLocked: Boolean, limit: Int): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      val results = this.dalManager.task.getNearbyTasks(User.userOrMocked(user), challengeId, proximityId, excludeSelfLocked, limit)
      Ok(Json.toJson(results))
    }
  }

  /**
    * Gets the next task in sequential order for the specified challenge
    *
    * @param challengeId   The current challenge id
    * @param currentTaskId The current task id that is being viewed
    * @param statusList    Filter by task status
    * @return The next task in the list
    */
  def getSequentialNextTask(challengeId: Long, currentTaskId: Long, statusList: String): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      Ok(Utils.getResponseJSON(this.dalManager.task.getNextTaskInSequence(challengeId, currentTaskId,
        Utils.toIntList(statusList)), this.dalManager.task.getLastModifiedUser));
    }
  }

  /**
    * Gets the previous task in sequential order for the specified challenge
    *
    * @param challengeId   The current challenge id
    * @param currentTaskId The current task id that is being viewed
    * @param statusList    Filter by task status
    * @return The previous task in the list
    */
  def getSequentialPreviousTask(challengeId: Long, currentTaskId: Long, statusList: String): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      Ok(Utils.getResponseJSON(this.dalManager.task.getPreviousTaskInSequence(challengeId, currentTaskId,
        Utils.toIntList(statusList)), this.dalManager.task.getLastModifiedUser));
    }
  }

  /**
    * Gets the featured challenges
    *
    * @param limit  The number of challenges to get
    * @param offset The offset
    * @return A Json array with the featured challenges
    */
  def getFeaturedChallenges(limit: Int, offset: Int): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      Ok(Json.toJson(this.dal.getFeaturedChallenges(limit, offset)))
    }
  }

  /**
    * Gets the hot (recently popular) challenges
    *
    * @param limit  The number of challenges to get
    * @param offset The offset
    * @return A Json array with the hot challenges
    */
  def getHotChallenges(limit: Int, offset: Int): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      Ok(Json.toJson(this.dal.getHotChallenges(limit, offset)))
    }
  }

  /**
    * Gets the preferred challenges (hottest, newest, featured)
    *
    * @param limit  The number of challenges to get
    * @return A Json array with the hot challenges
    */
  def getPreferredChallenges(limit: Int): Action[AnyContent] = Action.async { implicit request =>
    val all = Map("popular" -> _insertProjectJSON(this.dal.getHotChallenges(limit, 0)),
                  "newest" -> _insertProjectJSON(this.dal.getNewChallenges(limit, 0)),
                  "featured" -> _insertProjectJSON(this.dal.getFeaturedChallenges(limit, 0)))

    Future(Ok(Json.toJson(all)))
  }


  def updateTaskPriorities(challengeId: Long): Action[AnyContent] = Action.async { implicit request =>
    implicit val requireSuperUser = true
    this.sessionManager.authenticatedRequest { implicit user =>
      val challengeIds = if (challengeId < 0) {
        val challengeList = this.dal.find("%", -1)
        challengeList.foreach {
          challenge => this.dal.updateTaskPriorities(user)(challenge.id)
        }
        challengeList.map(_.id)
      } else {
        this.dal.updateTaskPriorities(user)(challengeId)
        List(challengeId)
      }
      Ok(Json.toJson(StatusMessage("OK", JsString(s"Priorities updated for challenges ${challengeIds.mkString(",")}"))))
    }
  }

  /**
    * Resets the task instructions for all the children tasks of the supplied challenge
    *
    * @param challengeId id of the parent challenge
    * @return 200 empty Ok
    */
  def resetTaskInstructions(challengeId: Long): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      this.dal.resetTaskInstructions(user, challengeId)
      Ok
    }
  }

  /**
    * Deletes all the tasks under a challenge based on the status of the task. If no filter for the
    * status is supplied, then will delete all the tasks
    *
    * @param challengeId   The id of the challenge
    * @param statusFilters A comma separated list of status' to filter the deletion by.
    * @return
    */
  def deleteTasks(challengeId: Long, statusFilters: String = ""): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      dalManager.challenge.retrieveById(challengeId) match {
        case Some(c) =>
          permission.hasWriteAccess(ProjectType(), user)(c.general.parent)
          if (c.status.getOrElse(Challenge.STATUS_NA) == Challenge.STATUS_DELETING_TASKS) {
            throw new InvalidException("Task deletion already in-progress for this challenge")
          }
          else if (c.status.getOrElse(Challenge.STATUS_NA) == Challenge.STATUS_BUILDING) {
            throw new InvalidException("Tasks cannot be deleted while challenge is building")
          }

          val originalStatus = c.status
          dalManager.challenge.update(Json.obj("status" -> Challenge.STATUS_DELETING_TASKS), user)(challengeId)
          // Deleting a lot of tasks can be time consuming, so perform this asynchronously
          Future {
            try {
              this.dal.deleteTasks(user, challengeId, Utils.split(statusFilters).map(_.toInt))
            }
            finally {
              dalManager.challenge.update(Json.obj("status" -> originalStatus), user)(challengeId)
            }
          }
          Ok
        case None => throw new NotFoundException(s"No challenge found with id $challengeId")
      }
    }
  }

  /**
    * Retrieve all the comments for a specific challenge
    *
    * @param challengeId The id of the challenge
    * @return A list of comments that exist for a specific challenge
    */
  def retrieveComments(challengeId: Long, limit: Int, page: Int): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      Ok(Json.toJson(this.dalManager.task.retrieveComments(List.empty, List(challengeId), List.empty, limit, page)))
    }
  }

  /**
    * Extracts all the comments and returns them in a nice format like csv.
    *
    * @param challengeId The id of the challenge
    * @param limit       limit the number of results
    * @param page        Used for paging
    * @return A csv list of comments for the challenge
    */
  def extractComments(challengeId: Long, limit: Int, page: Int): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      Result(
        header = ResponseHeader(OK, Map(CONTENT_DISPOSITION -> s"attachment; filename=challenge_${challengeId}_comments.csv")),
        body = HttpEntity.Strict(
          ByteString(
            "ProjectID,ChallengeID,TaskID,OSM_UserID,OSM_Username,Comment,TaskLink\n"
          ).concat(ByteString(extractComments(challengeId, limit, page, request.host).mkString("\n"))),
          Some("text/csv; header=present"))
      )
    }
  }

  private def extractComments(challengeId: Long, limit: Int, page: Int, host: String): Seq[String] = {
    val comments = this.dalManager.task.retrieveComments(List.empty, List(challengeId), List.empty, limit, page)
    val urlPrefix = config.getPublicOrigin match {
      case Some(origin) => s"${origin}/"
      case None => s"http://$host/"
    }
    comments.map(comment =>
      s"""${comment.projectId},$challengeId,${comment.taskId},${comment.osm_id},""" +
      s"""${comment.osm_username},"${comment.comment}",${urlPrefix}map/$challengeId/${comment.taskId}""".stripMargin
    )
  }

  /**
    * Extracts all the tasks belonging to the challenges in the project and
    * returns them in a nice format like csv.
    *
    * @param projectId The id of the project
    * @param cId Optional list of challenges
    * @return A csv list of tasks for the project
    */
  def extractAllTaskSummaries(projectId: Long, cId: Option[String]): Action[AnyContent] = {
    var challengeIds:List[Long] = cId match {
      case Some(c) => Utils.toLongList(c).getOrElse(List())
      case None => List()
    }
    if (challengeIds.length == 0) {
      challengeIds = this.dalManager.project.retrieveById(projectId) match {
          case Some(p) => this.dalManager.project.listChildren(-1)(projectId).map(c => c.id)
          case None => throw new NotFoundException(s"Project with id $projectId not found")
        }
    }
    this._extractTaskSummaries(challengeIds, -1, 0, "", "", "", s"project_${projectId}_tasks.csv")
  }

  /**
    * Extracts all the tasks and returns them in a nice format like csv.
    *
    * @param challengeId The id of the challenge
    * @param limit       limit the number of results
    * @param page        Used for paging
    * @return A csv list of tasks for the challenge
    */
  def extractTaskSummaries(challengeId: Long, limit: Int, page: Int,
                           statusFilter: String, reviewStatusFilter: String,
                           priorityFilter: String): Action[AnyContent] = {
    this._extractTaskSummaries(List(challengeId), limit, page, statusFilter,
                               reviewStatusFilter, priorityFilter,
                               s"challenge_${challengeId}_tasks.csv")
  }

  def _extractTaskSummaries(challengeIds: List[Long], limit: Int, page: Int,
                           statusFilter: String, reviewStatusFilter: String,
                           priorityFilter: String, filename: String): Action[AnyContent] = Action.async { implicit request =>

    this.sessionManager.authenticatedRequest { implicit user =>
      val status = if (StringUtils.isEmpty(statusFilter)) {
        None
      } else {
        Some(Utils.split(statusFilter).map(_.toInt))
      }
      val reviewStatus = if (StringUtils.isEmpty(reviewStatusFilter)) {
        None
      } else {
        Some(Utils.split(reviewStatusFilter).map(_.toInt))
      }
      val priority = if (StringUtils.isEmpty(priorityFilter)) {
        None
      } else {
        Some(Utils.split(priorityFilter).map(_.toInt))
      }

      val (tasks, allComments) = this.dalManager.task.retrieveTaskSummaries(challengeIds, limit, page, status, reviewStatus, priority)

      // Setup all exportable properties
      var propsToExportHeaders = Set[String]()
      //var propsToExport = Array[String]()
      challengeIds.foreach(cId => {
        val challenge = this.dal.retrieveById(cId) match {
          case Some(c) => c
          case None => throw new NotFoundException(s"Challenge with id $cId not found")
        }
        challenge.extra.exportableProperties match {
          case Some(ex) =>
            if (!ex.isEmpty) {
              var propsToExport = ex.replaceAll("\\s", "").split(",")
              propsToExport.foreach(pe => propsToExportHeaders += pe)
            }
          case None => // do nothing
        }
      })

      // Find all response property names
      var responseProperties = Set[String]()
      tasks.foreach(
        _.completionResponses match {
          case Some(responses) =>
            Json.parse(responses) match {
              case o: JsObject => o.keys
                for (key <- o.keys) {
                  responseProperties += key.toString()
                }
              case _ => // do nothing
            }
          case None => // do nothing
        }
      )
      var responseHeaders = ""
      for (p <- responseProperties) {
        responseHeaders += "," + "Recorded_" + p
      }

      val seqString = tasks.map(task => {
          var mapper = task.reviewRequestedBy.getOrElse("")
          if (mapper == "") {
            mapper = task.username.getOrElse("")
          }

          val reviewTimeSeconds = task.reviewStartedAt match {
            case Some(startTime) =>
              task.reviewedAt match {
                case Some(endTime) => (endTime.getMillis() - startTime.getMillis()) / 1000
                case _ => ""
              }
            case _ => ""
          }

          // Find matching geojson feature properties
          var propData = ""
          task.geojson match {
            case Some(g) =>
              val taskProps = (Json.parse(g) \\ "properties")(0).as[JsObject]
              for (key <- propsToExportHeaders) {
                (taskProps \ key) match {
                    case value: JsDefined =>
                      var propValue = value.get.toString()
                      propValue = propValue.substring(1, propValue.length() - 1)
                      propData += "," + propValue.replaceAll("\"", "\"\"")
                    case value: JsUndefined => propData += "," + "\"\"" // empty value
                }
              }
            case None => // do nothing
          }

          // Find matching response values to each response property name
          var responseData = ""
          task.completionResponses match {
            case Some(responses) =>
              val responseMap = Json.parse(responses)
              for (key <- responseProperties) {
                (responseMap \ key) match {
                    case value: JsDefined =>
                      var propValue = value.get.toString()
                      if (propValue != "true") {
                        // Strip off ""s
                        propValue = propValue.substring(1, propValue.length() - 1)
                      }
                      responseData += "," + propValue.replaceAll("\"", "\"\"")
                    case vaue: JsUndefined =>
                      responseData += "," + "\"\"" // empty value
                }
              }
            case None => // No responses, all empty values
              for (key <- responseProperties) {
                responseData += "," + "\"\""
              }
          }

          var comments = allComments(task.taskId).replaceAll("\"", "\"\"")

          s"""${task.taskId},${task.parent},"${task.name}","${Task.statusMap.get(task.status).get}",""" +
          s""""${Challenge.priorityMap.get(task.priority).get}",${task.mappedOn.getOrElse("")},""" +
          s"""${Task.reviewStatusMap.get(task.reviewStatus.getOrElse(-1)).get},"${mapper}",""" +
          s""""${task.reviewedBy.getOrElse("")}",${task.reviewedAt.getOrElse("")},"${reviewTimeSeconds}",""" +
          s""""${comments}","${task.bundleId.getOrElse("")}","${task.isBundlePrimary.getOrElse("")}",""" +
          s""""${task.tags.getOrElse("")}"${propData}${responseData}""".stripMargin
        }
      )

      var propsToExportHeaderString = propsToExportHeaders.mkString(",")
      if (!propsToExportHeaderString.isEmpty) {
        propsToExportHeaderString = "," + propsToExportHeaderString
      }
      Result(
        header = ResponseHeader(OK, Map(CONTENT_DISPOSITION -> s"attachment; filename=${filename}")),
        body = HttpEntity.Strict(
          ByteString(
            s"""TaskID,ChallengeID,TaskName,TaskStatus,TaskPriority,MappedOn,ReviewStatus,Mapper,Reviewer,ReviewedAt,ReviewTimeSeconds,Comments,BundleId,IsBundlePrimary,Tags${propsToExportHeaderString}${responseHeaders}\n"""
          ).concat(ByteString(seqString.mkString("\n"))),
          Some("text/csv; header=present"))
      )
    }
  }

  /**
    * Uses the search parameters from the query string to find challenges
    *
    * @param limit limits the amount of results returned
    * @param page  paging mechanism for limited results
    * @return A list of challenges matching the query string parameters
    */
  def extendedFind(limit: Int, page: Int, sort: String, order: String): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.userAwareRequest { implicit user =>
      SearchParameters.withSearch { implicit params =>
        val challenges = this.dal.extendedFind(params, limit, page, sort, order)
        Ok(_insertProjectJSON(challenges))
      }
    }
  }

  /**
   * Fetches the matching parent object and inserts it into the JSON data returned.
   *
   */
  private def _insertProjectJSON(challenges: List[Challenge]): JsValue = {
    if (challenges.isEmpty) {
      Json.toJson(List[JsValue]())
    } else {
      val tags = this.tagDAL.listByChallenges(challenges.map(c => c.id))
      val projects = Some(this.dalManager.project.retrieveListById(-1, 0)(challenges.map(c => c.general.parent)).map(p => p.id -> p).toMap)

      var vpIds = scala.collection.mutable.Set[Long]()
      challenges.map(c => {
        c.general.virtualParents match {
          case Some(vps) =>
            vps.map(vp => vpIds += vp)
          case _ => // do nothing
        }
      })
      val vpObjects = this.dalManager.project.retrieveListById(-1, 0)(vpIds.toList).map(p => p.id -> p).toMap

      val jsonList = challenges.map { c =>
        var updated = Utils.insertIntoJson(Json.toJson(c), Tag.KEY, Json.toJson(tags.getOrElse(c.id, List.empty).map(_.name)))
        val projectJson = Json.toJson(projects.get(c.general.parent)).as[JsObject] - Project.KEY_GROUPS
        updated = Utils.insertIntoJson(updated, Challenge.KEY_PARENT, projectJson, true)

        c.general.virtualParents match {
          case Some(vps) =>
            val vpJson = Some(vps.map(vp => Json.toJson(vpObjects.get(vp)).as[JsObject] - Project.KEY_GROUPS))
            updated = Utils.insertIntoJson(updated, Challenge.KEY_VIRTUAL_PARENTS, vpJson, true)
          case _ => // do nothing
        }
        updated
      }
      Json.toJson(jsonList)
    }
  }

  /**
    * Retrieves a lightweight listing of the challenges in the given project(s).
    *
    * @param projectIds  comma-separated list of projects
    * @param limit       limits the amount of results returned
    * @param page        paging mechanism for limited results
    * @param onlyEnabled determines if results are restricted to enabled challenges projects
    * @return A list of challenges
    */
  def listing(projectIds: String, limit: Int, page: Int, onlyEnabled: Boolean): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      Ok(Json.toJson(this.dal.listing(Utils.toLongList(projectIds), limit, page, onlyEnabled)))
    }
  }

  /**
    * Matches all the tasks to their specific changesets
    *
    * @param challengeId The challenge id
    * @param skipSet     Whether to skip tasks that have already been set
    * @return Just a 200 OK
    */
  def matchChangeSets(challengeId: Long, skipSet: Boolean): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedRequest { implicit user =>
      val challenge = this.dal.retrieveById(challengeId)
      challenge match {
        case Some(c) if user.isSuperUser || c.general.owner == user.osmProfile.id =>
          // TODO might need to loop through the tasks in batches. Pulling every single task could be very expensive
          this.dal.listChildren(-1)(challengeId)
            .filter(t => t.status.get == Task.STATUS_FIXED && (!skipSet || t.changesetId.isEmpty || t.changesetId.get != -1))
            .foreach(t => {
              this.dalManager.task.matchToOSMChangeSet(t, user, false)
            })
          // todo might need to respond only once everything has updated or handle this better
          Ok
        case Some(_) => throw new OAuthNotAuthorizedException("Only owners of the challenge or super users can make this request")
        case _ => throw new NotFoundException(s"Challenge with id $challengeId not found")
      }
    }
  }

  /**
    * Creates or updates a challenge directly from Github. It uses the following files:
    * ${name}_create.json - The json file containing all the information to generate the file
    * ${name}_geojson.json - The geojson used to build the challenge tasks, can be used to update later
    * ${name}_info.md - An information file that will be used to display information about the challenge
    *
    * @param projectId The project that you are building the challenge under
    * @param username  The github username of where the challenge information exists
    * @param repo      The repo that the challenge information exists in
    * @param name      The name of the challenge files.
    * @return A response with the newly created challenge
    */
  def createFromGithub(projectId: Long, username: String, repo: String, name: String, rebuild: Boolean): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedFutureRequest { implicit user =>
      val result = Promise[Result]
      val baseURL = s"https://raw.githubusercontent.com/$username/$repo/master/${name}_";
      this.wsClient.url(s"${baseURL}create.json").get onComplete {
        case Success(response) =>
          try {
            // inject the info link into the challenge
            val challengeJson = Utils.insertIntoJson(
              Utils.insertIntoJson(response.json, "infoLink", s"${baseURL}info.md", false),
              "remoteGeoJson",
              s"${baseURL}geojson_{x}.json",
              true
            )
            val challengeName = (challengeJson \ "name").asOpt[String].getOrElse(name)
            // look for the challenge, if the name exists we will attempt to update the challenge
            val challengeID = this.dal.retrieveByName(challengeName, projectId) match {
              case Some(c) => c.id
              case None => -1
            }
            val updatedBody = this.updateCreateBody(Utils.insertIntoJson(challengeJson, "parent", projectId), user)
            if (challengeID > 0) {
              // if rebuild set to true, remove all the tasks first from the challenge before recreating them
              this.dal.deleteTasks(user, challengeID)
              // if you provide the ID in the post method we will send you to the update path
              this.internalUpdate(updatedBody, user)(challengeID.toString, -1) match {
                case Some(value) => result success Ok(this.inject(value))
                case None => result success NotModified
              }
            } else {
              this.internalCreate(updatedBody, updatedBody.validate[Challenge].get, user) match {
                case Some(value) => result success Ok(this.inject(value))
                case None => result success NotModified
              }
            }
          } catch {
            case e: Throwable => result success MPExceptionUtil.manageException(e)
          }
        case Failure(error) => throw error
      }
      result.future
    }
  }

  override def internalCreate(requestBody: JsValue, element: Challenge, user: User)
                            (implicit c: Option[Connection] = None): Option[Challenge] = {
    var created = super.internalCreate(requestBody, element, user)
    // Fetch challenge fresh from database. There are some fields that are set after creating
    // children (ie. hasSuggestedFixes) and our cached copy does not reflect those changes
    created match {
      case Some(value) => this.dal._retrieveById(false)(value.id)
      case None => created
    }
  }

  /**
    * Classes can override this function to inject values into the object before it is sent along
    * with the response
    *
    * @param obj the object being sent in the response
    * @return A Json representation of the object
    */
  override def inject(obj: Challenge)(implicit request: Request[Any]) : JsValue = {
    val tags = tagDAL.listByChallenge(obj.id)
    val withTagsJson = Utils.insertIntoJson(Json.toJson(obj), Tag.KEY, Json.toJson(tags.map(_.name)))
    obj.general.challengeType match {
      case Actions.ITEM_TYPE_SURVEY =>
        // if no answers provided with Challenge, then provide the default answers
        Utils.insertIntoJson(withTagsJson, Challenge.KEY_ANSWER, Json.toJson(this.dalManager.survey.getAnswers(obj.id)))
      case _ => withTagsJson
    }
  }

  /**
    * This function allows sub classes to modify the body, primarily this would be used for inserting
    * default elements into the body that shouldn't have to be required to create an object.
    *
    * @param body The incoming body from the request
    * @return
    */
  override def updateCreateBody(body: JsValue, user: User): JsValue = {
    var jsonBody = super.updateCreateBody(body, user)
    jsonBody = Utils.insertIntoJson(jsonBody, "owner", user.osmProfile.id, true)(LongWrites)
    jsonBody = Utils.insertIntoJson(jsonBody, "enabled", true)(BooleanWrites)
    jsonBody = Utils.insertIntoJson(jsonBody, "deleted", false)(BooleanWrites)
    jsonBody = Utils.insertIntoJson(jsonBody, "challengeType", Actions.ITEM_TYPE_CHALLENGE)(IntWrites)
    jsonBody = Utils.insertIntoJson(jsonBody, "difficulty", Challenge.DIFFICULTY_NORMAL)(IntWrites)
    jsonBody = Utils.insertIntoJson(jsonBody, "featured", false)(BooleanWrites)
    jsonBody = Utils.insertIntoJson(jsonBody, "hasSuggestedFixes", false)(BooleanWrites)
    jsonBody = Utils.insertIntoJson(jsonBody, "checkinComment", "")(StringWrites)
    jsonBody = Utils.insertIntoJson(jsonBody, "checkinSource", "")(StringWrites)
    jsonBody = Utils.insertIntoJson(jsonBody, "defaultPriority", Challenge.PRIORITY_HIGH)(IntWrites)
    jsonBody = Utils.insertIntoJson(jsonBody, "defaultZoom", Challenge.DEFAULT_ZOOM)(IntWrites)
    jsonBody = Utils.insertIntoJson(jsonBody, "minZoom", Challenge.MIN_ZOOM)(IntWrites)
    jsonBody = Utils.insertIntoJson(jsonBody, "maxZoom", Challenge.MAX_ZOOM)(IntWrites)
    jsonBody = Utils.insertIntoJson(jsonBody, "updateTasks", false)(BooleanWrites)
    // if we can't find the parent ID, just use the user's default project instead
    (jsonBody \ "parent").asOpt[Long] match {
      case Some(v) => jsonBody
      case None => Utils.insertIntoJson(jsonBody, "parent", this.dalManager.user.getHomeProject(user).id)
    }
  }

  /**
    * Creates a challenge gzipped package that contains the JSON to recreate the challenge, geojson to
    * recreate all the tasks, challenge info md file, metrics file at the time this function is executed,
    * and all comments that have currently been made against tasks in this challenge.
    *
    * @param challengeId The id of the challenge to extract
    * @return The
    */
  def extractPackage(challengeId: Long): Action[AnyContent] = Action.async { implicit request =>
    this.sessionManager.authenticatedFutureRequest { implicit user =>
      this.dal.retrieveById(challengeId) match {
        case Some(c) =>
          implicit val actionSummaryWrites = Json.writes[ActionSummary]
          implicit val challengeSummaryWrites = Json.writes[ChallengeSummary]
          val files: Map[String, String] = Map(
            "challenge.json" -> Json.prettyPrint(Json.toJson(c)),
            "lineByLine.geojson" -> this.dal.getLineByLineChallengeGeometry(challengeId).values.mkString("\n"),
            "geometry.geojson" -> this.dal.getChallengeGeometry(challengeId),
            "comments.csv" -> this.extractComments(challengeId, -1, 0, request.host).mkString("\n"),
            "metrics.csv" -> Json.prettyPrint(Json.toJson(this.dalManager.data.getChallengeSummary(challengeId = Some(challengeId))))
          )
          val out = new ByteArrayOutputStream()

          val zip = new ZipOutputStream(out)
          files.foreach { f =>
            zip.putNextEntry(new ZipEntry(f._1))
            val in = new BufferedInputStream(new ByteArrayInputStream(f._2.getBytes))
            var b = in.read
            while (b > -1) {
              zip.write(b)
              b = in.read
            }
            in.close()
            zip.closeEntry()
          }
          zip.close()
          val response = ByteString(out.toByteArray)
          out.close()
          Future {
            Result(
              header = ResponseHeader(OK, Map.empty),
              body = HttpEntity.Strict(response, Some("application/gzip"))
            )
          }
        case None => Future {
          NotFound
        }
      }
    }
  }

  /**
    * Clones a challenge with a new name
    *
    * @param itemId  The item id of the challenge you want to clone
    * @param newName The new name of the cloned challenge
    * @return The newly created cloned challenge
    */
  def cloneChallenge(itemId: Long, newName: String): Action[AnyContent] = Action.async { implicit request =>
    sessionManager.authenticatedRequest { implicit user =>
      dalManager.challenge.retrieveById(itemId) match {
        case Some(c) =>
          permission.hasWriteAccess(ProjectType(), user)(c.general.parent)
          val clonedChallenge = c.copy(id = -1, name = newName)
          Ok(Json.toJson(this.dal.insert(clonedChallenge, user)))
        case None =>
          throw new NotFoundException(s"No challenge found to clone matching the given id [$itemId]")
      }
    }
  }

  /**
    * Rebuilds a challenge if it uses a remote geojson or overpass query to generate it's tasks
    *
    * @param challengeId     The id of the challenge
    * @param removeUnmatched Whether to first remove incomplete tasks prior to processing
    *                        updated source data
    * @return A 200 status OK
    */
  def rebuildChallenge(challengeId: Long, removeUnmatched: Boolean): Action[AnyContent] = Action.async { implicit request =>
    sessionManager.authenticatedRequest { implicit user =>
      dalManager.challenge.retrieveById(challengeId) match {
        case Some(c) =>
          permission.hasWriteAccess(ProjectType(), user)(c.general.parent)
          c.status match {
            case Some(Challenge.STATUS_DELETING_TASKS) =>
              throw new InvalidException("Challenge cannot be rebuilt while undergoing bulk task deletion")
            case Some(Challenge.STATUS_BUILDING) =>
              throw new InvalidException("Task build is already in progress for this challenge")
            case _ => // just ignore
          }

          challengeProvider.rebuildTasks(user, c, removeUnmatched)
          Ok
        case None => throw new NotFoundException(s"No challenge found with id $challengeId")
      }
    }
  }

  def addTasksToChallenge(challengeId: Long): Action[AnyContent] = Action.async { implicit request =>
    sessionManager.authenticatedRequest { implicit user =>
      dalManager.challenge.retrieveById(challengeId) match {
        case Some(challenge) =>
          permission.hasObjectWriteAccess(challenge, user)
          challenge.status match {
            case Some(Challenge.STATUS_DELETING_TASKS) =>
              throw new InvalidException("Tasks cannot be added while challenge is undergoing bulk task deletion")
            case Some(Challenge.STATUS_BUILDING) =>
              throw new InvalidException("Tasks cannot be added while challenge is being built")
            case _ => // just ignore
          }

          request.body.asText match {
            case Some(bodyText) =>
              challengeProvider.createTasksFromJson(user, challenge, bodyText)
              NoContent
            case None =>
              request.body.asJson match {
                case Some(bodyJson) =>
                  challengeProvider.createTasksFromJson(user, challenge, bodyJson.toString())
                  NoContent
                case None =>
                  throw new InvalidException("No json data provided to create new tasks from")
              }
          }
        case None =>
          throw new NotFoundException(s"No challenge found with id $challengeId")
      }
    }
  }

  def addTasksToChallengeFromFile(challengeId: Long, lineByLine: Boolean, removeUnmatched: Boolean,
                                  dataOriginDate: Option[String] = None): Action[MultipartFormData[Files.TemporaryFile]] =
    Action.async(parse.multipartFormData) { implicit request => {
      sessionManager.authenticatedRequest { implicit user =>
        dalManager.challenge.retrieveById(challengeId) match {
          case Some(c) =>
            permission.hasObjectWriteAccess(c, user)
            c.status match {
              case Some(Challenge.STATUS_DELETING_TASKS) =>
                throw new InvalidException("Tasks cannot be added while challenge is undergoing bulk task deletion")
              case Some(Challenge.STATUS_BUILDING) =>
                throw new InvalidException("Tasks cannot be added while challenge is being built")
              case _ => // just ignore
            }

            request.body.file("json") match {
              case Some(f) if StringUtils.isNotEmpty(f.filename) =>
                if (removeUnmatched) {
                  dalManager.challenge.removeIncompleteTasks(user)(challengeId)
                }

                // todo this should probably be streamed instead of all pulled into memory
                val sourceData = Source.fromFile(f.ref.getAbsoluteFile).getLines()
                if (lineByLine) {
                  sourceData.foreach(challengeProvider.createTaskFromJson(user, c, _))
                } else {
                  challengeProvider.createTasksFromJson(user, c, sourceData.mkString)
                }
                dataOriginDate match {
                  case Some(d) =>
                    dalManager.challenge.markTasksRefreshed(true, Some(new DateTime(d)))(challengeId)
                  case _ => // do nothing
                }
                NoContent
              case _ =>
                throw new InvalidException(s"No json uploaded with request to add tasks from")
            }
          case None =>
            throw new NotFoundException(s"No challenge found with id $challengeId")
        }
      }
    }
    }

  /**
    * Moves a challenge from one project to another. This requires admin access on both projects
    *
    * @param newProjectId The new project to move the challenge too
    * @param challengeId  The challenge that you are moving
    * @return Ok with no message
    */
  def moveChallenge(newProjectId: Long, challengeId: Long): Action[AnyContent] = Action.async { implicit request =>
    sessionManager.authenticatedRequest { implicit user =>
      Ok(Json.toJson(dalManager.challenge.moveChallenge(newProjectId, challengeId, user)))
    }
  }
}
