package lgsf.pacprototypehcd

import com.imperx.camera.nativebridge.JnrImperxBinding
import com.imperx.camera.{CameraConfig, CameraSession, CameraSystem, StreamSession}
import scala.util.Random

// 1. Define the protocol interface
trait PacCameraProtocol {
  def initialize(ipAddress: String): Int
  def shutdown(): Unit
  def setExposureTime(microseconds: Double): Int
  def setGain(gain: Double): Int
  def startStream(): Int
  def stopStream(): Int
  def takeSingleExposure(timeoutMs: Int): CameraFrame | Null
  def getStreamFrame(timeoutMs: Int): CameraFrame | Null
  def lastError: Option[String] = None
}

// 2. The Real Native Implementation (Production) via imperx-camera project
class PacCameraNative extends PacCameraProtocol {
  private var system: Option[CameraSystem]         = None
  private var session: Option[CameraSession]       = None
  private var streamSession: Option[StreamSession] = None
  private var exposureMicros: Option[Double]       = None
  private var gain: Option[Double]                 = None
  private var lastErr: Option[String]              = None

  override def lastError: Option[String] = lastErr

  override def initialize(ipAddress: String): Int = {
    try {
      lastErr = None
      closeAll()
      val binding      = JnrImperxBinding.load("imperx_bridge")
      val cameraSystem = CameraSystem(binding)
      val cameraInfo = cameraSystem
        .listCameras()
        .find(_.ipAddress.contains(ipAddress))
        .orElse(cameraSystem.listCameras().headOption)
        .getOrElse(throw new RuntimeException("No Imperx cameras discovered"))
      val config     = CameraConfig(exposureMicros = exposureMicros, gain = gain, pixelFormat = Some("Mono8"))
      val camSession = cameraSystem.open(cameraInfo.id, config)
      system = Some(cameraSystem)
      session = Some(camSession)
      0
    }
    catch {
      case ex: Throwable =>
        val top = ex.getStackTrace.headOption.map(_.toString).getOrElse("no-stack")
        lastErr = Some(s"${ex.getClass.getSimpleName}: ${ex.getMessage}; at $top")
        1
    }
  }

  override def shutdown(): Unit = closeAll()

  override def setExposureTime(microseconds: Double): Int = {
    exposureMicros = Some(microseconds)
    reconfigure()
    0
  }

  override def setGain(gainValue: Double): Int = {
    gain = Some(gainValue)
    reconfigure()
    0
  }

  override def startStream(): Int = {
    try {
      lastErr = None
      if (streamSession.isEmpty) streamSession = session.map(_.startAcquisition())
      0
    }
    catch {
      case ex: Throwable =>
        val top = ex.getStackTrace.headOption.map(_.toString).getOrElse("no-stack")
        lastErr = Some(s"${ex.getClass.getSimpleName}: ${ex.getMessage}; at $top")
        1
    }
  }

  override def stopStream(): Int = {
    streamSession.foreach(_.close())
    streamSession = None
    0
  }

  override def takeSingleExposure(timeoutMs: Int): CameraFrame | Null = {
    session match {
      case Some(s) =>
        val stream = s.startAcquisition()
        try toCameraFrame(stream.grabFrame(timeoutMs.toLong))
        catch { case _: Throwable => null }
        finally stream.close()
      case None => null
    }
  }

  override def getStreamFrame(timeoutMs: Int): CameraFrame | Null = {
    streamSession match {
      case Some(stream) =>
        try toCameraFrame(stream.grabFrame(timeoutMs.toLong))
        catch { case _: Throwable => null }
      case None => null
    }
  }

  private def reconfigure(): Unit = {
    // imperx-camera does not expose runtime reconfigure on an open session:
    // reopen the camera session with updated config when connected.
    (system, session) match {
      case (Some(cameraSystem), Some(oldSession)) =>
        oldSession.close()
        cameraSystem.listCameras().headOption.foreach { cameraInfo =>
          session = Some(
            cameraSystem.open(
              cameraInfo.id,
              CameraConfig(exposureMicros = exposureMicros, gain = gain, pixelFormat = Some("Mono8"))
            )
          )
        }
        streamSession = None
      case _ => ()
    }
  }

  private def toCameraFrame(frame: com.imperx.camera.Frame): CameraFrame =
    CameraFrame(
      frame.width,
      frame.height,
      frame.timestampNanos,
      PacCamera.flipVertical(frame.bytes, frame.width, frame.height)
    )

  private def closeAll(): Unit = {
    streamSession.foreach(_.close())
    streamSession = None
    session.foreach(_.close())
    session = None
    system.foreach(_.close())
    system = None
  }
}

