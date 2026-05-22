package lgsf.pacprototypehcd

import csw.event.api.scaladsl.EventPublisher
import csw.params.core.generics.KeyType
import csw.params.events.{EventName, SystemEvent}
import csw.prefix.models.Prefix
import nom.tam.fits.Fits
import nom.tam.util.{FitsFile, FitsOutputStream}
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import org.apache.pekko.actor.typed.{ActorRef, Behavior}

import java.io.ByteArrayOutputStream
import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration.*

object FrameProcessor {
  sealed trait Command
  final case class Start(period: FiniteDuration) extends Command
  case object Stop                               extends Command
  case object Tick                               extends Command

  val centroidXKey: csw.params.core.generics.Key[Double]    = KeyType.DoubleKey.make("x")
  val centroidYKey: csw.params.core.generics.Key[Double]    = KeyType.DoubleKey.make("y")
  val frameTimestampKey: csw.params.core.generics.Key[Long] = KeyType.LongKey.make("timestamp")

  private def errorDetails(ex: Throwable): String = {
    val top = ex.getStackTrace.headOption.map(_.toString).getOrElse("no-stack")
    s"${ex.getClass.getSimpleName}: ${ex.getMessage}; at $top"
  }

  private def buildCentroidEvent(prefix: Prefix, frame: CameraFrame): SystemEvent = {
    val (x, y) = computeCentroid(frame)
    SystemEvent(prefix, EventName("cameraCentroid"))
      .add(centroidXKey.set(x))
      .add(centroidYKey.set(y))
      .add(frameTimestampKey.set(frame.timestamp))
  }

  def computeCentroid(frame: CameraFrame): (Double, Double) = {
    var sum = 0.0
    var xw  = 0.0
    var yw  = 0.0
    var y   = 0
    while (y < frame.height) {
      var x = 0
      while (x < frame.width) {
        val i = java.lang.Byte.toUnsignedInt(frame.data(y * frame.width + x)).toDouble
        sum += i
        xw += x * i
        yw += y * i
        x += 1
      }
      y += 1
    }
    if (sum == 0.0) ((frame.width - 1) / 2.0, (frame.height - 1) / 2.0)
    else (xw / sum, yw / sum)
  }

  private def safeDateObs(timestamp: Long): String = {
    val now = java.time.Instant.now()
    if (timestamp > 0L && timestamp < 4102444800000L) { // ~ year 2100 in epoch millis
      try java.time.Instant.ofEpochMilli(timestamp).toString
      catch {
        case _: Throwable => now.toString
      }
    }
    else now.toString
  }

  private def buildFits(frame: CameraFrame): Fits = {
    if (frame.width <= 0 || frame.height <= 0) {
      throw new IllegalArgumentException(s"Invalid frame dimensions for FITS: width=${frame.width}, height=${frame.height}")
    }
    val expectedPixels = frame.width * frame.height
    if (frame.data == null) {
      throw new IllegalArgumentException("Invalid frame data for FITS: data is null")
    }
    if (frame.data.length < expectedPixels) {
      throw new IllegalArgumentException(
        s"Invalid frame data length for FITS: expected at least $expectedPixels bytes, got ${frame.data.length}"
      )
    }

    val floatData = Array.ofDim[Float](frame.height, frame.width)
    var y         = 0
    while (y < frame.height) {
      var x = 0
      while (x < frame.width) {
        floatData(y)(x) = java.lang.Byte.toUnsignedInt(frame.data(y * frame.width + x)).toFloat
        x += 1
      }
      y += 1
    }

    val hdu = Fits.makeHDU(floatData)
    hdu.getHeader.addValue("DATE-OBS", safeDateObs(frame.timestamp), "observation timestamp")
    val fits = new Fits()
    fits.addHDU(hdu)
    fits
  }

  def frameToFitsBytes(frame: CameraFrame): Array[Byte] = {
    val fits    = buildFits(frame)
    val out     = new ByteArrayOutputStream()
    val fitsOut = new FitsOutputStream(out)
    fits.write(fitsOut)
    fitsOut.flush()
    out.toByteArray
  }

  def writeFrameToFits(frame: CameraFrame, filePath: String): Unit = {
    val fits = buildFits(frame)
    val bf   = new FitsFile(filePath, "rw")
    fits.write(bf)
    bf.close()
  }

  def apply(
      camera: PacCamera,
      publisher: EventPublisher,
      prefix: Prefix,
      vbdsPublisher: Option[ActorRef[VbdsPublisherActor.Command]]
  ): Behavior[Command] =
    Behaviors.setup { _ =>
      Behaviors.withTimers { timers =>
        idle(timers, camera, publisher, prefix, vbdsPublisher)
      }
    }

  private def idle(
      timers: TimerScheduler[Command],
      camera: PacCamera,
      publisher: EventPublisher,
      prefix: Prefix,
      vbdsPublisher: Option[ActorRef[VbdsPublisherActor.Command]]
  ): Behavior[Command] =
    Behaviors.receive { (ctx, msg) =>
      msg match {
        case Start(period) =>
          ctx.log.debug(s"FrameProcessor Start received: period=$period")
          try {
            camera.startStream()
            timers.startTimerAtFixedRate(Tick, period)
            ctx.log.info(s"FrameProcessor started, period=$period")
          }
          catch {
            case e: Exception => ctx.log.error(s"Failed to start camera stream: ${errorDetails(e)}")
          }
          running(timers, camera, publisher, prefix, vbdsPublisher, ctx)

        case Stop =>
          ctx.log.info("FrameProcessor already idle")
          Behaviors.same

        case Tick =>
          Behaviors.same
      }
    }

  private def running(
      timers: TimerScheduler[Command],
      camera: PacCamera,
      publisher: EventPublisher,
      prefix: Prefix,
      vbdsPublisher: Option[ActorRef[VbdsPublisherActor.Command]],
      ctx: ActorContext[Command]
  ): Behavior[Command] =
    Behaviors.receiveMessage {
      case Tick =>
        camera.getStreamFrame(5000) match {
          case Some(frame) =>
            implicit val ec: ExecutionContextExecutor = ctx.executionContext
            val event                                 = buildCentroidEvent(prefix, frame)
            val (cx, cy)                              = computeCentroid(frame)
            publisher.publish(event).failed.foreach { ex =>
              ctx.log.error(s"Failed to publish centroid event: ${errorDetails(ex)}")
            }
            vbdsPublisher.foreach(_ ! VbdsPublisherActor.PublishFrame(frame))
            ctx.log.debug(
              s"FrameProcessor Tick: frame=${frame.width}x${frame.height}, ts=${frame.timestamp}, centroid=($cx,$cy), vbdsEnabled=${vbdsPublisher.isDefined}"
            )
          case None =>
            ctx.log.debug("FrameProcessor Tick: no frame returned from camera")
        }
        Behaviors.same

      case Start(newPeriod) =>
        timers.cancel(Tick)
        timers.startTimerAtFixedRate(Tick, newPeriod)
        ctx.log.info(s"FrameProcessor period updated to $newPeriod")
        Behaviors.same

      case Stop =>
        ctx.log.debug("FrameProcessor Stop received")
        timers.cancel(Tick)
        try camera.stopStream()
        catch { case e: Exception => ctx.log.error(s"Error stopping camera stream: ${errorDetails(e)}") }
        ctx.log.info("FrameProcessor stopped")
        idle(timers, camera, publisher, prefix, vbdsPublisher)
    }
}
