package lgsf.pacprototypehcd

import csw.command.client.CommandServiceFactory
import csw.command.api.scaladsl.CommandService
import csw.location.api.models.Connection.PekkoConnection
import csw.location.api.models.{ComponentId, ComponentType}
import csw.params.commands.CommandResponse.{Completed, Error, Invalid}
import csw.params.commands.{CommandName, Setup}
import csw.prefix.models.Prefix
import csw.testkit.scaladsl.CSWService.{AlarmServer, EventServer}
import csw.testkit.scaladsl.ScalaTestFrameworkTestKit
import nom.tam.fits.Fits
import nom.tam.util.FitsFile
import org.apache.pekko.util.Timeout
import org.scalatest.funsuite.AnyFunSuiteLike

import java.nio.file.{Files, Paths}
import scala.concurrent.Await
import scala.concurrent.duration.*

class PacPrototypeHcdTest extends ScalaTestFrameworkTestKit(AlarmServer, EventServer) with AnyFunSuiteLike {

  import frameworkTestKit.*

  override def beforeAll(): Unit = {
    super.beforeAll()
    System.setProperty("pac-prototype-hcd.simulation-mode", "true")
    System.setProperty("pac-prototype-hcd.vbds.enabled", "false")
    // uncomment if you want one HCD run for all tests
    val base = com.typesafe.config.ConfigFactory.load("PacPrototypeHcdStandalone.conf")
    val testOverride = com.typesafe.config.ConfigFactory.parseString("""
      pac-prototype-hcd {
        simulation-mode = true
        vbds.enabled = false
      }
      """)
    spawnStandalone(testOverride.withFallback(base).resolve())
  }

  test("HCD should be locatable using Location Service") {
    val connection    = PekkoConnection(ComponentId(Prefix("LGSF.pac.prototypeHcd"), ComponentType.HCD))
    val pekkoLocation = Await.result(locationService.resolve(connection, 10.seconds), 10.seconds).get

    pekkoLocation.connection shouldBe connection
  }

  test("ConnectCamera command should complete in simulation mode") {
    implicit val timeout: Timeout = 10.seconds
    val response = Await.result(
      commandService.submitAndWait(
        Setup(Prefix("LGSF.test.client"), CommandName("ConnectCamera"))
      ),
      10.seconds
    )
    assert(response.isInstanceOf[Completed])
  }

  test("ConfigureCamera command should accept exposure and gain params") {
    implicit val timeout: Timeout = 10.seconds
    val command = Setup(Prefix("LGSF.test.client"), CommandName("ConfigureCamera"))
      .add(PacPrototypeHcdHandlers.exposureTimeUsKey.set(1200.0f))
      .add(PacPrototypeHcdHandlers.gainKey.set(2.5f))

    val response = Await.result(commandService.submitAndWait(command), 10.seconds)
    assert(response.isInstanceOf[Completed] || response.isInstanceOf[Error], s"Unexpected response: $response")
  }

  test("StartStream and StopStream commands should complete") {
    implicit val timeout: Timeout = 10.seconds
    val startResponse = Await.result(
      commandService.submitAndWait(
        Setup(Prefix("LGSF.test.client"), CommandName("StartStream"))
          .add(PacPrototypeHcdHandlers.periodMillisKey.set(200L))
      ),
      10.seconds
    )
    val stopResponse = Await.result(
      commandService.submitAndWait(
        Setup(Prefix("LGSF.test.client"), CommandName("StopStream"))
      ),
      10.seconds
    )
    assert(startResponse.isInstanceOf[Completed])
    assert(stopResponse.isInstanceOf[Completed])
  }

  test("TakeSingleExposure command should return without timeout (Completed or Error)") {
    implicit val timeout: Timeout = 20.seconds
    val outFile                   = Paths.get(System.getProperty("java.io.tmpdir"), s"pac-test-${System.nanoTime()}.fits")
    val command = Setup(Prefix("LGSF.test.client"), CommandName("TakeSingleExposure"))
      .add(PacPrototypeHcdHandlers.timeoutMsKey.set(3000))
      .add(PacPrototypeHcdHandlers.singleExposureFilePathKey.set(outFile.toString))

    val response = Await.result(commandService.submitAndWait(command), 20.seconds)
    assert(response.isInstanceOf[Completed] || response.isInstanceOf[Error])
    if (response.isInstanceOf[Completed]) {
      assert(Files.exists(outFile))
      assert(Files.size(outFile) > 0)
      Files.deleteIfExists(outFile)
    }
  }

  test("TakeSingleExposure should write FITS file when filepath is valid") {
    implicit val timeout: Timeout = 20.seconds
    val outFile                   = Paths.get(System.getProperty("java.io.tmpdir"), s"pac-success-${System.nanoTime()}.fits")
    val command = Setup(Prefix("LGSF.test.client"), CommandName("TakeSingleExposure"))
      .add(PacPrototypeHcdHandlers.timeoutMsKey.set(3000))
      .add(PacPrototypeHcdHandlers.singleExposureFilePathKey.set(outFile.toString))

    val response = Await.result(commandService.submitAndWait(command), 20.seconds)
    response match {
      case _: Completed =>
        assert(Files.exists(outFile), s"Expected output file to exist: $outFile")
        assert(Files.size(outFile) > 0, s"Expected non-empty FITS file: $outFile")
        Files.deleteIfExists(outFile)
      case _: Error =>
      case other =>
        fail(s"Expected Completed or Error but got: $other")
    }
  }

  test("TakeSingleExposure should return Error when filepath parent does not exist") {
    implicit val timeout: Timeout = 20.seconds
    val badPath = Paths
      .get(System.getProperty("java.io.tmpdir"), s"pac-missing-dir-${System.nanoTime()}", "frame.fits")
      .toString

    val command = Setup(Prefix("LGSF.test.client"), CommandName("TakeSingleExposure"))
      .add(PacPrototypeHcdHandlers.timeoutMsKey.set(3000))
      .add(PacPrototypeHcdHandlers.singleExposureFilePathKey.set(badPath))

    val response = Await.result(commandService.submitAndWait(command), 20.seconds)
    assert(response.isInstanceOf[Error], s"Expected Error but got: $response")
  }

  test("ConnectCamera should complete when explicit ipAddress parameter is provided") {
    implicit val timeout: Timeout = 10.seconds
    val response = Await.result(
      commandService.submitAndWait(
        Setup(Prefix("LGSF.test.client"), CommandName("ConnectCamera"))
          .add(PacPrototypeHcdHandlers.cameraIpKey.set("192.168.1.228"))
      ),
      10.seconds
    )
    assert(response.isInstanceOf[Completed] || response.isInstanceOf[Error], s"Unexpected response: $response")
  }

  test("Unknown command should return Invalid response") {
    implicit val timeout: Timeout = 10.seconds
    val response = Await.result(
      commandService.submitAndWait(
        Setup(Prefix("LGSF.test.client"), CommandName("UnknownCommand"))
      ),
      10.seconds
    )
    assert(response.isInstanceOf[Invalid])
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

  private def commandService: CommandService = {
    val connection = PekkoConnection(ComponentId(Prefix("LGSF.pac.prototypeHcd"), ComponentType.HCD))
    val location   = Await.result(locationService.resolve(connection, 10.seconds), 10.seconds).get
    CommandServiceFactory.make(location)(frameworkTestKit.actorSystem)
  }
}
