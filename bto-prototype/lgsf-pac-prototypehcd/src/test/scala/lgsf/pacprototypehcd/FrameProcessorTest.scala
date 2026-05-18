package lgsf.pacprototypehcd

import csw.event.api.scaladsl.EventPublisher
import csw.params.events.SystemEvent
import csw.prefix.models.Prefix
import nom.tam.fits.Fits
import org.apache.pekko.Done
import org.apache.pekko.actor.testkit.typed.scaladsl.{ScalaTestWithActorTestKit, TestProbe}
import org.scalatest.funsuite.AnyFunSuiteLike
import org.scalatestplus.mockito.MockitoSugar

import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{verify, when}

import java.nio.file.{Files, Paths}
import scala.concurrent.Future
import scala.concurrent.duration.*

class FrameProcessorTest extends ScalaTestWithActorTestKit with AnyFunSuiteLike with MockitoSugar {

  test("frameToFitsBytes should encode a valid frame") {
    val frame = _root_.lgsf.pacprototypehcd.CameraFrame(4, 3, 1234L, Array.fill[Byte](12)(10))
    val bytes = _root_.lgsf.pacprototypehcd.FrameProcessor.frameToFitsBytes(frame)
    assert(bytes.nonEmpty)
  }

  test("frameToFitsBytes should fail fast for invalid frame dimensions") {
    val frame = _root_.lgsf.pacprototypehcd.CameraFrame(0, 3, 1234L, Array.fill[Byte](1)(10))
    val ex = intercept[IllegalArgumentException] {
      _root_.lgsf.pacprototypehcd.FrameProcessor.frameToFitsBytes(frame)
    }
    assert(ex.getMessage.contains("Invalid frame dimensions"))
  }

  test("frameToFitsBytes should fail fast for truncated frame data") {
    val frame = _root_.lgsf.pacprototypehcd.CameraFrame(4, 3, 1234L, Array.fill[Byte](6)(10))
    val ex = intercept[IllegalArgumentException] {
      _root_.lgsf.pacprototypehcd.FrameProcessor.frameToFitsBytes(frame)
    }
    assert(ex.getMessage.contains("Invalid frame data length"))
  }

  test("writeFrameToFits should write a readable FITS file for a valid frame") {
    val frame = _root_.lgsf.pacprototypehcd.CameraFrame(8, 6, 1234L, Array.fill[Byte](48)(42))
    val path  = Paths.get(System.getProperty("java.io.tmpdir"), s"frameprocessor-${System.nanoTime()}.fits")
    try {
      _root_.lgsf.pacprototypehcd.FrameProcessor.writeFrameToFits(frame, path.toString)
      assert(Files.exists(path))
      assert(Files.size(path) > 0)

      val fits = new Fits(path.toFile)
      val hdu  = fits.readHDU()
      assert(hdu != null)
      val axes = hdu.getAxes
      assert(axes.length == 2)
      assert(axes.sorted.sameElements(Array(6, 8)))
    }
    finally {
      Files.deleteIfExists(path)
    }
  }

  test("writeFrameToFits should write simulated-camera frames") {
    val camera = new _root_.lgsf.pacprototypehcd.PacCamera(new _root_.lgsf.pacprototypehcd.PacCameraSimulated(16, 12))
    camera.connect("sim://local")
    val frame = camera.takeSingleExposure(1000).get
    val path  = Paths.get(System.getProperty("java.io.tmpdir"), s"frameprocessor-sim-${System.nanoTime()}.fits")

    try {
      _root_.lgsf.pacprototypehcd.FrameProcessor.writeFrameToFits(frame, path.toString)
      assert(Files.exists(path))
      assert(Files.size(path) > 0)
      val fits = new Fits(path.toFile)
      val hdu  = fits.readHDU()
      assert(hdu != null)
      val axes = hdu.getAxes
      assert(axes.length == 2)
      assert(axes.sorted.sameElements(Array(12, 16)))
    }
    finally {
      Files.deleteIfExists(path)
    }
  }

