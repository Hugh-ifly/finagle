package com.twitter.finagle.server

import com.twitter.conversions.time._
import com.twitter.finagle._
import com.twitter.finagle.context.{Contexts, Deadline}
import com.twitter.finagle.param.{Stats, Timer}
import com.twitter.finagle.service.{ExpiringService, TimeoutFilter}
import com.twitter.finagle.stack.Endpoint
import com.twitter.finagle.stats.InMemoryStatsReceiver
import com.twitter.finagle.util.StackRegistry
import com.twitter.util.{Await, Duration, Future, MockTimer, Promise, Time}
import java.net.{InetSocketAddress, SocketAddress}
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class StackServerTest extends FunSuite with StringServer {
  test("Deadline isn't changed until after it's recorded") {
    val echo = ServiceFactory.const(Service.mk[Unit, Deadline] { unit =>
      Future.value(Contexts.broadcast(Deadline))
    })
    val stack = StackServer.newStack[Unit, Deadline] ++ Stack.Leaf(Endpoint, echo)
    val statsReceiver = new InMemoryStatsReceiver
    val factory = stack.make(StackServer.defaultParams + TimeoutFilter.Param(1.second) + Stats(statsReceiver))
    val svc = Await.result(factory(), 5.seconds)
    Time.withCurrentTimeFrozen { ctl =>
      Contexts.broadcast.let(Deadline, Deadline.ofTimeout(5.seconds)) {
        ctl.advance(1.second)
        val result = svc(())

        // we should be one second ahead
        assert(statsReceiver.stats(Seq("transit_latency_ms"))(0) == 1.second.inMilliseconds.toFloat)

        // but the deadline inside the service's closure should be updated
        assert(Await.result(result) == Deadline.ofTimeout(1.second))
      }
    }
  }

  test("StackServer uses ExpiringService") {
    @volatile var closed = false
    val connSF = new ServiceFactory[Int, Int] {
      val svc = Service.mk[Int, Int] { i => Future.value(i) }
      def apply(conn: ClientConnection) = {
        conn.onClose.ensure { closed = true }
        Future.value(svc)
      }
      def close(deadline: Time) = Future.Done
    }
    val stack = StackServer.newStack[Int, Int] ++ Stack.Leaf(Endpoint, connSF)
    val sr = new InMemoryStatsReceiver
    val timer = new MockTimer
    val lifeTime = 1.second
    val factory = stack.make(
      StackServer.defaultParams +
      ExpiringService.Param(idleTime = Duration.Top, lifeTime = lifeTime) +
      Timer(timer) +
      Stats(sr))

    val conn = new ClientConnection {
      val closed = new Promise[Unit]
      def remoteAddress: SocketAddress = new SocketAddress {}
      def localAddress: SocketAddress = new SocketAddress {}
      def close(deadline: Time): Future[Unit] = {
        closed.setDone()
        Future.Done
      }
      def onClose: Future[Unit] = closed
    }

    val svc = Await.result(factory(conn), 5.seconds)

    Time.withCurrentTimeFrozen { ctl =>
      assert(Await.result(svc(1), 5.seconds) == 1)
      ctl.advance(lifeTime * 2)
      timer.tick()
      assert(closed)
    }
  }

  test("StackServer added to server registry") {
    ServerRegistry.clear()
    val name = "testServer"
    val s = Service.const[String](Future.value("foo"))
    val server = stringServer.withLabel(name).serve(new InetSocketAddress(0), s)

    assert(ServerRegistry.registrants.count { e: StackRegistry.Entry =>
      val param.Label(actual) = e.params[param.Label]
      name == actual
    } == 1)

    Await.ready(server.close(), 10.seconds)
  }
}
