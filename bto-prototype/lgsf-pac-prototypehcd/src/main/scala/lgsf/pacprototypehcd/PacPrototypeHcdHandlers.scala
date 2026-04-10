package lgsf.pacprototypehcd

import org.apache.pekko.actor.typed.scaladsl.ActorContext
import csw.command.client.messages.TopLevelActorMessage
import csw.framework.models.CswContext
import csw.framework.scaladsl.ComponentHandlers
import csw.location.api.models.TrackingEvent
import csw.params.commands.CommandResponse._
import csw.params.commands.ControlCommand
import csw.time.core.models.UTCTime
import csw.params.core.models.Id

import scala.concurrent.ExecutionContextExecutor

/**
 * Domain specific logic should be written in below handlers.
 * This handlers gets invoked when component receives messages/commands from other component/entity.
 * For example, if one component sends Submit(Setup(args)) command to PacPrototypeHcd,
 * This will be first validated in the supervisor and then forwarded to Component TLA which first invokes validateCommand hook
 * and if validation is successful, then onSubmit hook gets invoked.
 * You can find more information on this here : https://tmtsoftware.github.io/csw/commons/framework.html
 *
 * Pre-alignment Camera Imperx C1911
 * "Resolution	1944 x 1472 (2.86 MP)
 * Sensor	Sony Pregius IMX429 CMOS Color/Mono
 * Sensor Format	2/3"" optical format
 * Pixel Size	4.5 microns square
 * Interfaces	GigE Vision® with Power over Ethernet (PoE)
 * Frame Rate	40 fps (8-bit), 20 fps (10-bit/12-bit unpacked), 26 fps (10-bit/12-bit packed)
 * Output Bit Depth	8-bit, 10-bit, 12-bit
 * Sensor Digitization	12-bit
 * Shutter	Global shutter (GS)
 * Shutter Speed	1 μs/step, 5 μs to 16 s
 * Dynamic Range	77 dB
 * Analog/Digital Gain Control	Manual, Auto; 0 dB - 48 dB, 480 steps
 * Digital Gain	1x (0 dB) to 4x (12 dB) with a precision of 0.001x
 * Digital Offset	-512 to +511, 1 step increments
 * AEC/AGC	Yes
 * Exposure Control	Off, manual, external, auto
 * Regions of Interest (ROI)	2 ROI
 * Trigger Inputs	External, pulse generator, software
 * Trigger Options	Edge, pulse width, trigger filter, trigger delay, debounce
 * Trigger Modes	Free run, standard, fast
 * External Inputs/Outputs	1 IN (OPTO) / 2 OUT (OPTO, TTL)
 * Lens Mount	C-Mount
 * Dimensions	48.5 mm (W) x 42.0 mm (H) x 61 mm (L) (without lens tube and connectors)
 * Weight	196 g (without lens tube)
 * Power	12 V DC (6 V-30 V), 1.5 A inrush; PoE (IEEE 802.3af / IEEE 802.3at)
 * Environmental	-30 °C to +75 °C Operating (-40 °C to +85 °C tested); -40 °C to +85 °C Storage
 * Vibration, Shock	20G (20 - 200 Hz XYZ) /100G
 * Regulatory	FCC Part 15 Class A, CE, RoHS"
 */
class PacPrototypeHcdHandlers(ctx: ActorContext[TopLevelActorMessage], cswCtx: CswContext) extends ComponentHandlers(ctx, cswCtx) {

  import cswCtx._
  implicit val ec: ExecutionContextExecutor = ctx.executionContext
  private val log                           = loggerFactory.getLogger

  override def initialize(): Unit = {
    log.info("Initializing pac.prototypeHcd...")
  }

  override def onLocationTrackingEvent(trackingEvent: TrackingEvent): Unit = {}

  override def validateCommand(runId: Id, controlCommand: ControlCommand): ValidateCommandResponse = Accepted(runId)

  override def onSubmit(runId: Id, controlCommand: ControlCommand): SubmitResponse = Completed(runId)

  override def onOneway(runId: Id, controlCommand: ControlCommand): Unit = {}

  override def onShutdown(): Unit = {}

  override def onGoOffline(): Unit = {}

  override def onGoOnline(): Unit = {}

  override def onDiagnosticMode(startTime: UTCTime, hint: String): Unit = {}

  override def onOperationsMode(): Unit = {}

}
