package com.convergencelabs.server.datastore

import grizzled.slf4j.Logging
import com.orientechnologies.orient.core.db.OPartitionedDatabasePool
import com.convergencelabs.server.domain.datastore.ConvergenceDeltaHistory
import com.convergencelabs.server.domain.datastore.ConvergenceDelta
import com.convergencelabs.server.domain.datastore.DomainDeltaHistory
import com.convergencelabs.server.domain.datastore.DomainDelta
import scala.util.Try
import com.convergencelabs.server.datastore.DeltaHistoryStore._
import com.orientechnologies.orient.core.db.record.OIdentifiable
import com.orientechnologies.orient.core.db.document.ODatabaseDocumentTx
import com.orientechnologies.orient.core.record.impl.ODocument
import java.time.Instant
import com.convergencelabs.server.domain.DomainFqn
import com.orientechnologies.orient.core.index.OCompositeKey
import collection.JavaConverters._

object DeltaHistoryStore {
  val ConvergenceDeltaClass = "ConvergenceDelta"
  val ConvergernceDeltaHistoryClass = "ConvergenceDeltaHistory"

  val ConvergenceDeltaIndex = "ConvergenceDelta.deltaNo"
  val ConvergenceDeltaHistoryIndex = "ConvergenceDeltaHistory.delta"

  val DomainDeltaClass = "ConvergenceDelta"
  val DomainDeltaHistoryClass = "ConvergenceDeltaHistory"

  val DomainIndex = "Domain.namespace_id"
  val DomainDeltaIndex = "DomainDelta.deltaNo"
  val DomainDeltaHistoryIndex = "DomainDeltaHistory.delta"

  object Fields {
    val DeltaNo = "deltaNo"
    val Value = "value"

    val Domain = "domain"
    val Delta = "delta"
    val Status = "status"
    val Message = "message"
    val Date = "date"
  }
}

class DeltaHistoryStore(dbPool: OPartitionedDatabasePool) extends AbstractDatabasePersistence(dbPool) with Logging {

  def saveConvergenceDeltaHistory(deltaHistory: ConvergenceDeltaHistory): Try[Unit] = tryWithDb { db =>
    val ConvergenceDeltaHistory(delta, status, message, date) = deltaHistory

    // Validate delta exists or create it
    val deltaIndex = db.getMetadata.getIndexManager.getIndex(ConvergenceDeltaIndex)
    if (deltaIndex.contains(delta.deltaNo)) {
      saveConvergenceDelta(delta, db)
    }

    val deltaORID = deltaIndex.get(delta.deltaNo).asInstanceOf[OIdentifiable].getIdentity

    // Remove old history record
    val deltaHistoryIndex = db.getMetadata.getIndexManager.getIndex(ConvergenceDeltaHistoryIndex)
    if (deltaHistoryIndex.contains(deltaORID)) {
      val deltaHistoryORID = deltaHistoryIndex.get(deltaORID).asInstanceOf[OIdentifiable].getIdentity
      db.delete(deltaHistoryORID)
    }

    val doc = db.newInstance(ConvergernceDeltaHistoryClass)
    doc.field(Fields.Delta, deltaORID)
    doc.field(Fields.Status, status)
    doc.field(Fields.Message, message)
    doc.field(Fields.Date, date.toEpochMilli())
    doc.save()
    ()
  }

  def getConvergenceDeltaHistory(deltaNo: Int): Try[Option[ConvergenceDeltaHistory]] = tryWithDb { db =>
    val deltaIndex = db.getMetadata.getIndexManager.getIndex(ConvergenceDeltaIndex)
    val deltaHistoryIndex = db.getMetadata.getIndexManager.getIndex(ConvergenceDeltaHistoryIndex)

    if (deltaIndex.contains(deltaNo)) {
      val deltaORID = deltaIndex.get(deltaNo).asInstanceOf[OIdentifiable].getIdentity
      if (deltaHistoryIndex.contains(deltaORID)) {
        val doc: ODocument = deltaHistoryIndex.get(deltaORID).asInstanceOf[OIdentifiable].getRecord.asInstanceOf[ODocument]
        val deltaDoc: ODocument = doc.field(Fields.Delta)

        val deltaNo: Int = deltaDoc.field(Fields.DeltaNo)
        val value: String = deltaDoc.field(Fields.Value)

        val status: String = doc.field(Fields.Status)
        val message: Option[String] = Option(doc.field(Fields.Message))
        val date: Long = doc.field(Fields.Message)

        val delta = ConvergenceDelta(deltaNo, value)
        Some(ConvergenceDeltaHistory(delta, status, message, Instant.ofEpochMilli(date)))
      }
    }
    None
  }

