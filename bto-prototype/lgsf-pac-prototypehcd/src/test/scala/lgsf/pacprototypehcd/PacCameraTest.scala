package lgsf.pacprototypehcd

import org.scalatest.funsuite.AnyFunSuite

class PacCameraTest extends AnyFunSuite {

  test("PacCamera delegates connect to protocol and throws on failure") {
    val mock   = new CameraMockProtocol(initReturnCode = 0)
    val camera = new PacCamera(mock)

    camera.connect("192.168.1.100")
    assert(mock.initCalls == 1)
    assert(mock.initArgs.head == "192.168.1.100")

    val failingMock   = new CameraMockProtocol(initReturnCode = -1)
    val failingCamera = new PacCamera(failingMock)

    val ex = intercept[RuntimeException]:
      failingCamera.connect("10.0.0.1")
    assert(ex.getMessage == "Camera connect failed: code -1")
  }

  test("PacCamera delegates disconnect to protocol") {
    val mock   = new CameraMockProtocol()
    val camera = new PacCamera(mock)

    camera.disconnect()
    assert(mock.shutdownCalls == 1)
  }

  test("PacCamera delegates exposure and gain settings") {
    val mock   = new CameraMockProtocol()
    val camera = new PacCamera(mock)

    camera.setExposureTime(2500.5)
    camera.setGain(4.2)

    assert(mock.exposureTimes == List(2500.5))
    assert(mock.gains == List(4.2))
  }

  test("PacCamera delegates stream control") {
    val mock   = new CameraMockProtocol()
    val camera = new PacCamera(mock)

    camera.startStream()
    camera.stopStream()

    assert(mock.startCalls == 1)
    assert(mock.stopCalls == 1)
  }

  test("PacCamera returns frame from protocol") {
    val frameData = Array.fill[Byte](64 * 48)(0)
    val frame     = CameraFrame(64, 48, 987654321L, frameData)
    val mock      = new CameraMockProtocol(frame = Some(frame))
    val camera    = new PacCamera(mock)

    val result = camera.takeSingleExposure(1000)

    assert(result.exists(f => f.width == 64 && f.height == 48 && f.timestamp == 987654321L && f.data.length == 64 * 48))
  }

  test("PacCamera returns None for null payloads") {
    val mock   = new CameraMockProtocol(frame = None, streamFrame = None)
    val camera = new PacCamera(mock)

    val streamResult = camera.getStreamFrame(500)
    val snapResult   = camera.takeSingleExposure(500)

    assert(streamResult.isEmpty)
    assert(snapResult.isEmpty)

    val nullMock   = new CameraMockProtocol(frame = None, streamFrame = None)
    val nullCamera = new PacCamera(nullMock)
    assert(nullCamera.getStreamFrame(500).isEmpty)
    assert(nullCamera.takeSingleExposure(500).isEmpty)
  }

  test("PacCamera integrates end-to-end with PacCameraSimulated") {
    val camera = new PacCamera(new PacCameraSimulated(128, 96))

    camera.connect("sim://local")
    val frame = camera.takeSingleExposure(5000)

    assert(frame.isDefined)
    val f = frame.get
    assert(f.width == 128)
    assert(f.height == 96)
    assert(f.data.length == 128 * 96)

    // Sanity check: simulated gaussian data should contain non-zero bytes
    assert(f.data.exists(_ != 0.toByte), "Simulated frame should contain non-zero pixel data")
  }

}

// Simple call-tracking mock for unit testing
private class CameraMockProtocol(
    initReturnCode: Int = 0,
    frame: Option[CameraFrame] = None,
    streamFrame: Option[CameraFrame] = None
) extends PacCameraProtocol {

  var initCalls     = 0
  var initArgs      = List[String]()
  var shutdownCalls = 0
  var exposureTimes = List[Double]()
  var gains         = List[Double]()
  var startCalls    = 0
  var stopCalls     = 0

  override def initialize(ipAddress: String): Int = {
    initCalls += 1
    initArgs = ipAddress :: initArgs
    initReturnCode
  }

  override def shutdown(): Unit = shutdownCalls += 1

  override def setExposureTime(microseconds: Double): Int = {
    exposureTimes = microseconds :: exposureTimes
    0
  }

  override def setGain(gain: Double): Int = {
    gains = gain :: gains
    0
  }

  override def startStream(): Int = {
    startCalls += 1
    0
  }

  override def stopStream(): Int = {
    stopCalls += 1
    0
  }

  override def takeSingleExposure(timeoutMs: Int): CameraFrame | Null =
    frame.orNull

  override def getStreamFrame(timeoutMs: Int): CameraFrame | Null =
    streamFrame.orNull
}
