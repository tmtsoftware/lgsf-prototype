package lgsf.btoprototypedeploy

import csw.command.client.CommandServiceFactory
import csw.location.api.models.Connection.PekkoConnection
import csw.location.api.models.{ComponentId, ComponentType}
import csw.location.client.ActorSystemFactory
import csw.location.client.scaladsl.HttpLocationServiceFactory
import csw.params.commands.CommandResponse
import csw.params.commands.{CommandName, Setup}
import csw.prefix.models.Prefix
import lgsf.pacprototypehcd.PacPrototypeHcdHandlers
import org.apache.pekko.actor.typed.SpawnProtocol
import org.apache.pekko.util.Timeout

import scala.concurrent.Await
import scala.concurrent.duration.*

object PacTakeSingleExposureClientApp {

  sealed trait CameraMode
  object CameraMode {
    case object Real      extends CameraMode
    case object Simulated extends CameraMode

    def fromString(value: String): CameraMode = value.trim.toLowerCase match {
      case "real"      => Real
      case "sim"       => Simulated
      case "simulated" => Simulated
      case other => throw new IllegalArgumentException(s"Unsupported --camera-mode value: $other (expected: real|simulated)")
    }
  }

  final case class Settings(
      hcdPrefix: String = "LGSF.pac.prototypeHcd",
      clientPrefix: String = "LGSF.pac.singleExposureClient",
      cameraMode: CameraMode = CameraMode.Simulated,
      cameraIp: String = "192.168.0.21",
      timeoutMs: Int = 5000,
      exposureTimeUs: Option[Float] = None,
      outputFile: Option[String] = None,
      disconnectAfter: Boolean = true
  )

  def main(args: Array[String]): Unit = {
    val settings = parseArgs(args.toList)

    implicit val actorSystem      = ActorSystemFactory.remote(SpawnProtocol(), "pac-single-exposure-client")
    implicit val timeout: Timeout = 20.seconds

    try {
      val locationService = HttpLocationServiceFactory.makeLocalClient
      val connection      = PekkoConnection(ComponentId(Prefix(settings.hcdPrefix), ComponentType.HCD))
      val location = Await
        .result(locationService.resolve(connection, 20.seconds), 20.seconds)
        .getOrElse(throw new RuntimeException(s"HCD not found for prefix=${settings.hcdPrefix}"))

      val commandService = CommandServiceFactory.make(location)
      val sourcePrefix   = Prefix(settings.clientPrefix)

      val connectCommand = settings.cameraMode match {
        case CameraMode.Real =>
          Setup(sourcePrefix, CommandName("ConnectCamera"))
            .add(PacPrototypeHcdHandlers.cameraIpKey.set(settings.cameraIp))
        case CameraMode.Simulated =>
          Setup(sourcePrefix, CommandName("ConnectCamera"))
      }

      submitAndPrint(commandService, connectCommand)

      var exposureCommand = Setup(sourcePrefix, CommandName("TakeSingleExposure"))
        .add(PacPrototypeHcdHandlers.timeoutMsKey.set(settings.timeoutMs))

      settings.exposureTimeUs.foreach { value =>
        exposureCommand = exposureCommand.add(PacPrototypeHcdHandlers.exposureTimeUsKey.set(value))
      }
      settings.outputFile.foreach { value =>
        exposureCommand = exposureCommand.add(PacPrototypeHcdHandlers.singleExposureFilePathKey.set(value))
      }

      submitAndPrint(commandService, exposureCommand)

      if (settings.disconnectAfter) {
        submitAndPrint(commandService, Setup(sourcePrefix, CommandName("DisconnectCamera")))
      }

      settings.cameraMode match {
        case CameraMode.Real =>
          println(s"Requested REAL camera mode with ip=${settings.cameraIp}")
        case CameraMode.Simulated =>
          println("Requested SIMULATED camera mode")
      }
      println("Note: actual simulation/real behavior is controlled by the HCD configuration (pac-prototype-hcd.simulation-mode).")
    }
    finally {
      actorSystem.terminate()
    }
  }

  private def submitAndPrint(commandService: csw.command.api.scaladsl.CommandService, setup: Setup)(implicit
      timeout: Timeout
  ): Unit = {
    val response = Await.result(commandService.submitAndWait(setup), 30.seconds)
    println(s"${setup.commandName.name} -> $response")
    response match {
      case _: CommandResponse.Completed =>
      case other                        => throw new RuntimeException(s"Command failed: $other")
    }
  }

  private def parseArgs(args: List[String]): Settings = {
    @annotation.tailrec
    def loop(rest: List[String], cur: Settings): Settings = rest match {
      case "--hcd-prefix" :: v :: tail    => loop(tail, cur.copy(hcdPrefix = v))
      case "--client-prefix" :: v :: tail => loop(tail, cur.copy(clientPrefix = v))
      case "--camera-mode" :: v :: tail   => loop(tail, cur.copy(cameraMode = CameraMode.fromString(v)))
      case "--camera-ip" :: v :: tail     => loop(tail, cur.copy(cameraIp = v))
      case "--timeout-ms" :: v :: tail    => loop(tail, cur.copy(timeoutMs = v.toInt))
      case "--exposure-us" :: v :: tail   => loop(tail, cur.copy(exposureTimeUs = Some(v.toFloat)))
      case "--output-file" :: v :: tail   => loop(tail, cur.copy(outputFile = Some(v)))
      case "--no-disconnect" :: tail      => loop(tail, cur.copy(disconnectAfter = false))
      case Nil                            => cur
      case other                          => throw new IllegalArgumentException(s"Unknown args: ${other.mkString(" ")}")
    }
    loop(args, Settings())
  }
}
