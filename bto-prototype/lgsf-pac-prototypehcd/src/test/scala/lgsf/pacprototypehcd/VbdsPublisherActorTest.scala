package lgsf.pacprototypehcd

import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.funsuite.AnyFunSuiteLike

import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}

class VbdsPublisherActorTest extends ScalaTestWithActorTestKit with AnyFunSuiteLike {

  test("VbdsPublisherActor should create VBDS stream on startup when enabled and auto-create is true") {
    val requests = new LinkedBlockingQueue[String]()
    val server   = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    val stream   = "PAC-RAW"

    server.createContext(
      "/",
      handler { ex =>
        val path = requestPath(ex)
        requests.offer(path)
        if (path.startsWith(s"/vbds/admin/streams/$stream")) ex.sendResponseHeaders(200, -1)
        else ex.sendResponseHeaders(404, -1)
        ex.close()
      }
    )
    server.start()

    try {
      val port = server.getAddress.getPort
      spawn(
        _root_.lgsf.pacprototypehcd.VbdsPublisherActor(
          _root_.lgsf.pacprototypehcd.VbdsPublisherActor.Settings(
            enabled = true,
            host = "127.0.0.1",
            port = port,
            streamName = stream,
            contentType = "image/fits",
            autoCreateStream = true,
            requestTimeout = Duration.ofSeconds(2)
          )
        )
      )

      val createReq = requests.poll(5, TimeUnit.SECONDS)
      assert(createReq != null)
      assert(createReq.startsWith(s"/vbds/admin/streams/$stream"))
    }
    finally {
      server.stop(0)
    }
  }

  test("VbdsPublisherActor should not send requests when disabled") {
    val requests = new LinkedBlockingQueue[String]()
    val server   = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    val stream   = "PAC-RAW"

    server.createContext(
      "/",
      handler { ex =>
        requests.offer(requestPath(ex))
        ex.sendResponseHeaders(200, -1)
        ex.close()
      }
    )
    server.start()

    try {
      val port = server.getAddress.getPort
      val actor = spawn(
        _root_.lgsf.pacprototypehcd.VbdsPublisherActor(
          _root_.lgsf.pacprototypehcd.VbdsPublisherActor.Settings(
            enabled = false,
            host = "127.0.0.1",
            port = port,
            streamName = stream,
            contentType = "image/fits",
            autoCreateStream = true,
            requestTimeout = Duration.ofSeconds(2)
          )
        )
      )
      actor ! _root_.lgsf.pacprototypehcd.VbdsPublisherActor.PublishFrame(
        _root_.lgsf.pacprototypehcd.CameraFrame(10, 10, 1L, Array.fill[Byte](100)(7))
      )

      val req = requests.poll(2, TimeUnit.SECONDS)
      assert(req == null)
    }
    finally {
      server.stop(0)
    }
  }

  test("VbdsPublisherActor should fallback to multipart publish when raw endpoints fail") {
    val requests = new LinkedBlockingQueue[(String, String)]()
    val server   = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    val stream   = "PAC-RAW"

    server.createContext(
      "/",
      handler { ex =>
        val path        = requestPath(ex)
        val contentType = Option(ex.getRequestHeaders.getFirst("Content-Type")).getOrElse("")
        requests.offer((path, contentType))
        val isPublishPath = path == s"/vbds/transfer/streams/$stream" || path == s"/vbds/transfer/streams/$stream/image"
        if (isPublishPath && contentType.startsWith("multipart/form-data")) ex.sendResponseHeaders(204, -1)
        else if (isPublishPath) ex.sendResponseHeaders(400, -1)
        else ex.sendResponseHeaders(404, -1)
        ex.close()
      }
    )
    server.start()

    try {
      val port = server.getAddress.getPort
      val actor = spawn(
        _root_.lgsf.pacprototypehcd.VbdsPublisherActor(
          _root_.lgsf.pacprototypehcd.VbdsPublisherActor.Settings(
            enabled = true,
            host = "127.0.0.1",
            port = port,
            streamName = stream,
            contentType = "image/fits",
            autoCreateStream = false,
            requestTimeout = Duration.ofSeconds(2)
          )
        )
      )

      actor ! _root_.lgsf.pacprototypehcd.VbdsPublisherActor.PublishFrame(
        _root_.lgsf.pacprototypehcd.CameraFrame(10, 10, 1L, Array.fill[Byte](100)(7))
      )

      var sawMultipart = false
      val deadline     = System.currentTimeMillis() + 5000
      while (!sawMultipart && System.currentTimeMillis() < deadline) {
        val req = requests.poll(250, TimeUnit.MILLISECONDS)
        if (req != null && req._2.startsWith("multipart/form-data")) sawMultipart = true
      }
      assert(sawMultipart, "Expected multipart/form-data publish fallback request")
    }
    finally {
      server.stop(0)
    }
  }

  private def handler(fn: HttpExchange => Unit): HttpHandler =
    (exchange: HttpExchange) => fn(exchange)

  private def requestPath(ex: HttpExchange): String =
    Option(ex.getRequestURI.getRawQuery).map(q => s"${ex.getRequestURI.getPath}?$q").getOrElse(ex.getRequestURI.getPath)
}
