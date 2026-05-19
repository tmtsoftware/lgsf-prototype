package lgsf.pacprototypehcd

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import csw.command.client.messages.TopLevelActorMessage
import csw.event.api.scaladsl.EventPublisher
import csw.framework.models.CswContext
import csw.framework.scaladsl.ComponentHandlers
import csw.location.api.models.TrackingEvent
import csw.params.commands.CommandIssue.UnsupportedCommandIssue
import csw.params.commands.CommandResponse.*
import csw.params.commands.{ControlCommand, Setup}
import csw.params.core.generics.{Key, KeyType}
import csw.time.core.models.UTCTime
import csw.params.core.models.Id
import com.typesafe.config.Config
import java.time.LocalDateTime
import java.time.Duration
import java.time.format.DateTimeFormatter
import java.nio.file.{Files, Paths}

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
 *   SetSimulationMode  simulationMode: Boolean (required)
 *   LoadConfig         configPath: String (required)
 *
 * In simulation mode (pac-prototype-hcd.simulation-mode = true in application.conf) the
 * native library is not loaded and synthetic Gaussian spot images are produced instead.
 */
class PacPrototypeHcdHandlers(ctx: ActorContext[TopLevelActorMessage], cswCtx: CswContext)
    extends ComponentHandlers(ctx, cswCtx) {

  import cswCtx.*
  implicit val ec: ExecutionContextExecutor = ctx.executionContext

  private val log                    = loggerFactory.getLogger
  private var config                 = ConfigFactory.load()
  private val simulationModePropName = "pac-prototype-hcd.simulation-mode"
  private val simulationModePropRaw  = Option(System.getProperty(simulationModePropName))
  private val initialSimulationMode = simulationModePropRaw match {
    case Some(v) => v.trim.equalsIgnoreCase("true")
    case None    => config.getBoolean(simulationModePropName)
  }
  private val defaultSimulationMode = config.getBoolean(simulationModePropName)
  private var simulationMode        = initialSimulationMode

  private def errorDetails(ex: Throwable): String = {
    val top = ex.getStackTrace.headOption.map(_.toString).getOrElse("no-stack")
    s"${ex.getClass.getSimpleName}: ${ex.getMessage}; at $top"
  }

  private def createCamera(mode: Boolean): PacCamera = {
    val width  = config.getInt("camera.width")
    val height = config.getInt("camera.height")
    val protocol: PacCameraProtocol =
      if (mode) new PacCameraSimulated(width, height)
      else new PacCameraNative()
    new PacCamera(protocol)
  }
  private var camera: PacCamera = createCamera(simulationMode)

  private var vbdsPublisherRef: Option[ActorRef[VbdsPublisherActor.Command]] = None
  private var frameProcessorRef: Option[ActorRef[FrameProcessor.Command]]    = None

  private def stopAndClearFrameProcessor(): Unit = {
    frameProcessorRef.foreach { ref =>
      ref ! FrameProcessor.Stop
      ctx.stop(ref)
    }
    frameProcessorRef = None
  }

  private def stopAndClearVbdsPublisher(): Unit = {
    vbdsPublisherRef.foreach(ctx.stop)
    vbdsPublisherRef = None
  }

  private def getOrCreateVbdsPublisher(): Option[ActorRef[VbdsPublisherActor.Command]] = {
    if (vbdsPublisherRef.isEmpty) {
      try {
        if (config.hasPath("pac-prototype-hcd.vbds")) {
          val vbdsCfg = config.getConfig("pac-prototype-hcd.vbds")
          val settings = VbdsPublisherActor.Settings(
            enabled = vbdsCfg.getBoolean("enabled"),
            host = vbdsCfg.getString("host"),
            port = vbdsCfg.getInt("port"),
            streamName = vbdsCfg.getString("stream-name"),
            contentType = vbdsCfg.getString("content-type"),
            autoCreateStream = vbdsCfg.getBoolean("auto-create-stream"),
            requestTimeout = Duration.ofMillis(vbdsCfg.getLong("request-timeout-millis"))
          )
          if (settings.enabled) vbdsPublisherRef = Some(ctx.spawn(VbdsPublisherActor(settings), "vbdsPublisher"))
        }
      }
      catch {
        case ex: Exception =>
          log.error(s"VBDS publisher init failed: ${errorDetails(ex)}")
      }
    }
    vbdsPublisherRef
  }

  private def getOrCreateFrameProcessor(): ActorRef[FrameProcessor.Command] = {
    frameProcessorRef.getOrElse {
      val ref = ctx.spawn(
        FrameProcessor(camera, eventService.defaultPublisher, componentInfo.prefix, getOrCreateVbdsPublisher()),
        "frameProcessor"
      )
      frameProcessorRef = Some(ref)
      ref
    }
  }

  private def parseConfigFromPath(path: String): Config = {
    val trimmed = path.trim
    if (trimmed.isEmpty) throw new IllegalArgumentException("configPath cannot be empty")

    def isFilesystemPath(s: String): Boolean = {
      val p = Paths.get(s)
      p.isAbsolute || s.startsWith(".") || s.contains("/") || s.contains("\\")
    }

    if (isFilesystemPath(trimmed)) {
      val resolved = {
        if (trimmed.startsWith("~")) Paths.get(System.getProperty("user.home") + trimmed.drop(1))
        else Paths.get(trimmed)
      }.toAbsolutePath.normalize()
      if (!Files.exists(resolved)) throw new IllegalArgumentException(s"Config file not found: $resolved")
      ConfigFactory.parseFile(resolved.toFile).resolve()
    }
    else {
      val exact = ConfigFactory.parseResources(trimmed).resolve()
      if (!exact.isEmpty) exact
      else {
        val base = if (trimmed.endsWith(".conf")) trimmed.stripSuffix(".conf") else trimmed
        val any  = ConfigFactory.parseResourcesAnySyntax(base).resolve()
        if (!any.isEmpty) any
        else throw new IllegalArgumentException(s"Config resource not found: $trimmed")
      }
    }
  }

  private def applyRuntimeConfig(overrideConfig: Config): Unit = {
    val merged  = overrideConfig.withFallback(config).resolve()
    val oldMode = simulationMode
    val newMode = merged.getBoolean(simulationModePropName)
    config = merged

    // Apply runtime changes safely: stop stream and rebuild processor/camera as needed.
    stopAndClearFrameProcessor()
    stopAndClearVbdsPublisher()

    if (newMode != oldMode) {
      camera.disconnect()
      simulationMode = newMode
      camera = createCamera(simulationMode)
      log.info(s"LoadConfig applied simulation mode change: $oldMode -> $newMode; protocol=${camera.protocolName}")
    }
    else {
      log.info(s"LoadConfig applied with unchanged simulation mode: $simulationMode")
    }
  }

  override def initialize(): Unit =
    log.info("Initializing pac.prototypeHcd...")
    log.info(s"simulation-mode=$simulationMode protocol=${camera.protocolName}")
    log.info(
      s"simulation-mode config diagnostics: default=$defaultSimulationMode, system-property-present=${simulationModePropRaw.isDefined}, system-property-value=${simulationModePropRaw.getOrElse("<unset>")}"
    )
    log.info(s"default camera-ip=${config.getString("pac-prototype-hcd.camera-ip")}")
    log.info(s"default frame-period-millis=${config.getLong("pac-prototype-hcd.frame-period-millis")}")
    if (config.hasPath("pac-prototype-hcd.vbds")) {
      val vbdsCfg = config.getConfig("pac-prototype-hcd.vbds")
      log.info(
        s"vbds config: enabled=${vbdsCfg.getBoolean("enabled")}, host=${vbdsCfg.getString("host")}, port=${vbdsCfg.getInt("port")}, stream-name=${vbdsCfg.getString("stream-name")}, content-type=${vbdsCfg.getString("content-type")}, auto-create-stream=${vbdsCfg.getBoolean("auto-create-stream")}, request-timeout-millis=${vbdsCfg.getLong("request-timeout-millis")}"
      )
    }
    else {
      log.warn("vbds config: section pac-prototype-hcd.vbds not found")
    }

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
          .get(PacPrototypeHcdHandlers.cameraIpKey)
          .map(_.head)
          .getOrElse(config.getString("pac-prototype-hcd.camera-ip"))
        log.debug(s"ConnectCamera received runId=$runId ip=$ip")
        try {
          log.info(s"Connecting camera using protocol=${camera.protocolName} ip=$ip")
          camera.connect(ip)
          log.info(s"Camera connected: ip=$ip protocol=${camera.protocolName}")
          Completed(runId)
        }
        catch {
          case e: Exception =>
            log.error(s"Camera connect failed: ${errorDetails(e)}")
            Error(runId, e.getMessage)
        }

      case _: Setup if controlCommand.commandName.name == "DisconnectCamera" =>
        log.debug(s"DisconnectCamera received runId=$runId")
        try {
          camera.disconnect()
          log.info("Camera disconnected")
          Completed(runId)
        }
        catch {
          case ex: Exception =>
            log.error(s"DisconnectCamera failed: ${errorDetails(ex)}")
            Error(runId, ex.getMessage)
        }

      case setup: Setup if setup.commandName.name == "ConfigureCamera" =>
        try {
          log.debug(
            s"ConfigureCamera received runId=$runId exposure=${setup.get(PacPrototypeHcdHandlers.exposureTimeUsKey).map(_.head)} gain=${setup.get(PacPrototypeHcdHandlers.gainKey).map(_.head)}"
          )
          setup.get(PacPrototypeHcdHandlers.exposureTimeUsKey).map(_.head).foreach { t =>
            camera.setExposureTime(t.toDouble)
            log.info(s"ExposureTime set to ${t}us")
          }
          setup.get(PacPrototypeHcdHandlers.gainKey).map(_.head).foreach { g =>
            camera.setGain(g.toDouble)
            log.info(s"Gain set to $g")
          }
          Completed(runId)
        }
        catch {
          case ex: Exception =>
            log.error(s"ConfigureCamera failed: ${errorDetails(ex)}")
            Error(runId, ex.getMessage)
        }

      case setup: Setup if setup.commandName.name == "StartStream" =>
        try {
          val period = setup
            .get(PacPrototypeHcdHandlers.periodMillisKey)
            .map(_.head)
            .getOrElse(config.getLong("pac-prototype-hcd.frame-period-millis"))
          log.debug(s"StartStream received runId=$runId periodMillis=$period")
          getOrCreateFrameProcessor() ! FrameProcessor.Start(period.millis)
          log.info(s"Stream started, period=${period}ms")
          Completed(runId)
        }
        catch {
          case ex: Exception =>
            log.error(s"StartStream failed: ${errorDetails(ex)}")
            Error(runId, ex.getMessage)
        }

      case _: Setup if controlCommand.commandName.name == "StopStream" =>
        try {
          log.debug(s"StopStream received runId=$runId")
          frameProcessorRef.foreach(_ ! FrameProcessor.Stop)
          log.info("Stream stopped")
          Completed(runId)
        }
        catch {
          case ex: Exception =>
            log.error(s"StopStream failed: ${errorDetails(ex)}")
            Error(runId, ex.getMessage)
        }

      case setup: Setup if setup.commandName.name == "TakeSingleExposure" =>
        try {
          setup.get(PacPrototypeHcdHandlers.exposureTimeUsKey).map(_.head).foreach { t =>
            camera.setExposureTime(t.toDouble)
          }
          val timeout = setup
            .get(PacPrototypeHcdHandlers.timeoutMsKey)
            .map(_.head)
            .getOrElse(5000)

          val formatter       = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
          val timestamp       = LocalDateTime.now().format(formatter)
          val baseName        = "pac"
          val homePath        = System.getProperty("user.home")
          val defaultPath     = s"${homePath}/tmpData"
          val defaultFilePath = s"${defaultPath}/${baseName}_${timestamp}.fits"

          val filePath = setup
            .get(PacPrototypeHcdHandlers.singleExposureFilePathKey)
            .map(_.head)
            .getOrElse(defaultFilePath)
          log.debug(s"TakeSingleExposure received runId=$runId timeoutMs=$timeout filePath=$filePath")
          log.info(s"Taking single exposure using protocol=${camera.protocolName} timeoutMs=$timeout")
          camera.takeSingleExposure(timeout) match {
            case Some(frame) =>
              log.info(
                s"Single exposure acquired: ${frame.width}x${frame.height} ts=${frame.timestamp} bytes=${frame.data.length} protocol=${camera.protocolName}"
              )
              FrameProcessor.writeFrameToFits(frame, filePath)
              log.debug(s"Wrote single exposure FITS to filePath=$filePath")
              getOrCreateVbdsPublisher().foreach(_ ! VbdsPublisherActor.PublishFrame(frame))
              Completed(runId)
            case None =>
              log.error("Single exposure timed out or failed")
              Error(runId, "Frame acquisition failed")
          }
        }
        catch {
          case ex: Exception =>
            log.error(s"Single exposure failed: ${errorDetails(ex)}")
            Error(runId, ex.getMessage)
        }

      case setup: Setup if setup.commandName.name == "SetSimulationMode" =>
        setup.get(PacPrototypeHcdHandlers.simulationModeKey).map(_.head) match {
          case None =>
            Error(runId, "Missing required parameter: simulationMode")

          case Some(newMode) if newMode == simulationMode =>
            log.info(s"SetSimulationMode received: already in requested mode simulationMode=$simulationMode")
            Completed(runId)

          case Some(newMode) =>
            log.info(
              s"SetSimulationMode switching mode: from simulationMode=$simulationMode (${camera.protocolName}) to simulationMode=$newMode"
            )
            try {
              stopAndClearFrameProcessor()
              camera.disconnect()
              simulationMode = newMode
              camera = createCamera(simulationMode)
              log.info(s"SetSimulationMode complete: simulationMode=$simulationMode protocol=${camera.protocolName}")
              Completed(runId)
            }
            catch {
              case ex: Exception =>
                log.error(s"SetSimulationMode failed: ${errorDetails(ex)}")
                Error(runId, ex.getMessage)
            }
        }

      case setup: Setup if setup.commandName.name == "LoadConfig" =>
        setup.get(PacPrototypeHcdHandlers.configPathKey).map(_.head) match {
          case None =>
            Error(runId, "Missing required parameter: configPath")
          case Some(path) =>
            try {
              log.info(s"LoadConfig requested: path=$path")
              val loaded = parseConfigFromPath(path)
              applyRuntimeConfig(loaded)
              log.info(s"LoadConfig completed successfully: path=$path")
              Completed(runId)
            }
            catch {
              case ex: Exception =>
                log.error(s"LoadConfig failed: ${errorDetails(ex)}")
                Error(runId, ex.getMessage)
            }
        }

      case _ =>
        log.warn(s"Unsupported command received runId=$runId name=${controlCommand.commandName.name}")
        Invalid(runId, UnsupportedCommandIssue(s"Unknown command: ${controlCommand.commandName.name}"))
    }

  override def onOneway(runId: Id, controlCommand: ControlCommand): Unit = {}

  override def onShutdown(): Unit = {
    frameProcessorRef.foreach(_ ! FrameProcessor.Stop)
    camera.disconnect()
  }

  override def onGoOffline(): Unit = {}

  override def onGoOnline(): Unit = {}

  override def onDiagnosticMode(startTime: UTCTime, hint: String): Unit = {}

  override def onOperationsMode(): Unit = {}
}

object PacPrototypeHcdHandlers {
  val cameraIpKey: Key[String]               = KeyType.StringKey.make("ipAddress")
  val configPathKey: Key[String]             = KeyType.StringKey.make("configPath")
  val singleExposureFilePathKey: Key[String] = KeyType.StringKey.make("filepath")
  val simulationModeKey: Key[Boolean]        = KeyType.BooleanKey.make("simulationMode")
  val exposureTimeUsKey: Key[Float]          = KeyType.FloatKey.make("exposureTimeUs")
  val gainKey: Key[Float]                    = KeyType.FloatKey.make("gain")
  val periodMillisKey: Key[Long]             = KeyType.LongKey.make("periodMillis")
  val timeoutMsKey: Key[Int]                 = KeyType.IntKey.make("timeoutMs")
}
