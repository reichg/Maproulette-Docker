// Copyright (C) 2019 MapRoulette contributors (see CONTRIBUTORS.md).
// Licensed under the Apache License, Version 2.0 (see LICENSE).
package org.maproulette.models

import org.joda.time.DateTime

/**
  * @author cuthbertm
  */
case class Lock(lockedTime: Option[DateTime], itemType: Int, itemId: Long, userId: Long)

object Lock {
  def emptyLock: Lock = Lock(None, -1, -1, -1)
}
