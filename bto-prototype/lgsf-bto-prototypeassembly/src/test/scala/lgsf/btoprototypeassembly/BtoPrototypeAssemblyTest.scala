package lgsf.btoprototypeassembly

import csw.location.api.models.Connection.PekkoConnection
import csw.prefix.models.Prefix
import csw.location.api.models.{ComponentId, ComponentType}
import csw.testkit.scaladsl.CSWService.{AlarmServer, EventServer}
import csw.testkit.scaladsl.ScalaTestFrameworkTestKit
import org.scalatest.funsuite.AnyFunSuiteLike

import scala.concurrent.Await
import scala.concurrent.duration._

class BtoPrototypeAssemblyTest extends ScalaTestFrameworkTestKit(AlarmServer, EventServer) with AnyFunSuiteLike {

  import frameworkTestKit._

  override def beforeAll(): Unit = {
    super.beforeAll()
    // uncomment if you want one Assembly run for all tests
    spawnStandalone(com.typesafe.config.ConfigFactory.load("BtoPrototypeAssemblyStandalone.conf"))
  }

  test("Assembly should be locatable using Location Service") {
    val connection = PekkoConnection(ComponentId(Prefix("LGSF.bto.prototypeAssembly"), ComponentType.Assembly))
    val pekkoLocation = Await.result(locationService.resolve(connection, 10.seconds), 10.seconds).get

    pekkoLocation.connection shouldBe connection
  }
}