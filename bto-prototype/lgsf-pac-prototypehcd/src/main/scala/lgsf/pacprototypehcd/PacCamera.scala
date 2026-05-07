package lgsf.pacprototypehcd

import java.nio.{ByteBuffer, ByteOrder}
import java.util.Arrays

case class CameraFrame(width: Int, height: Int, timestamp: Long, data: Array[Byte])

/**
 * Interface to the Imperx C1911 camera via JNI.
 * When simulationMode=true, returns synthesized Gaussian spot images without loading
 * the native library. Set simulation-mode=true in application.conf for unit testing.
 *
 * Frame wire format returned by JNI methods:
 *   Bytes  0- 3  width  (int32, little-endian)
 *   Bytes  4- 7  height (int32, little-endian)
 *   Bytes  8-15  timestamp (int64, little-endian, nanoseconds)
 *   Bytes 16+    pixel data (8-bit grayscale, row-major)
 */
class PacCamera(simulationMode: Boolean, simWidth: Int, simHeight: Int) {

  if (!simulationMode) {
    System.loadLibrary("pac-camera-jni")
  }

  def connect(ipAddress: String): Unit = {
    if (!simulationMode) {
      val rc = nativeInitialize(ipAddress)
      if (rc != 0) throw new RuntimeException(s"Camera connect failed: code $rc")
    }
  }

  def disconnect(): Unit = if (!simulationMode) nativeShutdown()

  def setExposureTime(microseconds: Double): Unit =
    if (!simulationMode) nativeSetExposureTime(microseconds)

  def setGain(gain: Double): Unit =
    if (!simulationMode) nativeSetGain(gain)

  def startStream(): Unit = if (!simulationMode) nativeStartStream()

  def stopStream(): Unit = if (!simulationMode) nativeStopStream()

  def getStreamFrame(timeoutMs: Int): Option[CameraFrame] =
    if (simulationMode) Some(generateSimulatedFrame())
    else Option(nativeGetStreamFrame(timeoutMs)).flatMap(parseFrame)

  def takeSingleExposure(timeoutMs: Int): Option[CameraFrame] =
    if (simulationMode) Some(generateSimulatedFrame())
    else Option(nativeTakeSingleExposure(timeoutMs)).flatMap(parseFrame)

  private def parseFrame(bytes: Array[Byte]): Option[CameraFrame] = {
    if (bytes.length < 16) return None
    val buf       = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val width     = buf.getInt
    val height    = buf.getInt
    val timestamp = buf.getLong
    val data      = Arrays.copyOfRange(bytes, 16, bytes.length)
    Some(CameraFrame(width, height, timestamp, data))
  }

  private def generateSimulatedFrame(): CameraFrame = {
    val raw = SimulatedData.create2DGaussian(
      width = simWidth,
      height = simHeight,
      majorAxisFWHM = 100.0,
      minorAxisFWHM = 50.0,
      centerX = simWidth / 2.0,
      centerY = simHeight / 2.0,
      peakMax = 255.0,
      rotationDegrees = 30.0,
      readNoiseSigma = 1.0
    )
    val data = new Array[Byte](simWidth * simHeight)
    for (y <- 0 until simHeight; x <- 0 until simWidth)
      data(y * simWidth + x) = math.max(0, math.min(255, raw(y)(x).round.toInt)).toByte
    CameraFrame(simWidth, simHeight, System.nanoTime(), data)
  }

  // JNI bridge — implemented in PacCameraJni.cpp, loaded as libpac-camera-jni.so
  @native def nativeInitialize(ipAddress: String): Int
  @native def nativeShutdown(): Unit
  @native def nativeSetExposureTime(microseconds: Double): Int
  @native def nativeSetGain(gain: Double): Int
  @native def nativeStartStream(): Int
  @native def nativeStopStream(): Int
  @native def nativeTakeSingleExposure(timeoutMs: Int): Array[Byte]
  @native def nativeGetStreamFrame(timeoutMs: Int): Array[Byte]
}
