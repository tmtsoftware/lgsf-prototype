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
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.SpawnProtocol
import org.apache.pekko.util.Timeout

import scala.concurrent.Await
import scala.concurrent.duration.*

object PacSetSimulationModeClientApp {

  final case class Settings(
      hcdPrefix: String = "LGSF.pac.prototypeHcd",
      clientPrefix: String = "LGSF.pac.modeClient",
      simulationMode: Boolean = true
  )

  def main(args: Array[String]): Unit = {
    val settings = parseArgs(args.toList)

    implicit val actorSystem: ActorSystem[SpawnProtocol.Command] =
      ActorSystemFactory.remote(SpawnProtocol(), "pac-set-simulation-mode-client")
    implicit val timeout: Timeout = 20.seconds

    try {
      val locationService = HttpLocationServiceFactory.makeLocalClient
      val connection      = PekkoConnection(ComponentId(Prefix(settings.hcdPrefix), ComponentType.HCD))
      val location = Await
        .result(locationService.resolve(connection, 20.seconds), 20.seconds)
        .getOrElse(throw new RuntimeException(s"HCD not found for prefix=${settings.hcdPrefix}"))

      val commandService = CommandServiceFactory.make(location)
      val sourcePrefix   = Prefix(settings.clientPrefix)

      val setup = Setup(sourcePrefix, CommandName("SetSimulationMode"))
        .add(PacPrototypeHcdHandlers.simulationModeKey.set(settings.simulationMode))

      val response = Await.result(commandService.submitAndWait(setup), 30.seconds)
      println(s"${setup.commandName.name}(simulationMode=${settings.simulationMode}) -> $response")
      response match {
        case _: CommandResponse.Completed =>
        case other                        => throw new RuntimeException(s"Command failed: $other")
      }
    }
    finally {
      actorSystem.terminate()
    }
  }

  private def parseArgs(args: List[String]): Settings = {
    @annotation.tailrec
    def loop(rest: List[String], cur: Settings): Settings = rest match {
      case "--hcd-prefix" :: v :: tail      => loop(tail, cur.copy(hcdPrefix = v))
      case "--client-prefix" :: v :: tail   => loop(tail, cur.copy(clientPrefix = v))
      case "--simulation-mode" :: v :: tail => loop(tail, cur.copy(simulationMode = parseBoolean(v)))
      case "--sim-mode" :: v :: tail        => loop(tail, cur.copy(simulationMode = parseBoolean(v)))
      case Nil                              => cur
      case other                            => throw new IllegalArgumentException(s"Unknown args: ${other.mkString(" ")}")
    }
    loop(args, Settings())
  }

  private def parseBoolean(value: String): Boolean = value.trim.toLowerCase match {
    case "true"  => true
    case "false" => false
    case other   => throw new IllegalArgumentException(s"Unsupported boolean value: $other (expected: true|false)")
  }
}