// 3. Simulated Implementation (Strategy Pattern)
class PacCameraSimulated(val simWidth: Int, val simHeight: Int) extends PacCameraProtocol {
  private val majorAxisFwhm   = 100.0
  private val minorAxisFwhm   = 50.0
  private val maxStepX        = majorAxisFwhm / 4.0
  private val maxStepY        = minorAxisFwhm / 4.0
  private val edgeMargin      = majorAxisFwhm
  private val minCenterX      = edgeMargin
  private val maxCenterX      = math.max(minCenterX, simWidth - 1.0 - edgeMargin)
  private val minCenterY      = edgeMargin
  private val maxCenterY      = math.max(minCenterY, simHeight - 1.0 - edgeMargin)
  private val rng             = new Random()
  private var centerX: Double = (minCenterX + maxCenterX) / 2.0
  private var centerY: Double = (minCenterY + maxCenterY) / 2.0

  override def initialize(ipAddress: String): Int         = 0
  override def shutdown(): Unit                           = ()
  override def setExposureTime(microseconds: Double): Int = 0
  override def setGain(gain: Double): Int                 = 0
  override def startStream(): Int                         = 0
  override def stopStream(): Int                          = 0

  override def takeSingleExposure(timeoutMs: Int): CameraFrame | Null = generateFrame()
  override def getStreamFrame(timeoutMs: Int): CameraFrame | Null     = generateFrame()

  private def generateFrame(): CameraFrame = {
    val dx = (rng.nextDouble() * 2.0 - 1.0) * maxStepX
    val dy = (rng.nextDouble() * 2.0 - 1.0) * maxStepY
    centerX = clamp(centerX + dx, minCenterX, maxCenterX)
    centerY = clamp(centerY + dy, minCenterY, maxCenterY)

    val raw = SimulatedData.create2DGaussian(
      width = simWidth,
      height = simHeight,
      majorAxisFWHM = majorAxisFwhm,
      minorAxisFWHM = minorAxisFwhm,
      centerX = centerX,
      centerY = centerY,
      peakMax = 255.0,
      rotationDegrees = 30.0,
      readNoiseSigma = 1.0
    )

    val pixelData = new Array[Byte](simWidth * simHeight)
    for (y <- 0 until simHeight; x <- 0 until simWidth) {
      pixelData(y * simWidth + x) = math.max(0, math.min(255, raw(y)(x).round.toInt)).toByte
    }
    CameraFrame(simWidth, simHeight, System.nanoTime(), pixelData)
  }

  private def clamp(v: Double, lo: Double, hi: Double): Double =
    math.max(lo, math.min(hi, v))
}

case class CameraFrame(width: Int, height: Int, timestamp: Long, data: Array[Byte])

// 4. The Wrapper Class (Injects the protocol, no flags)
class PacCamera(protocol: PacCameraProtocol = new PacCameraNative) {
  def protocolName: String                = protocol.getClass.getSimpleName
  private def protocolErrorSuffix: String = protocol.lastError.map(e => s"; cause=$e").getOrElse("")

  def connect(ipAddress: String): Unit = {
    val rc = protocol.initialize(ipAddress)
    if (rc != 0) throw new RuntimeException(s"Camera connect failed: code $rc$protocolErrorSuffix")
  }

  def disconnect(): Unit = protocol.shutdown()

  def setExposureTime(microseconds: Double): Unit = {
    val rc = protocol.setExposureTime(microseconds)
    if (rc != 0) throw new RuntimeException(s"Set exposure failed: code $rc")
  }

  def setGain(gain: Double): Unit = {
    val rc = protocol.setGain(gain)
    if (rc != 0) throw new RuntimeException(s"Set gain failed: code $rc")
  }

  def startStream(): Unit = {
    val rc = protocol.startStream()
    if (rc != 0) throw new RuntimeException(s"Start stream failed: code $rc$protocolErrorSuffix")
  }

  def stopStream(): Unit = {
    val rc = protocol.stopStream()
    if (rc != 0) throw new RuntimeException(s"Stop stream failed: code $rc")
  }

  def getStreamFrame(timeoutMs: Int): Option[CameraFrame] =
    Option(protocol.getStreamFrame(timeoutMs))

  def takeSingleExposure(timeoutMs: Int): Option[CameraFrame] =
    Option(protocol.takeSingleExposure(timeoutMs))
}

object PacCamera {
  private[pacprototypehcd] def flipVertical(data: Array[Byte], width: Int, height: Int): Array[Byte] = {
    if (data == null || width <= 0 || height <= 0 || data.length < width * height) return data
    val out     = new Array[Byte](data.length)
    val rowSize = width
    var row     = 0
    while (row < height) {
      val srcRow = row * rowSize
      val dstRow = (height - 1 - row) * rowSize
      System.arraycopy(data, srcRow, out, dstRow, rowSize)
      row += 1
    }
    out
  }
}
