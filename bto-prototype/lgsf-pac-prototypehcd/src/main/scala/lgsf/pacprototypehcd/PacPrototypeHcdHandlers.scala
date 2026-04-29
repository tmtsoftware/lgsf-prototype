package lgsf.pacprototypehcd

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior, Scheduler}
import csw.command.client.messages.TopLevelActorMessage
import csw.framework.models.CswContext
import csw.framework.scaladsl.ComponentHandlers
import csw.location.api.models.TrackingEvent
import csw.params.commands.CommandIssue.UnsupportedCommandIssue
import csw.params.commands.CommandResponse.*
import csw.params.commands.{ControlCommand, Setup}
import csw.time.core.models.UTCTime
import csw.params.core.models.Id

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration.*
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import java.io.ByteArrayOutputStream

/**
 * Domain specific logic should be written in below handlers.
 * This handlers gets invoked when component receives messages/commands from other component/entity.
 * For example, if one component sends Submit(Setup(args)) command to PacPrototypeHcd,
 * This will be first validated in the supervisor and then forwarded to Component TLA which first invokes validateCommand hook
 * and if validation is successful, then onSubmit hook gets invoked.
 * You can find more information on this here : https://tmtsoftware.github.io/csw/commons/framework.html
 *
 * Pre-alignment Camera Imperx C1911
 * ...
 */
class PacPrototypeHcdHandlers(ctx: ActorContext[TopLevelActorMessage], cswCtx: CswContext) extends ComponentHandlers(ctx, cswCtx) {

  import cswCtx._
  implicit val ec: ExecutionContextExecutor = ctx.executionContext
  private val log                           = loggerFactory.getLogger
  
  private val config = ConfigFactory.load()

  private val frameProcessor: ActorRef[FrameProcessor.Command] =
    ctx.spawn(FrameProcessor(), "frameProcessor")

  override def initialize(): Unit = {
    log.info("Initializing pac.prototypeHcd...")
  }

  override def onLocationTrackingEvent(trackingEvent: TrackingEvent): Unit = {}

  override def validateCommand(runId: Id, controlCommand: ControlCommand): ValidateCommandResponse = {
    controlCommand match {
      case _: Setup => Accepted(runId)
      case _        => Invalid(runId, UnsupportedCommandIssue("Unsupported command type"))
    }
  }

  override def onSubmit(runId: Id, controlCommand: ControlCommand): SubmitResponse = {
    controlCommand match {
      case setup: Setup if setup.commandName.name == "GenerateSimulatedImage" =>
        val image = generateSimulatedImage()
        log.info(s"Generated simulated image: width=${image.getWidth}, height=${image.getHeight}")
        Completed(runId)

      case setup: Setup if setup.commandName.name == "StartFrameProcessor" =>
        val periodMillis = config.getLong("pac-prototype-hcd.frame-period-millis")
        frameProcessor ! FrameProcessor.Start(periodMillis.millis)
        Completed(runId)

      case _ =>
        Invalid(runId, UnsupportedCommandIssue("Unsupported command"))
    }
  }

  override def onOneway(runId: Id, controlCommand: ControlCommand): Unit = {}

  override def onShutdown(): Unit = {}

  override def onGoOffline(): Unit = {}

  override def onGoOnline(): Unit = {}

  override def onDiagnosticMode(startTime: UTCTime, hint: String): Unit = {}

  override def onOperationsMode(): Unit = {}

  private def generateSimulatedImage(): BufferedImage = {
    val width = config.getInt("camera.width")
    val height = config.getInt("camera.height")
    
    val data = SimulatedData.create2DGaussian(
      width = width,
      height = height,
      majorAxisFWHM = 100.0,
      minorAxisFWHM = 50.0,
      centerX = width / 2.0,
      centerY = height / 2.0,
      peakMax = 255.0,
      rotationDegrees = 30.0,
      readNoiseSigma = 1.0
    )

    val image  = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY)
    val raster = image.getRaster

    for (y <- 0 until height; x <- 0 until width) {
      val value = math.max(0, math.min(255, data(y)(x).round.toInt))
      raster.setSample(x, y, 0, value)
    }

    image
  }

}

object FrameProcessor {
  sealed trait Command
  final case class Start(period: FiniteDuration) extends Command
  private case object Tick extends Command

  def apply(): Behavior[Command] =
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        Behaviors.receiveMessage {
          case Start(period) =>
            timers.startTimerAtFixedRate(Tick, period)
            context.log.info(s"FrameProcessor started with period = $period")
            running(timers, period)
        }
      }
    }

  private def running(timers: org.apache.pekko.actor.typed.scaladsl.TimerScheduler[Command],
                      period: FiniteDuration): Behavior[Command] =
    Behaviors.receive { (context, message) =>
      message match {
        case Tick =>
          val frame = getFrame()
          context.log.info(s"Received frame: rows=${frame.length}, cols=${if (frame.nonEmpty) frame.head.length else 0}")
          Behaviors.same

        case Start(newPeriod) =>
          timers.cancel(Tick)
          timers.startTimerAtFixedRate(Tick, newPeriod)
          context.log.info(s"FrameProcessor period updated to $newPeriod")
          running(timers, newPeriod)
      }
    }

  // TODO: implement frame acquisition
  private def getFrame(): Array[Array[Double]] =
    Array.empty[Array[Double]]
}