package lgsf.btoprototypedeploy

import csw.command.client.CommandServiceFactory
import csw.location.api.models.Connection.PekkoConnection
import csw.location.api.models.{ComponentId, ComponentType}
import csw.location.client.ActorSystemFactory
import csw.location.client.scaladsl.HttpLocationServiceFactory
import csw.params.commands.{CommandName, Setup}
import csw.prefix.models.Prefix
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.SpawnProtocol
import org.apache.pekko.util.Timeout

import scala.annotation.tailrec
import scala.concurrent.Await
import scala.concurrent.duration.*

object PacStreamClientApp {

  final case class Settings(
      prefix: String = "LGSF.pac.prototypeHcd",
      streamPeriodMillis: Long = 1000,
      streamDurationSeconds: Int = 10
  )

  def main(args: Array[String]): Unit = {
    val settings = parseArgs(args.toList)

    implicit val actorSystem: ActorSystem[SpawnProtocol.Command] =
      ActorSystemFactory.remote(SpawnProtocol(), "pac-prototype-hcd-verify")
    implicit val timeout: Timeout = 15.seconds

    try {
      val locationService = HttpLocationServiceFactory.makeLocalClient
      val connection      = PekkoConnection(ComponentId(Prefix(settings.prefix), ComponentType.HCD))
      val location = Await
        .result(locationService.resolve(connection, 20.seconds), 20.seconds)
        .getOrElse(throw new RuntimeException(s"HCD not found for prefix=${settings.prefix}"))

      val commandService = CommandServiceFactory.make(location)
      val sourcePrefix   = Prefix("LGSF.pac.verifyclient")

      def submit(setup: Setup): Unit = {
        val resp = Await.result(commandService.submitAndWait(setup), 20.seconds)
        println(s"${setup.commandName.name} -> $resp")
      }

      submit(Setup(sourcePrefix, CommandName("ConnectCamera")))
      submit(
        Setup(sourcePrefix, CommandName("StartStream"))
          .add(lgsf.pacprototypehcd.PacPrototypeHcdHandlers.periodMillisKey.set(settings.streamPeriodMillis))
      )

      Thread.sleep(settings.streamDurationSeconds.toLong * 1000L)

      submit(Setup(sourcePrefix, CommandName("StopStream")))
      submit(Setup(sourcePrefix, CommandName("DisconnectCamera")))
    }
    finally {
      actorSystem.terminate()
    }
  }

  private def parseArgs(args: List[String]): Settings = {
    @tailrec
    def loop(rest: List[String], cur: Settings): Settings = rest match {
      case "--prefix" :: v :: tail           => loop(tail, cur.copy(prefix = v))
      case "--period-ms" :: v :: tail        => loop(tail, cur.copy(streamPeriodMillis = v.toLong))
      case "--duration-seconds" :: v :: tail => loop(tail, cur.copy(streamDurationSeconds = v.toInt))
      case Nil                               => cur
      case other                             => throw new IllegalArgumentException(s"Unknown args: ${other.mkString(" ")}")
    }
    loop(args, Settings())
  }
}
