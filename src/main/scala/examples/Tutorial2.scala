package examples

import java.util.concurrent.CountDownLatch

import org.freedesktop.gstreamer.{
  Bus,
  ElementFactory,
  Gst,
  GstObject,
  Pipeline,
  State,
  StateChangeReturn
}

object Tutorial2 extends App {
  /* Initialize GStreamer */
  Gst.init("tutorial 2")

  /* Create the elements */
  val source = ElementFactory.make("videotestsrc", "source")
  val sink = ElementFactory.make("autovideosink", "sink")

  val pipeline = new Pipeline("test-pipeline")

  assert(
    source != null &&
      sink != null &&
      pipeline != null,
    "Not all elements could be created.\n"
  )

  /* Build the pipeline */
  pipeline.addMany(source, sink)

  val isLinked = source.link(sink)
  if (!isLinked) {
    println("can not link source and sink")
    sys.exit(-1)
  }

  /* Start playing */
  val stateChange = pipeline.setState(State.PLAYING)
  if (stateChange == StateChangeReturn.FAILURE) {
    println("Unable to set the pipeline to the playing state.")
    sys.exit(-1)
  }

  val bus = pipeline.getBus
  /* Wait until error or EOS */

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

  bus.connect(eosHandler)
  bus.connect(errorHandler)

  latch.await()
}
