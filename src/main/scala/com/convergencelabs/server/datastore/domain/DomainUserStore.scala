package com.convergencelabs.server.datastore.domain

import com.orientechnologies.orient.core.db.OPartitionedDatabasePool
import org.json4s.NoTypeHints
import org.json4s.jackson.Serialization
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.json4s.jackson.Serialization._
import com.orientechnologies.orient.core.sql.OCommandSQL
import com.orientechnologies.orient.core.sql.query.OSQLSynchQuery
import com.orientechnologies.orient.core.record.impl.ODocument
import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import com.orientechnologies.orient.core.metadata.schema.OType
import scala.collection.immutable.HashMap

class DomainUserStore(dbPool: OPartitionedDatabasePool) {
  
private[this] implicit val formats = Serialization.formats(NoTypeHints)

  
  def createDomainUser(domainUser: DomainUser): Boolean = {
    // FIXME should take an password: Option[String] for the PW
    // Should start a tx and create two records.  That password can use
    // password.getOrElse("") when storing it. 
    val db = dbPool.acquire()
    val doc = db.newInstance("user")
    doc.fromJSON(write(domainUser))
    db.save(doc)
    db.close()
    true
  }

  //FIXME need this method  
  //def setDomainUserPassword(username: String, password: String): Unit = {
  //}

  //FIXME need this method  
  //def validateCredentials(username: String, password: String): Boolean = {
  //}

  def deleteDomainUser(uid: String): Unit = {
    val db = dbPool.acquire()
    val command = new OCommandSQL("DELETE FROM user WHERE uid = :uid")
    val params: java.util.Map[String, String] = HashMap("uid" -> uid)
    db.command(command).execute(params)
  }

  def updateDomainUser(domainUser: DomainUser): Unit = {
    val db = dbPool.acquire()
    val updatedDoc = db.newInstance("user")
    updatedDoc.fromJSON(write(domainUser))

    val query = new OSQLSynchQuery[ODocument]("SELECT FROM user WHERE uid = :uid")
    val params: java.util.Map[String, String] = HashMap("uid" -> domainUser.uid)
    val result: java.util.List[ODocument] = db.command(query).execute(params)

    result.asScala.toList match {
      case doc :: rest => {
        doc.merge(updatedDoc, false, false)
        db.save(doc)
      }
      case Nil => ???
    }
  }

  def getDomainUserByUid(uid: String): Option[DomainUser] = {
    val db = dbPool.acquire()
    val query = new OSQLSynchQuery[ODocument]("SELECT FROM user WHERE uid = :uid")
    val params: java.util.Map[String, String] = HashMap("uid" -> uid)
    val result: java.util.List[ODocument] = db.command(query).execute(params)

    result.asScala.toList match {
      case doc :: rest => Some(DomainUser(uid, doc.field("username"), doc.field("firstName"), doc.field("lastName"), doc.field("emails", OType.EMBEDDEDLIST)))
      case Nil         => None
    }
  }

  def getDomainUsersByUids(uids: List[String]): List[DomainUser] = {
    val db = dbPool.acquire()
    val query = new OSQLSynchQuery[ODocument]("SELECT FROM user WHERE uid in :uids")
    val params: java.util.Map[String, Any] = HashMap("uids" -> uids)
    val result: java.util.List[ODocument] = db.command(query).execute(params)
    result.asScala.toList.map { doc => DomainUser(doc.field("uid"), doc.field("username"), doc.field("firstName"), doc.field("lastName"), doc.field("emails", OType.EMBEDDEDLIST)) }
  }

  def getDomainUserByUsername(username: String): Option[DomainUser] = {
    val db = dbPool.acquire()
    val query = new OSQLSynchQuery[ODocument]("SELECT FROM user WHERE username = :username")
    val params: java.util.Map[String, Any] = HashMap("username" -> username)
    val result: java.util.List[ODocument] = db.command(query).execute(params)

    result.asScala.toList match {
      case doc :: rest => Some(DomainUser(doc.field("uid"), username, doc.field("firstName"), doc.field("lastName"), doc.field("emails", OType.EMBEDDEDLIST)))
      case Nil         => None
    }
  }

  def getDomainUsersByUsername(usernames: List[String]): List[DomainUser] = {
    val db = dbPool.acquire()
    val query = new OSQLSynchQuery[ODocument]("SELECT FROM user WHERE username in :usernames")
    val params: java.util.Map[String, Any] = HashMap("usernames" -> usernames)
    val result: java.util.List[ODocument] = db.command(query).execute(params)
    result.asScala.toList.map { doc => DomainUser(doc.field("uid"), doc.field("username"), doc.field("firstName"), doc.field("lastName"), doc.field("emails", OType.EMBEDDEDLIST)) }
  }

  def getDomainUserByEmail(email: String): Option[DomainUser] = {
    val db = dbPool.acquire()
    val query = new OSQLSynchQuery[ODocument]("SELECT FROM user WHERE email = :email")
    val params: java.util.Map[String, Any] = HashMap("email" -> email)
    val result: java.util.List[ODocument] = db.command(query).execute(params)

    result.asScala.toList match {
      case doc :: rest => Some(DomainUser(doc.field("uid"), doc.field("username"), doc.field("firstName"), doc.field("lastName"), doc.field("emails", OType.EMBEDDEDLIST)))
      case Nil         => None
    }
  }

  def getDomainUsersByEmail(emails: List[String]): List[DomainUser] = {
    val db = dbPool.acquire()
    val query = new OSQLSynchQuery[ODocument]("SELECT FROM user WHERE email in :emails")
    val params: java.util.Map[String, Any] = HashMap("emails" -> emails)
    val result: java.util.List[ODocument] = db.command(query).execute(params)
    result.asScala.toList.map { doc => DomainUser(doc.field("uid"), doc.field("username"), doc.field("firstName"), doc.field("lastName"), doc.field("emails", OType.EMBEDDEDLIST)) }
  }

  def domainUserExists(username: String): Boolean = {
    val db = dbPool.acquire()
    val query = new OSQLSynchQuery[ODocument]("SELECT FROM user WHERE username = :username")
    val params: java.util.Map[String, Any] = HashMap("username" -> username)
    val result: java.util.List[ODocument] = db.command(query).execute(params)

    result.asScala.toList match {
      case doc :: rest => true
      case Nil         => false
    }
  }

  def getAllDomainUsers(): List[DomainUser] = {
    val db = dbPool.acquire()
    val query = new OSQLSynchQuery[ODocument]("SELECT FROM user")
    val result: java.util.List[ODocument] = db.command(query).execute()
    result.asScala.toList.map { doc => DomainUser(doc.field("uid"), doc.field("username"), doc.field("firstName"), doc.field("lastName"), doc.field("emails", OType.EMBEDDEDLIST)) }
  }
}

case class DomainUser(uid: String, username: String, firstName: String, lastName: String, emails: List[String])