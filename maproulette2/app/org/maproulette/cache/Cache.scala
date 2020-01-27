package org.maproulette.cache

import org.joda.time.{LocalDateTime, Seconds}

/**
  * @author mcuthbert
  */
case class BasicInnerValue[Key, Value](key: Key, value: Value, accessTime: LocalDateTime, localExpiry: Option[Int] = None)

trait Cache[Key, Value <: CacheObject[Key]] {

  implicit val cacheLimit:Int
  implicit val cacheExpiry:Int

  /**
    * Adds an object to the cache, if cache limit has been reached, then will remove the oldest
    * accessed item in the cache
    *
    * @param obj         The object to add to the cache
    * @param localExpiry You can add a custom expiry to a specific element in seconds
    * @return The object put in the cache, or None if it could not be placed in the cache
    */
  def addObject(obj: Value, localExpiry: Option[Int] = None): Option[Value] = synchronized {
    this.add(obj.id, obj, localExpiry)
  }

  /**
    * Checks if an item is cached or not
    *
    * @param key The id of the object to check to see if it is in the cache
    * @return true if the item is found in the cache
    */
  def isCached(key: Key): Boolean

  /**
    * Fully clears the cache, this may not be applicable for non basic in memory caches
    */
  def clear(): Unit

  /**
    * The current size of the cache
    *
    * @return
    */
  def size: Int

  /**
    * True size is a little bit more accurate than size, however the performance will be a bit slower
    * as this size will loop through all the objects in the cache and expire out any items that have
    * expired. Thereby giving the true size at the end.
    *
    * @return
    */
  def trueSize: Int

  /**
    * Adds an object to the cache, if cache limit has been reached, then will remove the oldest
    * accessed item in the cache
    *
    * @param key   The object to add to the cache
    * @param value You can add a custom expiry to a specific element in seconds
    * @return The object put in the cache, or None if it could not be placed in the cache
    */
  def add(key: Key, value: Value, localExpiry: Option[Int] = None): Option[Value]

  /**
    * Gets an object from the cache
    *
    * @param id The id of the object you are looking for
    * @return The object from the cache, None if not found
    */
  def get(id: Key): Option[Value] = synchronized {
    this.innerGet(id) match {
      case Some(value) =>
        if (isExpired(value)) {
          None
        } else {
          // because it has been touched, we need to update the accesstime
          add(id, value.value)
          Some(value.value)
        }
      case None => None
    }
  }

  /**
    * Finds an object from the cache based on the name instead of the id
    *
    * @param name The name of the object you wish to find
    * @return The object from the cache, None if not found
    */
  def find(name: String): Option[Value]

  /**
    * Remove an object from the cache based on the name
    *
    * @param name The name of the object to be removed
    * @return The object removed from the cache, or None if it could not be removed from the cache,
    *         or was not originally in the cache
    */
  def remove(name: String): Option[Value]

  def remove(id: Key) : Option[Value]

  protected def innerGet(key: Key): Option[BasicInnerValue[Key, Value]]

  protected def isExpiredByKey(key: Key): Boolean = synchronized {
    this.innerGet(key) match {
      case Some(value) => isExpired(value)
      case None => true
    }
  }

  /**
    * Checks to see if the item has expired and should be removed from the cache. If it finds the
    * item and it has expired it will automatically remove it from the cache.
    *
    * @param value The value to check in the cache
    * @return true if it doesn't exist or has expired
    */
  protected def isExpired(value: BasicInnerValue[Key, Value]): Boolean = synchronized {
    val currentTime = new LocalDateTime()
    val itemExpiry = value.localExpiry match {
      case Some(v) => v
      case None => cacheExpiry
    }
    if (currentTime.isAfter(value.accessTime.plus(Seconds.seconds(itemExpiry)))) {
      remove(value.key)
      true
    } else {
      false
    }
  }
}
