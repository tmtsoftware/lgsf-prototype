package lgsf.pacprototypehcd

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import csw.command.client.messages.TopLevelActorMessage
import csw.event.api.scaladsl.EventPublisher
import csw.framework.models.CswContext
import csw.framework.scaladsl.ComponentHandlers
import csw.location.api.models.TrackingEvent
import csw.params.commands.CommandIssue.UnsupportedCommandIssue
import csw.params.commands.CommandResponse.*
import csw.params.commands.{ControlCommand, Setup}
import csw.params.core.generics.KeyType
import csw.params.core.models.ArrayData
import csw.params.events.{EventName, SystemEvent}
import csw.time.core.models.UTCTime
import csw.params.core.models.Id
import csw.prefix.models.Prefix

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration.*

/**
 * CSW HCD for the Pre-alignment Camera (PAC) — Imperx C1911.
 *
 * Supported commands:
 *   ConnectCamera      ipAddress: String (optional, falls back to config)
 *   DisconnectCamera   (no params)
 *   ConfigureCamera    exposureTimeUs: Float, gain: Float (both optional)
 *   StartStream        periodMillis: Long (optional, falls back to config)
 *   StopStream         (no params)
 *   TakeSingleExposure exposureTimeUs: Float (optional), timeoutMs: Int (optional)
 *
 * In simulation mode (pac-prototype-hcd.simulation-mode = true in application.conf) the
 * native library is not loaded and synthetic Gaussian spot images are produced instead.
 */
class PacPrototypeHcdHandlers(ctx: ActorContext[TopLevelActorMessage], cswCtx: CswContext)
    extends ComponentHandlers(ctx, cswCtx) {

  import cswCtx.*
  implicit val ec: ExecutionContextExecutor = ctx.executionContext

  private val log    = loggerFactory.getLogger
  private val config = ConfigFactory.load()

  private val camera: PacCamera = {
    val simMode = config.getBoolean("pac-prototype-hcd.simulation-mode")
    val width   = config.getInt("camera.width")
    val height  = config.getInt("camera.height")
    new PacCamera(simMode, width, height)
  }

  private val frameProcessor: ActorRef[FrameProcessor.Command] =
    ctx.spawn(
      FrameProcessor(camera, eventService.defaultPublisher, componentInfo.prefix),
      "frameProcessor"
    )

  override def initialize(): Unit =
    log.info("Initializing pac.prototypeHcd...")

  override def onLocationTrackingEvent(trackingEvent: TrackingEvent): Unit = {}

  override def validateCommand(runId: Id, controlCommand: ControlCommand): ValidateCommandResponse =
    controlCommand match {
      case _: Setup => Accepted(runId)
      case _        => Invalid(runId, UnsupportedCommandIssue("Only Setup commands are supported"))
    }

  override def onSubmit(runId: Id, controlCommand: ControlCommand): SubmitResponse =
    controlCommand match {

      case setup: Setup if setup.commandName.name == "ConnectCamera" =>
        val ip = setup
          .get(PacPrototypeHcdHandlers.ipAddressKey)
          .map(_.head)
          .getOrElse(config.getString("pac-prototype-hcd.camera-ip"))
        try {
          camera.connect(ip)
          log.info(s"Camera connected: $ip")
          Completed(runId)
        }
        catch {
          case e: Exception =>
            log.error(s"Camera connect failed: ${e.getMessage}")
            Error(runId, e.getMessage)
        }

      case _: Setup if controlCommand.commandName.name == "DisconnectCamera" =>
        camera.disconnect()
        log.info("Camera disconnected")
        Completed(runId)

      case setup: Setup if setup.commandName.name == "ConfigureCamera" =>
        setup.get(PacPrototypeHcdHandlers.exposureTimeUsKey).map(_.head).foreach { t =>
          camera.setExposureTime(t.toDouble)
          log.info(s"ExposureTime set to ${t}us")
        }
        setup.get(PacPrototypeHcdHandlers.gainKey).map(_.head).foreach { g =>
          camera.setGain(g.toDouble)
          log.info(s"Gain set to $g")
        }
        Completed(runId)

      case setup: Setup if setup.commandName.name == "StartStream" =>
        val period = setup
          .get(PacPrototypeHcdHandlers.periodMillisKey)
          .map(_.head)
          .getOrElse(config.getLong("pac-prototype-hcd.frame-period-millis"))
        frameProcessor ! FrameProcessor.Start(period.millis)
        log.info(s"Stream started, period=${period}ms")
        Completed(runId)

      case _: Setup if controlCommand.commandName.name == "StopStream" =>
        frameProcessor ! FrameProcessor.Stop
        log.info("Stream stopped")
        Completed(runId)

      case setup: Setup if setup.commandName.name == "TakeSingleExposure" =>
        setup.get(PacPrototypeHcdHandlers.exposureTimeUsKey).map(_.head).foreach { t =>
          camera.setExposureTime(t.toDouble)
        }
        val timeout = setup
          .get(PacPrototypeHcdHandlers.timeoutMsKey)
          .map(_.head)
          .getOrElse(5000)
        camera.takeSingleExposure(timeout) match {
          case Some(frame) =>
            val event = FrameProcessor.buildFrameEvent(componentInfo.prefix, frame)
            eventService.defaultPublisher.publish(event)
            log.info(s"Single exposure acquired: ${frame.width}x${frame.height}")
            Completed(runId)
          case None =>
            log.error("Single exposure timed out or failed")
            Error(runId, "Frame acquisition failed")
        }

      case _ =>
        Invalid(runId, UnsupportedCommandIssue(s"Unknown command: ${controlCommand.commandName.name}"))
    }

  override def onOneway(runId: Id, controlCommand: ControlCommand): Unit = {}

  override def onShutdown(): Unit = {
    frameProcessor ! FrameProcessor.Stop
    camera.disconnect()
  }

  override def onGoOffline(): Unit = {}

  override def onGoOnline(): Unit = {}

  override def onDiagnosticMode(startTime: UTCTime, hint: String): Unit = {}

  override def onOperationsMode(): Unit = {}
}

