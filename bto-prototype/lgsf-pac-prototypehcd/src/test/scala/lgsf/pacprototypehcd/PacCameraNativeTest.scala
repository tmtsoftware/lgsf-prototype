package lgsf.pacprototypehcd

import org.scalatest.funsuite.AnyFunSuite

class PacCameraNativeTest extends AnyFunSuite {

  test("PacCameraNative protocol is valid") {
    val protocol = new PacCameraNative()
    assert(protocol.isInstanceOf[PacCameraProtocol])
  }

  test("disconnect should be safe even when never connected") {
    val camera = new PacCamera(new PacCameraNative())
    camera.disconnect()
  }

  test("takeSingleExposure should return None when not connected") {
    val camera = new PacCamera(new PacCameraNative())
    val frame  = camera.takeSingleExposure(timeoutMs = 1)
    assert(frame.isEmpty, "Expected None when not connected")
  }

  test("setExposureTime and setGain should not throw when not connected") {
    val camera = new PacCamera(new PacCameraNative())
    camera.setExposureTime(1000.0)
    camera.setGain(2.0)
  }
}
