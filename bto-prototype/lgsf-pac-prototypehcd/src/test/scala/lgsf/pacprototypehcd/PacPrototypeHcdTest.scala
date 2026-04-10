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
import scala.math.*
import scala.util.Random

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

  test ("Sample data test") {
    val width = 1944
    val height = 1472
    val M = 100.0 // Major FWHM
    val N = 50.0 // Minor FWHM
    val x0 = 972.0
    val y0 = 736.0
    val Z = 255.0
    val angle = 30.0
    val sigma = 1

    val result = GaussianArray.create2DGaussian(width, height, M, N, x0, y0, Z, angle, sigma)

    println(s"Generated ${result.length}x${result(0).length} Gaussian array.")
    println(f"Value at center peak: ${result(y0.toInt)(x0.toInt)}%.2f")
    val centroid = getCentroid(result)
    println(f"Centroid: (${centroid._1}, ${centroid._2})")

    val fits = new Fits()
    fits.addHDU(Fits.makeHDU(result))
    val bf = new FitsFile("/Users/weiss/tmpData/tmp5.fits", "rw")
    fits.write(bf)
    bf.close()
    println("Fits file written")
  }

  object GaussianArray {
    /*
      To create a 2D Gaussian distribution with rotation in Java, you need to transform the grid coordinates
      based on the rotation angle $p$ and then apply the Gaussian function using the relationship between FWHM
      and the standard deviation $\sigma$.

      Mathematical Foundation:

      The relationship between Full Width at Half Maximum (FWHM) and the standard deviation ($\sigma$) is:

      $$\sigma = \frac{\text{FWHM}}{2\sqrt{2\ln 2}} \approx \frac{\text{FWHM}}{2.35482}$$

      For a rotated Gaussian, we calculate the relative coordinates $(dx, dy)$ from the center $(x, y)$, rotate
      them by angle $\theta$ to get $(x', y')$, and then apply the formula:

      $$f(x, y) = peakMax \cdot \exp\left( -\left( \frac{(x')^2}{2\sigma_M^2} + \frac{(y')^2}{2\sigma_N^2} \right) \right)$$


     */

    /**
     * Generates a 2D Gaussian distribution array.
     *
     * @param width           Array width
     * @param height          Array height
     * @param majorAxisFWHM   Major axis FWHM
     * @param minorAxisFWHM   Minor axis FWHM
     * @param centerX         Center X coordinate
     * @param centerY         Center Y coordinate
     * @param peakMax         Maximum value at peak
     * @param rotationDegrees Rotation angle in degrees
     * @param readNoiseSigma  Background noise standard deviation
     * @param addPhotonNoise  Whether to add Poisson noise (defaults to true)
     *
     */
    def create2DGaussian(
                          width: Int,
                          height: Int,
                          majorAxisFWHM: Double,
                          minorAxisFWHM: Double,
                          centerX: Double,
                          centerY: Double,
                          peakMax: Double,
                          rotationDegrees: Double,
                          readNoiseSigma: Double,
                          addPhotonNoise: Boolean = true
                        ): Array[Array[Double]] =
      val theta = toRadians(rotationDegrees)
      val cosT = cos(theta)
      val sinT = sin(theta)
      val rand = new Random()

      // Constant for FWHM to Variance conversion: 4 * ln(2) / FWHM^2
      val log2_4 = 4.0 * log(2.0)
      val coefM = log2_4 / (majorAxisFWHM * majorAxisFWHM)
      val coefN = log2_4 / (minorAxisFWHM * minorAxisFWHM)

      // Initialize 2D array using indentation-based block
      Array.tabulate(height, width): (row, col) =>
        val dx = col.toDouble - centerX
        val dy = row.toDouble - centerY

        // Rotate coordinates (x', y')
        val xPrime = dx * cosT + dy * sinT
        val yPrime = -dx * sinT + dy * cosT

        val exponent = -((xPrime * xPrime * coefM) + (yPrime * yPrime * coefN))
        val rawCounts = peakMax * exp(exponent)

        // add Poisson noise if requested
        val counts = if (addPhotonNoise) {
            rawCounts +  rand.nextGaussian() * Math.sqrt(rawCounts)
        } else rawCounts
        // add read noise
        counts + rand.nextDouble() * readNoiseSigma
  }

  def getCentroid(data: Array[Array[Double]]): (Double, Double) = {
    var totalWeight = 0.0
    var weightedSumX = 0.0
    var weightedSumY = 0.0

    for (y <- data.indices; x <- data(y).indices) {
      val weight = data(y)(x)
      totalWeight += weight
      weightedSumX += weight * x
      weightedSumY += weight * y
    }

    if (totalWeight == 0) (0.0, 0.0) // Handle empty or zero-filled array
    else (weightedSumX / totalWeight, weightedSumY / totalWeight)
  }
}