object PacPrototypeHcdHandlers {
  val ipAddressKey      = KeyType.StringKey.make("ipAddress")
  val exposureTimeUsKey = KeyType.FloatKey.make("exposureTimeUs")
  val gainKey           = KeyType.FloatKey.make("gain")
  val periodMillisKey   = KeyType.LongKey.make("periodMillis")
  val timeoutMsKey      = KeyType.IntKey.make("timeoutMs")
}

// ---------------------------------------------------------------------------
// FrameProcessor — Pekko actor that drives the continuous acquisition loop
// ---------------------------------------------------------------------------
object FrameProcessor {
  sealed trait Command
  final case class Start(period: FiniteDuration) extends Command
  case object Stop                               extends Command
  case object Tick                               extends Command

  // CSW event keys for the published cameraFrame event
  val frameWidthKey     = KeyType.IntKey.make("width")
  val frameHeightKey    = KeyType.IntKey.make("height")
  val frameTimestampKey = KeyType.LongKey.make("timestamp")
  val frameDataKey      = KeyType.ByteArrayKey.make("frameData")

  def buildFrameEvent(prefix: Prefix, frame: CameraFrame): SystemEvent =
    SystemEvent(prefix, EventName("cameraFrame"))
      .add(frameWidthKey.set(frame.width))
      .add(frameHeightKey.set(frame.height))
      .add(frameTimestampKey.set(frame.timestamp))
      .add(frameDataKey.set(ArrayData.fromArray(frame.data)))

  def apply(camera: PacCamera, publisher: EventPublisher, prefix: Prefix): Behavior[Command] =
    Behaviors.setup { _ =>
      Behaviors.withTimers { timers =>
        idle(timers, camera, publisher, prefix)
      }
    }

  private def idle(
      timers: TimerScheduler[Command],
      camera: PacCamera,
      publisher: EventPublisher,
      prefix: Prefix
  ): Behavior[Command] =
    Behaviors.receive { (ctx, msg) =>
      msg match {
        case Start(period) =>
          try {
            camera.startStream()
            timers.startTimerAtFixedRate(Tick, period)
            ctx.log.info(s"FrameProcessor started, period=$period")
          }
          catch {
            case e: Exception => ctx.log.error(s"Failed to start camera stream: ${e.getMessage}")
          }
          running(timers, camera, publisher, prefix, ctx)

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
      ctx: ActorContext[Command]
  ): Behavior[Command] =
    Behaviors.receiveMessage {
      case Tick =>
        camera.getStreamFrame(5000).foreach { frame =>
          implicit val ec = ctx.executionContext
          publisher.publish(buildFrameEvent(prefix, frame))
          ctx.log.debug(s"Published cameraFrame ${frame.width}x${frame.height}")
        }
        Behaviors.same

      case Start(newPeriod) =>
        timers.cancel(Tick)
        timers.startTimerAtFixedRate(Tick, newPeriod)
        ctx.log.info(s"FrameProcessor period updated to $newPeriod")
        Behaviors.same

      case Stop =>
        timers.cancel(Tick)
        try camera.stopStream()
        catch { case e: Exception => ctx.log.error(s"Error stopping camera stream: ${e.getMessage}") }
        ctx.log.info("FrameProcessor stopped")
        idle(timers, camera, publisher, prefix)
    }
}
