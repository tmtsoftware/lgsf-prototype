package lgsf.pacprototypehcd

import csw.location.api.models.Connection.PekkoConnection
import csw.location.api.models.{ComponentId, ComponentType}
import csw.prefix.models.Prefix
import csw.testkit.scaladsl.CSWService.{AlarmServer, EventServer}
import csw.testkit.scaladsl.ScalaTestFrameworkTestKit
import nom.tam.fits.Fits
import nom.tam.util.FitsFile
import org.scalatest.funsuite.AnyFunSuiteLike

import scala.concurrent.Await
import scala.concurrent.duration.*

class PacPrototypeHcdTest extends ScalaTestFrameworkTestKit(AlarmServer, EventServer) with AnyFunSuiteLike {

  import frameworkTestKit.*

  override def beforeAll(): Unit = {
    super.beforeAll()
    // uncomment if you want one HCD run for all tests
    spawnStandalone(com.typesafe.config.ConfigFactory.load("PacPrototypeHcdStandalone.conf"))
  }

  test("HCD should be locatable using Location Service") {
    val connection    = PekkoConnection(ComponentId(Prefix("LGSF.pac.prototypeHcd"), ComponentType.HCD))
    val pekkoLocation = Await.result(locationService.resolve(connection, 10.seconds), 10.seconds).get

    pekkoLocation.connection shouldBe connection
  }

  test("Simulated data test") {
    val width  = 1944
    val height = 1472
    val M      = 100.0 // Major FWHM
    val N      = 50.0  // Minor FWHM
    val x0     = 972.0
    val y0     = 736.0
    val Z      = 255.0
    val angle  = 30.0
    val sigma  = 1

    val result = SimulatedData.create2DGaussian(width, height, M, N, x0, y0, Z, angle, sigma)

    println(s"Generated ${result.length}x${result(0).length} Gaussian array.")
    println(f"Value at center peak: ${result(y0.toInt)(x0.toInt)}%.2f")

    val centroid = SimulatedData.getCentroid(result)
    println(f"Centroid: (${centroid._1}, ${centroid._2})")

    val fits = new Fits()
    fits.addHDU(Fits.makeHDU(result))

    val homePath = System.getProperty("user.home")
    val bf       = new FitsFile(s"$homePath/tmpData/tmp5.fits", "rw")

    fits.write(bf)
    bf.close()

    println("Fits file written")
  }
}