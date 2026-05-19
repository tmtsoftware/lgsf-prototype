package lgsf.pacprototypehcd

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}

import java.net.URI
import java.net.URLEncoder
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.time.Duration
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

object VbdsPublisherActor {
  final case class Settings(
      enabled: Boolean,
      host: String,
      port: Int,
      streamName: String,
      contentType: String,
      autoCreateStream: Boolean,
      requestTimeout: Duration
  )

  sealed trait Command
  final case class PublishFrame(frame: CameraFrame)                                       extends Command
  private final case class PublishCompleted(statusCode: Int, endpoint: String)            extends Command
  private final case class PublishFailed(reason: String, cause: Option[Throwable] = None) extends Command
  private final case class PublishAttempt(name: String, uri: String, contentType: String, body: Array[Byte])

  def apply(settings: Settings): Behavior[Command] =
    Behaviors.setup { ctx =>
      implicit val ec: ExecutionContext = ctx.executionContext
      val log                           = ctx.log

      val client = HttpClient.newBuilder().connectTimeout(settings.requestTimeout).build()
      val stream = urlEncode(settings.streamName)
      val base   = s"http://${settings.host}:${settings.port}"

      val publishAttempts = buildPublishAttempts(base, stream, settings.contentType)
      log.debug(
        s"VBDS publisher initialized: enabled=${settings.enabled}, host=${settings.host}, port=${settings.port}, stream=${settings.streamName}, autoCreate=${settings.autoCreateStream}"
      )

      if (settings.enabled && settings.autoCreateStream) {
        createStream(client, settings, stream) match {
          case Success(code) if code == 200 || code == 409 =>
            log.info(s"VBDS stream ready: ${settings.streamName} (status=$code)")
          case Success(code) =>
            log.warn(s"VBDS stream create returned status=$code for stream=${settings.streamName}")
          case Failure(ex) =>
            log.error(s"VBDS stream create failed for stream=${settings.streamName}: ${errorDetails(ex)}")
        }
      }

      Behaviors.receiveMessage {
        case PublishFrame(frame) if !settings.enabled =>
          log.debug("Skipping VBDS publish because publisher is disabled")
          Behaviors.same

        case PublishFrame(frame) =>
          log.debug(
            s"Preparing VBDS publish for frame=${frame.width}x${frame.height} ts=${frame.timestamp} bytes=${frame.data.length}"
          )
          val fitsBytes = FrameProcessor.frameToFitsBytes(frame)
          publish(client, publishAttempts(fitsBytes), settings.requestTimeout).onComplete {
            case Success((code, endpoint)) => ctx.self ! PublishCompleted(code, endpoint)
            case Failure(ex)               => ctx.self ! PublishFailed(ex.getMessage, Some(ex))
          }
          Behaviors.same

        case PublishCompleted(code, endpoint) =>
          if (code >= 200 && code < 300) {
            log.debug(s"Published FITS frame to VBDS endpoint=$endpoint status=$code")
          }
          else {
            log.warn(s"VBDS publish returned status=$code endpoint=$endpoint")
          }
          Behaviors.same

        case PublishFailed(reason, Some(ex)) =>
          log.error(
            s"VBDS publish failed: $reason; ${errorDetails(ex)}. Hint: verify VBDS HTTP server is reachable at http://${settings.host}:${settings.port} (this is the HTTP port, not the Akka port)."
          )
          Behaviors.same

        case PublishFailed(reason, None) =>
          log.warn(s"VBDS publish failed: $reason")
          Behaviors.same
      }
    }

  private def createStream(client: HttpClient, settings: Settings, encodedStream: String): scala.util.Try[Int] =
    scala.util
      .Try {
        val contentType = urlEncode(settings.contentType)
        val uri = URI.create(
          s"http://${settings.host}:${settings.port}/vbds/admin/streams/$encodedStream?contentType=$contentType"
        )
        val req = HttpRequest
          .newBuilder(uri)
          .timeout(settings.requestTimeout)
          .POST(HttpRequest.BodyPublishers.noBody())
          .build()
        client.send(req, HttpResponse.BodyHandlers.discarding()).statusCode()
      }
      .recoverWith { case ex =>
        scala.util.Failure(
          new RuntimeException(
            s"${ex.getMessage}. Hint: verify VBDS HTTP server is reachable at http://${settings.host}:${settings.port} (this is the HTTP port, not the Akka port).",
            ex
          )
        )
      }

  private def publish(
      client: HttpClient,
      attempts: List[PublishAttempt],
      timeout: Duration
  )(implicit ec: ExecutionContext): scala.concurrent.Future[(Int, String)] = {
    def tryOne(remaining: List[PublishAttempt]): scala.concurrent.Future[(Int, String)] =
      remaining match {
        case Nil =>
          scala.concurrent.Future.failed(new RuntimeException("No VBDS publish endpoint succeeded"))
        case attempt :: tail =>
          val req = HttpRequest
            .newBuilder(URI.create(attempt.uri))
            .timeout(timeout)
            .header("Content-Type", attempt.contentType)
            .POST(HttpRequest.BodyPublishers.ofByteArray(attempt.body))
            .build()
          scala.concurrent
            .Future(client.send(req, HttpResponse.BodyHandlers.discarding()))
            .flatMap { resp =>
              val code = resp.statusCode()
              if (code >= 200 && code < 300) scala.concurrent.Future.successful((code, s"${attempt.name}:${attempt.uri}"))
              else if (tail.nonEmpty) tryOne(tail)
              else scala.concurrent.Future.successful((code, s"${attempt.name}:${attempt.uri}"))
            }
            .recoverWith {
              case ex if tail.nonEmpty =>
                tryOne(tail)
            }
      }

    tryOne(attempts)
  }

  private def buildPublishAttempts(base: String, encodedStream: String, contentType: String)(
      fitsBytes: Array[Byte]
  ): List[PublishAttempt] = {
    val streamPath = s"$base/vbds/transfer/streams/$encodedStream"
    List(
      PublishAttempt(
        name = "raw-image-endpoint",
        uri = s"$streamPath/image",
        contentType = contentType,
        body = fitsBytes
      ),
      PublishAttempt(
        name = "raw-stream-endpoint",
        uri = streamPath,
        contentType = contentType,
        body = fitsBytes
      ),
      buildMultipartAttempt(streamPath, contentType, fitsBytes)
    )
  }

  private def buildMultipartAttempt(streamUri: String, contentType: String, fitsBytes: Array[Byte]): PublishAttempt = {
    val boundary = s"----vbds-${java.util.UUID.randomUUID().toString.replace("-", "")}"
    val preamble =
      s"--$boundary\r\nContent-Disposition: form-data; name=\"data\"; filename=\"frame.fits\"\r\nContent-Type: $contentType\r\n\r\n"
    val closing = s"\r\n--$boundary--\r\n"
    val body    = preamble.getBytes(StandardCharsets.UTF_8) ++ fitsBytes ++ closing.getBytes(StandardCharsets.UTF_8)
    PublishAttempt(
      name = "multipart-stream-endpoint",
      uri = streamUri,
      contentType = s"multipart/form-data; boundary=$boundary",
      body = body
    )
  }

  private def urlEncode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8)

  private def errorDetails(ex: Throwable): String = {
    val top = ex.getStackTrace.headOption.map(_.toString).getOrElse("no-stack")
    s"${ex.getClass.getSimpleName}: ${ex.getMessage}; at $top"
  }
}
