package com.convergencelabs.server.datastore.domain

import com.orientechnologies.orient.core.db.OPartitionedDatabasePool
import com.orientechnologies.orient.core.db.document.ODatabaseDocumentTx
import com.orientechnologies.orient.core.db.tool.ODatabaseImport
import com.orientechnologies.orient.core.command.OCommandOutputListener
import com.orientechnologies.common.log.OLogManager
import com.convergencelabs.server.datastore.DatabaseProvider
import com.convergencelabs.server.db.schema.DeltaCategory
import com.convergencelabs.server.db.schema.ConvergenceSchemaManager
import com.convergencelabs.server.db.schema.TestingSchemaManager
import java.util.concurrent.atomic.AtomicInteger

abstract class PersistenceStoreSpec[S](category: DeltaCategory.Value) {
  OLogManager.instance().setConsoleLevel("WARNING")

  protected def createStore(dbProvider: DatabaseProvider): S

  private[this] val dbCounter = new AtomicInteger(1)

  def withPersistenceStore(testCode: S => Any): Unit = {
    // make sure no accidental collisions
    val dbName = getClass.getSimpleName
    val uri = s"memory:${dbName}${nextDbId()}"
    
    val db = new ODatabaseDocumentTx(uri)
    db.activateOnCurrentThread()
    db.create()

    val dbProvider = DatabaseProvider(db)

    val mgr = new TestingSchemaManager(db, category, true)
    mgr.install().get

    val store = createStore(DatabaseProvider(db))

    try {
      testCode(store)
    } finally {
      db.activateOnCurrentThread()
      db.drop() // Drop will close and drop
    }
  }
  
  def nextDbId(): Int = {
    dbCounter.getAndIncrement()
  }

  object CommandListener extends OCommandOutputListener() {
    def onMessage(iText: String): Unit = {
    }
  }
}
