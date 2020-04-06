package robolive

import java.util.concurrent.{CountDownLatch, TimeUnit}

import org.freedesktop.gstreamer.{Bus, Gst, GstObject, State}

object Tutorial1 extends App {
  val description =
    "playbin uri=https://www.freedesktop.org/software/gstreamer-sdk/data/media/sintel_trailer-480p.webm"

  val latch = new CountDownLatch(1)
  def exit() = latch.countDown()

  val eosHandler = new Bus.EOS {
    override def endOfStream(source: GstObject): Unit = {
      println(s"End of stream ${source.getName}")
      exit()
    }
  }

  val errorHandler = new Bus.ERROR {
    override def errorMessage(source: GstObject, code: Int, message: String): Unit = {
      println(s"Error: ${source.getName} $code $message")
      exit()
    }
  }

  Gst.init("Tutorial 1")

  val pipeline = Gst.parseLaunch(description)
  pipeline.setState(State.PLAYING)
  val bus = pipeline.getBus
  bus.connect(eosHandler)
  bus.connect(errorHandler)
  latch.await()
  pipeline.setState(State.NULL)
}
