package org.maproulette.utils

import com.google.inject.util.Providers
import org.joda.time.DateTime
import org.maproulette.data.{ActionManager, DataManager, StatusActionManager}
import org.maproulette.models._
import org.maproulette.models.dal._
import org.maproulette.permissions.Permission
import org.maproulette.session._
import org.maproulette.session.dal.{UserDAL, UserGroupDAL}
import org.mockito.ArgumentMatchers.{eq => eqM, _}
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar
import play.api.db.Databases
import play.api.libs.oauth.RequestToken

/**
  * @author mcuthbert
  */
trait TestSpec extends MockitoSugar {

  val testDb = Databases.inMemory()

  // 3x Groups (Admin, Write, Read)
  val adminGroup = Group(1, "Mocked_1_Admin", 1, Group.TYPE_ADMIN)
  val writeGroup = Group(2, "Mocked_1_Write", 1, Group.TYPE_WRITE_ACCESS)
  val readGroup = Group(3, "Mocked_1_Read", 1, Group.TYPE_READ_ONLY)

  //projects
  val project1 = Project(1, 101, "Mocked_1", DateTime.now(), DateTime.now(), None,
    List(adminGroup, writeGroup, readGroup)
  )

  //challenges
  val challenge1 = Challenge(1, "Challenge1", DateTime.now(), DateTime.now(), None, false, None,
    ChallengeGeneral(101, 1, ""),
    ChallengeCreation(),
    ChallengePriority(),
    ChallengeExtra()
  )

  //surveys
  val survey1 = Challenge(1, "Survey1", DateTime.now(), DateTime.now(), None, false, None,
    ChallengeGeneral(101, 1, ""),
    ChallengeCreation(),
    ChallengePriority(),
    ChallengeExtra()
  )

  //tasks
  val task1 = Task(1, "Task1", DateTime.now(), DateTime.now(), 1, None, None, "")

  val tagDAL = mock[TagDAL]
  when(tagDAL.retrieveById(0)).thenReturn(None)
  when(tagDAL.retrieveById(1)).thenReturn(Some(Tag(1, "Tag")))

  val taskDAL = mock[TaskDAL]
  when(taskDAL.retrieveById(0)).thenReturn(None)
  when(taskDAL.retrieveById(1)).thenReturn(Some(task1))
  when(taskDAL.retrieveRootObject(eqM(Right(task1)), any())(any())).thenReturn(Some(project1))

  val challengeDAL = mock[ChallengeDAL]
  when(challengeDAL.retrieveById(0)).thenReturn(None)
  when(challengeDAL.retrieveById(1)).thenReturn(Some(challenge1))
  when(challengeDAL.retrieveRootObject(eqM(Right(challenge1)), any())(any())).thenReturn(Some(project1))

  val virtualChallengeDAL = mock[VirtualChallengeDAL]
  when(virtualChallengeDAL.retrieveById(0)).thenReturn(None)
  when(virtualChallengeDAL.retrieveById(1)).thenReturn(Some(VirtualChallenge(1, "VChallenge", DateTime.now(), DateTime.now(), None, 101, SearchParameters(), DateTime.now())))

  val surveyDAL = mock[SurveyDAL]
  when(surveyDAL.retrieveById(0)).thenReturn(None)
  when(surveyDAL.retrieveById(1)).thenReturn(Some(survey1))

  val projectDAL = mock[ProjectDAL]
  when(projectDAL.retrieveById(0)).thenReturn(None)
  when(projectDAL.retrieveById(1)).thenReturn(Some(project1))

  val userDAL = mock[UserDAL]
  when(userDAL.retrieveById(-999)).thenReturn(Some(User.superUser))
  when(userDAL.retrieveById(-1)).thenReturn(Some(User.guestUser))
  when(userDAL.retrieveById(0)).thenReturn(None)
  when(userDAL.retrieveById(1)).thenReturn(Some(User(1, DateTime.now(), DateTime.now(),
    OSMProfile(1, "AdminUser", "", "", Location(0, 0), DateTime.now(), RequestToken("", "")),
    List(adminGroup)))
  )
  when(userDAL.retrieveById(2)).thenReturn(Some(User(2, DateTime.now(), DateTime.now(),
    OSMProfile(2, "WriteUser", "", "", Location(0, 0), DateTime.now(), RequestToken("", "")),
    List(writeGroup)))
  )
  when(userDAL.retrieveById(3)).thenReturn(Some(User(3, DateTime.now(), DateTime.now(),
    OSMProfile(3, "ReadUser", "", "", Location(0, 0), DateTime.now(), RequestToken("", "")),
    List(readGroup)))
  )
  when(userDAL.retrieveById(100)).thenReturn(Some(User(100, DateTime.now(), DateTime.now(),
    OSMProfile(101, "DefaultOwner", "", "", Location(0, 0), DateTime.now(), RequestToken("", "")),
    List.empty //generally an owner would have to be in an admin group, but here we are making sure the owner permissions are respective regardless or group or lack there of
  )))

  val userGroupDAL = mock[UserGroupDAL]
  when(userGroupDAL.getGroup(0)).thenReturn(None)
  when(userGroupDAL.getGroup(1)).thenReturn(Some(adminGroup))
  when(userGroupDAL.getGroup(2)).thenReturn(Some(writeGroup))
  when(userGroupDAL.getGroup(3)).thenReturn(Some(readGroup))


  val actionManager = mock[ActionManager]
  val dataManager = mock[DataManager]
  val statusActionManager = mock[StatusActionManager]
  var notificationDAL = mock[NotificationDAL]
  val dalManager = new DALManager(tagDAL, taskDAL, challengeDAL, virtualChallengeDAL,
    surveyDAL, projectDAL, userDAL, userGroupDAL, notificationDAL, actionManager, dataManager,
    statusActionManager)
  val permission = new Permission(Providers.of[DALManager](dalManager))
}
