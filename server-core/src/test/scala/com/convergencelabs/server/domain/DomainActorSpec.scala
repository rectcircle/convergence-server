package com.convergencelabs.server.domain

import java.util.concurrent.TimeUnit

import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import scala.language.postfixOps

import org.junit.runner.RunWith
import org.mockito.Mockito
import org.scalatest.BeforeAndAfterAll
import org.scalatest.Finders
import org.scalatest.WordSpecLike
import org.scalatest.junit.JUnitRunner
import org.scalatest.mock.MockitoSugar

import com.convergencelabs.server.HeartbeatConfiguration
import com.convergencelabs.server.ProtocolConfiguration
import com.convergencelabs.server.datastore.domain.DomainPersistenceProvider
import com.convergencelabs.server.util.MockDomainPersistenceManager

import akka.actor.ActorSystem
import akka.testkit.TestKit
import akka.testkit.TestProbe
import scala.util.Success

@RunWith(classOf[JUnitRunner])
class DomainActorSpec
    extends TestKit(ActorSystem("DomainActorSpec"))
    with WordSpecLike
    with BeforeAndAfterAll
    with MockitoSugar {

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
  }

  "A DomainActor" when {
    "receiving an initial handshake request" must {
      "response with a handshake success" in new TestFixture {
        val client = new TestProbe(system)
        domainActor.tell(HandshakeRequest(domainFqn, client.ref, false, None), client.ref)
        val response = client.expectMsgClass(FiniteDuration(1, TimeUnit.SECONDS), classOf[HandshakeSuccess])
        assert(domainActor == response.domainActor)
      }
    }

    "receiving a client disconnect" must {
      "send a domain shutdown request when the last client disconnects" in new TestFixture {
        val client = new TestProbe(system)
        domainActor.tell(HandshakeRequest(domainFqn, client.ref, false, None), client.ref)
        val response = client.expectMsgClass(FiniteDuration(1, TimeUnit.SECONDS), classOf[HandshakeSuccess])

        domainActor.tell(ClientDisconnected("sessionId"), client.ref)
        var request = domainManagerActor.expectMsgClass(FiniteDuration(1, TimeUnit.SECONDS), classOf[DomainShutdownRequest])
        assert(domainFqn == request.domainFqn)
      }
    }
  }

  trait TestFixture {
    val domainFqn = DomainFqn("convergence", "default")

    val provider = mock[DomainPersistenceProvider]
    Mockito.when(provider.validateConnection()).thenReturn(Success(()))
    
    val persistenceManager = new MockDomainPersistenceManager(Map(domainFqn -> provider))

    val domainManagerActor = new TestProbe(system)

    val protocolConfig = ProtocolConfiguration(
      2 seconds,
      2 seconds,
      HeartbeatConfiguration(
        false,
        0 seconds,
        0 seconds))

    val props = DomainActor.props(
      domainManagerActor.ref,
      domainFqn,
      protocolConfig,
      10 seconds,
      persistenceManager)

    val domainActor = system.actorOf(props)
  }
}
