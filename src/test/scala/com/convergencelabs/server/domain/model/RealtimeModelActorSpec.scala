package com.convergencelabs.server.domain.model

import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration
import org.json4s.JsonAST.JObject
import org.json4s.JsonAST.JString
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Matchers
import org.mockito.Mockito
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.scalatest.BeforeAndAfterAll
import org.scalatest.Finders
import org.scalatest.WordSpecLike
import org.scalatest.junit.JUnitRunner
import org.scalatest.mock.MockitoSugar
import com.convergencelabs.server.ErrorResponse
import com.convergencelabs.server.ErrorResponse
import com.convergencelabs.server.datastore.domain.ModelData
import com.convergencelabs.server.datastore.domain.ModelMetaData
import com.convergencelabs.server.datastore.domain.ModelSnapshotStore
import com.convergencelabs.server.datastore.domain.ModelStore
import com.convergencelabs.server.datastore.domain.SnapshotData
import com.convergencelabs.server.datastore.domain.SnapshotMetaData
import com.convergencelabs.server.domain.DomainFqn
import com.convergencelabs.server.domain.model.ot.ops.StringInsertOperation
import akka.actor.ActorSystem
import akka.testkit.TestKit
import akka.testkit.TestProbe
import com.convergencelabs.server.datastore.domain.OperationStore