  def saveDomainDeltaHistory(deltaHistory: DomainDeltaHistory): Try[Unit] = tryWithDb { db =>
    val DomainDeltaHistory(domain, delta, status, message, date) = deltaHistory

    // Validate domain exists
    val domainKey = new OCompositeKey(List(domain.namespace, domain.domainId).asJava)
    val domainIndex = db.getMetadata.getIndexManager.getIndex(DomainIndex)
    if (!domainIndex.contains(domainKey)) {
      // TODO: Handle Domain Does not exist
    }

    // Validate delta exists or create it
    val deltaIndex = db.getMetadata.getIndexManager.getIndex(DomainDeltaIndex)
    if (deltaIndex.contains(delta.deltaNo)) {
      saveDomainDelta(delta, db)
    }

    val deltaORID = deltaIndex.get(delta.deltaNo).asInstanceOf[OIdentifiable].getIdentity
    val domainORID = domainIndex.get(domainKey).asInstanceOf[OIdentifiable].getIdentity

    // Remove old history record
    val deltaHistoryKey = new OCompositeKey(List(deltaORID, domainORID).asJava)
    val deltaHistoryIndex = db.getMetadata.getIndexManager.getIndex(DomainDeltaHistoryIndex)
    if (deltaHistoryIndex.contains(deltaHistoryKey)) {
      val deltaHistoryORID = deltaHistoryIndex.get(deltaHistoryKey).asInstanceOf[OIdentifiable].getIdentity
      db.delete(deltaHistoryORID)
    }

    val doc = db.newInstance(DomainDeltaHistoryClass)
    doc.field(Fields.Domain, domainORID)
    doc.field(Fields.Delta, deltaORID)
    doc.field(Fields.Status, status)
    doc.field(Fields.Message, message)
    doc.field(Fields.Date, date.toEpochMilli())
    doc.save()
    ()
  }

  def getDomainDeltaHistory(domainFqn: DomainFqn, deltaNo: Int): Try[Option[DomainDeltaHistory]] = tryWithDb { db =>
    val DomainFqn(namespace, domainId) = domainFqn

    val deltaIndex = db.getMetadata.getIndexManager.getIndex(DomainDeltaIndex)
    val deltaHistoryIndex = db.getMetadata.getIndexManager.getIndex(DomainDeltaHistoryIndex)

    if (deltaIndex.contains(deltaNo)) {
      val deltaORID = deltaIndex.get(deltaNo).asInstanceOf[OIdentifiable].getIdentity

      val domainKey = new OCompositeKey(List(namespace, domainId).asJava)
      val domainIndex = db.getMetadata.getIndexManager.getIndex(DomainIndex)
      if (!domainIndex.contains(domainKey)) {
        val domainORID = deltaIndex.get(domainKey).asInstanceOf[OIdentifiable].getIdentity

        if (deltaHistoryIndex.contains(deltaORID)) {
          val doc: ODocument = deltaHistoryIndex.get(deltaORID).asInstanceOf[OIdentifiable].getRecord.asInstanceOf[ODocument]
          val deltaDoc: ODocument = doc.field(Fields.Delta)

          val deltaNo: Int = deltaDoc.field(Fields.DeltaNo)
          val value: String = deltaDoc.field(Fields.Value)

          val status: String = doc.field(Fields.Status)
          val message: Option[String] = Option(doc.field(Fields.Message))
          val date: Long = doc.field(Fields.Message)

          val delta = DomainDelta(deltaNo, value)
          Some(DomainDeltaHistory(domainFqn, delta, status, message, Instant.ofEpochMilli(date)))
        }
      }
    }
    None
  }

  private[this] def saveConvergenceDelta(delta: ConvergenceDelta, db: ODatabaseDocumentTx): Unit = {
    val ConvergenceDelta(deltaNo, value) = delta
    val doc = db.newInstance(ConvergenceDeltaClass)
    doc.field(Fields.DeltaNo, deltaNo)
    doc.field(Fields.Value, value)
    doc.save()
    ()
  }

  private[this] def saveDomainDelta(delta: DomainDelta, db: ODatabaseDocumentTx): Unit = {
    val DomainDelta(deltaNo, value) = delta
    val doc = db.newInstance(DomainDeltaClass)
    doc.field(Fields.DeltaNo, deltaNo)
    doc.field(Fields.Value, value)
    doc.save()
    ()
  }
}