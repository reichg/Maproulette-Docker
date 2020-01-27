// Copyright (C) 2019 MapRoulette contributors (see CONTRIBUTORS.md).
// Licensed under the Apache License, Version 2.0 (see LICENSE).
package org.maproulette.models.dal

import java.sql.Connection

import anorm._
import org.maproulette.models.BaseObject
import org.maproulette.session.User

/**
  * @author mcuthbert
  */
trait OwnerMixin[T <: BaseObject[_]] {
  this: BaseDAL[_, T] =>

  /**
    * Changes the owner of the object
    *
    * @param objectId  The id of the object to change the owner
    * @param newUserId The new users id
    * @param user      The user making the request
    */
  def changeOwner(objectId: Long, newUserId: Long, user: User)(implicit c: Option[Connection] = None): Unit = {
    // for now only super users can change the owners
    this.permission.hasSuperAccess(user)
    this.withMRConnection { implicit c =>
      SQL"""UPDATE $tableName SET owner_id = $newUserId WHERE id = $objectId""".executeUpdate()
    }
  }
}