// FIXME we really only check message types and not data.
@RunWith(classOf[JUnitRunner])
class RealtimeModelActorSpec
    extends TestKit(ActorSystem("RealtimeModelActorSpec"))
    with WordSpecLike
    with BeforeAndAfterAll
    with MockitoSugar {

  override def afterAll() {
    TestKit.shutdownActorSystem(system)
  }

  "A RealtimeModelActor" when {
    "opening a closed model" must {
      "load the model from the database if it is persisted" in new MockDatabaseWithModel {

        val client = new TestProbe(system)
        realtimeModelActor.tell(OpenRealtimeModelRequest("s1", modelFqn, client.ref), client.ref)

        val message = client.expectMsgClass(FiniteDuration(1, TimeUnit.SECONDS), classOf[OpenModelSuccess])
        assert(message.modelData == modelData.data)
        assert(message.metaData.version == modelData.metaData.version)
        assert(message.metaData.createdTime == modelData.metaData.createdTime)
        assert(message.metaData.modifiedTime == modelData.metaData.modifiedTime)
      }

      "notify openers of an initialization errror" in new MockDatabaseWithModel {
        val client = new TestProbe(system)

        // Set the database up to bomb
        Mockito.when(modelStore.getModelData(Matchers.any())).thenThrow(new IllegalArgumentException("Induced error for test"))

        realtimeModelActor.tell(OpenRealtimeModelRequest("s1", modelFqn, client.ref), client.ref)
        val message = client.expectMsgClass(FiniteDuration(1, TimeUnit.SECONDS), classOf[ErrorResponse])
      }

      "ask all connecting clients for state if it is not persisted" in new MockDatabaseWithoutModel {
        val client1 = new TestProbe(system)
        val client2 = new TestProbe(system)

        realtimeModelActor.tell(OpenRealtimeModelRequest("s1", modelFqn, client1.ref), client1.ref)
        val dataRequest1 = client1.expectMsgClass(FiniteDuration(1, TimeUnit.SECONDS), classOf[ClientModelDataRequest])
        assert(dataRequest1.modelFqn == modelFqn)

        realtimeModelActor.tell(OpenRealtimeModelRequest("s2", modelFqn, client2.ref), client2.ref)
        val dataRequest2 = client2.expectMsgClass(FiniteDuration(1, TimeUnit.SECONDS), classOf[ClientModelDataRequest])
        assert(dataRequest1.modelFqn == modelFqn)
      }

      "reject a client that does not respond with data" in new MockDatabaseWithoutModel {
        val client1 = new TestProbe(system)
        realtimeModelActor.tell(OpenRealtimeModelRequest("s1", modelFqn, client1.ref), client1.ref)
        client1.expectMsgClass(FiniteDuration(1, TimeUnit.SECONDS), classOf[ClientModelDataRequest])
        client1.expectMsgClass(FiniteDuration(200, TimeUnit.MILLISECONDS), classOf[ErrorResponse])
      }

      "reject a client that responds with the wrong message in request to data" in new MockDatabaseWithoutModel {
        val client1 = new TestProbe(system)
        realtimeModelActor.tell(OpenRealtimeModelRequest("s1", modelFqn, client1.ref), client1.ref)
        client1.expectMsgClass(FiniteDuration(1, TimeUnit.SECONDS), classOf[ClientModelDataRequest])
        client1.reply(ErrorResponse("","")) // Any message that is not a ClientModelDataResponse will do here.
        client1.expectMsgClass(FiniteDuration(200, TimeUnit.MILLISECONDS), classOf[ErrorResponse])
      }

      "notify all queued clients when data is returned by the first client" in new MockDatabaseWithoutModel {
        val client1 = new TestProbe(system)
        val client2 = new TestProbe(system)

        realtimeModelActor.tell(OpenRealtimeModelRequest("s1", modelFqn, client1.ref), client1.ref)
        client1.expectMsgClass(FiniteDuration(1, TimeUnit.SECONDS), classOf[ClientModelDataRequest])

        realtimeModelActor.tell(OpenRealtimeModelRequest("s2", modelFqn, client2.ref), client2.ref)
        client2.expectMsgClass(FiniteDuration(1, TimeUnit.SECONDS), classOf[ClientModelDataRequest])

        // Now mock that the data is there.
        Mockito.when(modelStore.getModelData(modelFqn)).thenReturn(Some(modelData))
        Mockito.when(modelSnapshotStore.getLatestSnapshotMetaDataForModel(modelFqn)).thenReturn(Some(modelSnapshotMetaData))

        client1.reply(ClientModelDataResponse(modelJsonData))
        client2.reply(ClientModelDataResponse(modelJsonData))

        // Verify that both clients got the data.
        client1.expectMsgClass(FiniteDuration(1, TimeUnit.SECONDS), classOf[OpenModelSuccess])
        val openResponse = client2.expectMsgClass(FiniteDuration(1, TimeUnit.SECONDS), classOf[OpenModelSuccess])

        assert(openResponse.modelData == modelJsonData)
        assert(openResponse.metaData.version == modelData.metaData.version)
        assert(openResponse.metaData.createdTime == modelData.metaData.createdTime)
        assert(openResponse.metaData.modifiedTime == modelData.metaData.modifiedTime)

        // Verify that the model and snapshot were created.
        verify(modelStore, times(1)).createModel(
          Matchers.eq(modelFqn),
          Matchers.eq(modelJsonData),
          Matchers.any())

        val snapshotCaptor = ArgumentCaptor.forClass(classOf[SnapshotData])

        verify(modelSnapshotStore, times(1)).createSnapshot(snapshotCaptor.capture())
        val capturedData = snapshotCaptor.getValue
        assert(capturedData.data == modelJsonData)
        assert(capturedData.metaData.fqn == modelFqn)
        assert(capturedData.metaData.version == 0) // since it is newly created.
        assert(capturedData.metaData.timestamp != 0)
      }
    }

    "opening an open model" must {
      "not allow the same session to open the same model twice" in new MockDatabaseWithModel {
        val client1 = new TestProbe(system)

        realtimeModelActor.tell(OpenRealtimeModelRequest("s1", modelFqn, client1.ref), client1.ref)
        val open1 = client1.expectMsgClass(FiniteDuration(1, TimeUnit.SECONDS), classOf[OpenModelSuccess])

        realtimeModelActor.tell(OpenRealtimeModelRequest("s1", modelFqn, client1.ref), client1.ref)
        val open2 = client1.expectMsg(FiniteDuration(1, TimeUnit.SECONDS), ModelAlreadyOpen)
      }
    }
    
    "closing a closed a model" must {
      "acknowledge the close" in new MockDatabaseWithModel with OneOpenClient {
        realtimeModelActor.tell(CloseRealtimeModelRequest(client1SessionId), client1.ref)
        val closeAck = client1.expectMsgClass(FiniteDuration(1, TimeUnit.SECONDS), classOf[CloseRealtimeModelSuccess])
      }

      "respond with an error for an invalid cId" in new MockDatabaseWithModel with OneOpenClient {
        realtimeModelActor.tell(CloseRealtimeModelRequest("invalidCId"), client1.ref)
        client1.expectMsg(FiniteDuration(1, TimeUnit.SECONDS), ModelNotOpened)
      }

      "notify other connected clients" in new MockDatabaseWithModel {
        val client1 = new TestProbe(system)
        val client2 = new TestProbe(system)

        realtimeModelActor.tell(OpenRealtimeModelRequest("s1", modelFqn, client1.ref), client1.ref)
        var client1Response = client1.expectMsgClass(FiniteDuration(1, TimeUnit.SECONDS), classOf[OpenModelSuccess])

        realtimeModelActor.tell(OpenRealtimeModelRequest("s2", modelFqn, client2.ref), client2.ref)
        var client2Response = client2.expectMsgClass(FiniteDuration(1, TimeUnit.SECONDS), classOf[OpenModelSuccess])

        realtimeModelActor.tell(CloseRealtimeModelRequest("s2"), client2.ref)
        val closeAck = client2.expectMsgClass(FiniteDuration(1, TimeUnit.SECONDS), classOf[CloseRealtimeModelSuccess])

        client1.expectMsgClass(FiniteDuration(1, TimeUnit.SECONDS), classOf[RemoteClientClosed])
      }

      "notify domain when last client disconnects" in new MockDatabaseWithModel {
        val client1 = new TestProbe(system)

        realtimeModelActor.tell(OpenRealtimeModelRequest("s1", modelFqn, client1.ref), client1.ref)
        var client1Response = client1.expectMsgClass(FiniteDuration(1, TimeUnit.SECONDS), classOf[OpenModelSuccess])
        realtimeModelActor.tell(CloseRealtimeModelRequest("s1"), client1.ref)
        val closeAck = client1.expectMsgClass(FiniteDuration(1, TimeUnit.SECONDS), classOf[CloseRealtimeModelSuccess])
        modelManagerActor.expectMsgClass(FiniteDuration(1, TimeUnit.SECONDS), classOf[ModelShutdownRequest])
      }
    }

    "receiving an operation" must {
      "send an ack back to the submitting client" in new OneOpenClient {
        realtimeModelActor.tell(OperationSubmission(0L, modelData.metaData.version, StringInsertOperation(List(), false, 1, "1")), client1.ref)
        val opAck = client1.expectMsgClass(FiniteDuration(1, TimeUnit.SECONDS), classOf[OperationAcknowledgement])
      }

      "send an operation to other connected clients" in new TwoOpenClients {
        realtimeModelActor.tell(OperationSubmission(0L, modelData.metaData.version, StringInsertOperation(List(), false, 1, "1")), client1.ref)
        val opAck = client1.expectMsgClass(FiniteDuration(1, TimeUnit.SECONDS), classOf[OperationAcknowledgement])

        client2.expectMsgClass(FiniteDuration(1, TimeUnit.SECONDS), classOf[OutgoingOperation])
      }

      "close a client that submits an invalid operation" in new TwoOpenClients {
        val badOp = StringInsertOperation(List(), false, 1, "1")

        Mockito.when(operationStore.processOperation(
          Matchers.any(), Matchers.any(), Matchers.any(), Matchers.any(), Matchers.any(), Matchers.any())).thenThrow(new IllegalArgumentException("Invalid Operation"))

        realtimeModelActor.tell(OperationSubmission(
          0L,
          modelData.metaData.version,
          badOp), client1.ref)

        client1.expectMsgClass(FiniteDuration(1, TimeUnit.SECONDS), classOf[ModelForceClose])
        client2.expectMsgClass(FiniteDuration(1, TimeUnit.SECONDS), classOf[RemoteClientClosed])
      }
    }

    "open and a model is deleted" must {
      "force close all clients" in new TwoOpenClients {
        realtimeModelActor.tell(ModelDeleted, modelManagerActor.ref)
        client1.expectMsgClass(FiniteDuration(1, TimeUnit.SECONDS), classOf[ModelForceClose])
        client2.expectMsgClass(FiniteDuration(1, TimeUnit.SECONDS), classOf[ModelForceClose])
      }
    }
  }

  trait TestFixture {
    val modelFqn = ModelFqn("collection", "model" + System.nanoTime())
    val modelJsonData = JObject("key" -> JString("value"))
    val modelCreateTime = Instant.ofEpochMilli(2L)
    val modelModifiedTime = Instant.ofEpochMilli(3L)
    val modelData = ModelData(ModelMetaData(modelFqn, 1L, modelCreateTime, modelModifiedTime), modelJsonData)
    val modelSnapshotTime = Instant.ofEpochMilli(2L)
    val modelSnapshotMetaData = SnapshotMetaData(modelFqn, 1L, modelSnapshotTime)
    val modelStore = mock[ModelStore]
    val operationStore = mock[OperationStore]
    val modelSnapshotStore = mock[ModelSnapshotStore]
    val resourceId = "1" + System.nanoTime()
    val modelManagerActor = new TestProbe(system)
    val props = RealtimeModelActor.props(
      modelManagerActor.ref,
      DomainFqn("convergence", "default"),
      modelFqn,
      resourceId,
      modelStore,
      operationStore,
      modelSnapshotStore,
      100L,
      SnapshotConfig(true, 3, 3, false, Duration.of(1, ChronoUnit.SECONDS), Duration.of(1, ChronoUnit.SECONDS)))

    val realtimeModelActor = system.actorOf(props, resourceId)
  }

  trait MockDatabaseWithModel extends TestFixture {
    Mockito.when(modelStore.modelExists(modelFqn)).thenReturn(true)
    Mockito.when(modelStore.getModelData(modelFqn)).thenReturn(Some(modelData))
    Mockito.when(modelSnapshotStore.getLatestSnapshotMetaDataForModel(modelFqn)).thenReturn(Some(modelSnapshotMetaData))
  }

  trait OneOpenClient extends MockDatabaseWithModel {
    val client1 = new TestProbe(system)
    val client1SessionId = "client1"
    realtimeModelActor.tell(OpenRealtimeModelRequest(client1SessionId ,modelFqn, client1.ref), client1.ref)
    val client1OpenResponse = client1.expectMsgClass(FiniteDuration(1, TimeUnit.SECONDS), classOf[OpenModelSuccess])
  }

  trait TwoOpenClients extends OneOpenClient {
    val client2 = new TestProbe(system)
    val client2SessionId = "client2"
    realtimeModelActor.tell(OpenRealtimeModelRequest(client2SessionId ,modelFqn, client2.ref), client2.ref)
    val client2OpenResponse = client2.expectMsgClass(FiniteDuration(1, TimeUnit.SECONDS), classOf[OpenModelSuccess])
  }

  trait MockDatabaseWithoutModel extends TestFixture {
    Mockito.when(modelStore.modelExists(modelFqn)).thenReturn(false)
  }
}