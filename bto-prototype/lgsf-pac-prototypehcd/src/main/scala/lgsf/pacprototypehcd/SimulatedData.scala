package lgsf.pacprototypehcd

import scala.math.*
import scala.util.Random

object SimulatedData {

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
   * @param addPhotonNoise  Whether to add Poisson noise
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
  ): Array[Array[Double]] = {
    val theta = toRadians(rotationDegrees)
    val cosT  = cos(theta)
    val sinT  = sin(theta)
    val rand  = new Random()

    // Constant for FWHM to Variance conversion: 4 * ln(2) / FWHM^2
    val log2_4 = 4.0 * log(2.0)
    val coefM  = log2_4 / (majorAxisFWHM * majorAxisFWHM)
    val coefN  = log2_4 / (minorAxisFWHM * minorAxisFWHM)

    Array.tabulate(height, width) { (row, col) =>
      val dx = col.toDouble - centerX
      val dy = row.toDouble - centerY

      // Rotate coordinates: x', y'
      val xPrime = dx * cosT + dy * sinT
      val yPrime = -dx * sinT + dy * cosT

      val exponent  = -((xPrime * xPrime * coefM) + (yPrime * yPrime * coefN))
      val rawCounts = peakMax * exp(exponent)

      val counts =
        if (addPhotonNoise) rawCounts + rand.nextGaussian() * Math.sqrt(rawCounts)
        else rawCounts

      counts + rand.nextDouble() * readNoiseSigma
    }
  }

  def getCentroid(data: Array[Array[Double]]): (Double, Double) = {
    var totalWeight  = 0.0
    var weightedSumX = 0.0
    var weightedSumY = 0.0

    for (y <- data.indices; x <- data(y).indices) {
      val weight = data(y)(x)
      totalWeight += weight
      weightedSumX += weight * x
      weightedSumY += weight * y
    }

    if (totalWeight == 0) (0.0, 0.0)
    else (weightedSumX / totalWeight, weightedSumY / totalWeight)
  }
}
