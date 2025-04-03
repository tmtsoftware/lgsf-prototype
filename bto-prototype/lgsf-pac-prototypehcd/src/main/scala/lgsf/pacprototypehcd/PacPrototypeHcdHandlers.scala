package lgsf.pacprototypehcd

import akka.actor.typed.scaladsl.ActorContext
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
 *  The Pre-Alignment Cameras (PACs) are used to determine the positions of the laser beams on the optical components
 *  (e.g., mirrors, lenses) using scattered light. Each HCD communicates with a PAC. The expected exposure frame rate
 *  will be 1 Hz or less for CoG calculations. The HCD can also stream raw pixels to the Engineering GUI for image
 *  display which is useful during manual alignment.
 *
 *  The optical component boundary is determined using ambient light by turning on a LED momentarily (triggered by
 *  PAC strobe signal). Each PAC may image one or multiple optical components. In the latter case, the frame will be
 *  split into multiple regions based on each component boundary information. Within each region, there should be only
 *  a single beam (or overlapped beams treated as a single beam), from which the CoG (or matched filters if needed)
 *  will be calculated to determine the beam position (in pixel coordinates). The total intensity per region is also
 *  calculated which is converted to laser power from configured throughput value. The PAC HCD computes beam position
 *  measurements either in the local coordinate (defined on the optical surface along minor and major axis) or in the
 *  reference coordinate if a mapping matrix is provided. The mirror tip/tilt to PAC measurement interaction matrix
 *  and its inverse link the control and sensor together.
 *
 *  The algorithm is described in Section 3.1 CoG Measurement of LGSF Adjustment and Control Algorithms
 *    https://docushare.tmt.org/docushare/dsweb/Get/Document-67700
 *
 *  LGSF Common HCD Framework Custom Actors:
 *
 *  - Command Sender: used by the handlers to send commands to HCDs to avoid blocking.
 *  - Group Handlers: handle more complex commands, or calculations, etc. There may be multiple such handlers with
 *      each handling a specific type of calculation, such as closed loop control, or moving mechanisms for
 *      calibrations.
 *  - Hardware Command Senders: convert received commands to hardware units and send to the hardware. There may be
 *      multiple such senders if the hardware supports multiple independent axes or functionalities.
 *  - Hardware receivers: receive status update and sensor readings from the hardware. There may also be multiple
 *      such receivers.
 *  - State Manager: manages the state and is responsible for publishing Events and currentState.
 *
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