  test("FrameProcessor should publish event and forward frame to VBDS actor on Tick") {
    val protocol = new FrameProtocolMock(
      streamFrame = Some(
        _root_.lgsf.pacprototypehcd.CameraFrame(
          3,
          3,
          1234L,
          Array[Byte](
            0,
            0,
            0,
            0,
            100.toByte,
            0,
            0,
            0,
            0
          )
        )
      )
    )
    val camera         = new _root_.lgsf.pacprototypehcd.PacCamera(protocol)
    val eventPublisher = mock[EventPublisher]
    when(eventPublisher.publish(any[csw.params.events.Event])).thenReturn(Future.successful(Done))
    val vbdsProbe = TestProbe[_root_.lgsf.pacprototypehcd.VbdsPublisherActor.Command]()

    val actor = spawn(
      _root_.lgsf.pacprototypehcd.FrameProcessor(
        camera,
        eventPublisher,
        Prefix("LGSF.pac.prototypeHcd"),
        Some(vbdsProbe.ref)
      )
    )

    actor ! _root_.lgsf.pacprototypehcd.FrameProcessor.Start(1.second)
    actor ! _root_.lgsf.pacprototypehcd.FrameProcessor.Tick

    vbdsProbe.expectMessageType[_root_.lgsf.pacprototypehcd.VbdsPublisherActor.PublishFrame]
    val eventCaptor = ArgumentCaptor.forClass(classOf[csw.params.events.Event])
    eventuallyAssert(
      {
        verify(eventPublisher).publish(eventCaptor.capture())
        true
      },
      2.seconds
    )
    val evt = eventCaptor.getValue.asInstanceOf[SystemEvent]
    assert(evt.eventName.name == "cameraCentroid")
    assert(evt.get(_root_.lgsf.pacprototypehcd.FrameProcessor.centroidXKey).exists(_.head == 1.0))
    assert(evt.get(_root_.lgsf.pacprototypehcd.FrameProcessor.centroidYKey).exists(_.head == 1.0))
    assert(protocol.startCalls == 1)
  }

  test("FrameProcessor should stop stream when Stop is received") {
    val protocol = new FrameProtocolMock(
      streamFrame = Some(_root_.lgsf.pacprototypehcd.CameraFrame(8, 8, 1L, Array.fill[Byte](64)(0)))
    )
    val camera         = new _root_.lgsf.pacprototypehcd.PacCamera(protocol)
    val eventPublisher = mock[EventPublisher]
    when(eventPublisher.publish(any[csw.params.events.Event])).thenReturn(Future.successful(Done))
    val actor = spawn(
      _root_.lgsf.pacprototypehcd.FrameProcessor(
        camera,
        eventPublisher,
        Prefix("LGSF.pac.prototypeHcd"),
        None
      )
    )

    actor ! _root_.lgsf.pacprototypehcd.FrameProcessor.Start(1.second)
    actor ! _root_.lgsf.pacprototypehcd.FrameProcessor.Stop

    eventuallyAssert(protocol.stopCalls == 1, 2.seconds)
  }

  private def eventuallyAssert(cond: => Boolean, max: FiniteDuration): Unit = {
    val deadline = max.fromNow
    while (!cond && deadline.hasTimeLeft()) Thread.sleep(20)
    assert(cond)
  }
}

private class FrameProtocolMock(streamFrame: Option[_root_.lgsf.pacprototypehcd.CameraFrame])
    extends _root_.lgsf.pacprototypehcd.PacCameraProtocol {
  var startCalls = 0
  var stopCalls  = 0

  override def initialize(ipAddress: String): Int         = 0
  override def shutdown(): Unit                           = ()
  override def setExposureTime(microseconds: Double): Int = 0
  override def setGain(gain: Double): Int                 = 0
  override def startStream(): Int = {
    startCalls += 1
    0
  }
  override def stopStream(): Int = {
    stopCalls += 1
    0
  }
  override def takeSingleExposure(timeoutMs: Int): _root_.lgsf.pacprototypehcd.CameraFrame | Null = null

  override def getStreamFrame(timeoutMs: Int): _root_.lgsf.pacprototypehcd.CameraFrame | Null = streamFrame.orNull
}
