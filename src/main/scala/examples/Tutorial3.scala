package examples
import java.util.concurrent.CountDownLatch

import examples.Tutorial2.exit
import org.freedesktop.gstreamer.{
  Bus,
  Element,
  ElementFactory,
  Gst,
  GstObject,
  Pad,
  Pipeline,
  State,
  StateChangeReturn
}

import scala.util.{Failure, Success, Try}

object Tutorial3 extends App {
  val latch = new CountDownLatch(1)
  def exit() = latch.countDown()

  val padAddedHandler = new Element.PAD_ADDED {
    override def padAdded(element: Element, newPad: Pad): Unit = {
      println(s"Received new pad $element from $newPad")
      /* If our converter is already linked, we have nothing to do here */
      if (newPad.isLinked) {
        println("we are already linked. Ignoring.")
      } else {
        /* Check the new pad's type */
        val caps = newPad.getCurrentCaps
        val struct = caps.getStructure(0)
        val padType = struct.getName()
        padType match {
          case t if t.startsWith("audio/x-raw") =>
            /* Attempt the link */
            val sinkPad = convert.getStaticPad("sink")
            Try(newPad.link(sinkPad)) match {
              case Failure(exception) =>
                println(s"Type is '$padType' but link failed with ${exception.getMessage}")
              case Success(_) =>
                println(s"Link succeeded (type '$padType')")
            }
          case t if t.startsWith("video/x-raw") =>
            val sinkPad = autoVideoSink.getStaticPad("sink")
            Try(newPad.link(sinkPad)) match {
              case Failure(exception) =>
                println(s"Type is '$padType' but link failed with ${exception.getMessage}")
              case Success(_) =>
                println(s"Link succeeded (type '$padType')")
            }

          case _ => println(s"It has type '$padType' which is not raw audio. Ignoring.")
        }
      }
    }
  }

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

  val stateChangedHandler = new Bus.STATE_CHANGED {
    override def stateChanged(
      source: GstObject,
      old: State,
      current: State,
      pending: State
    ): Unit = {
      /* We are only interested in state-changed messages from the pipeline */
      if (source.getName == pipeline.getName) {
        println(s"Pipeline state changed from $old to $current:")
      }
    }
  }

  /* Initialize GStreamer */
  Gst.init("tutorial 3")

  val uriDecodeBin = ElementFactory.make("uridecodebin", "source")
  val convert = ElementFactory.make("audioconvert", "convert")
  val audioResample = ElementFactory.make("audioresample", "resample")
  val autoAudioSink = ElementFactory.make("autoaudiosink", "audiosink")
  val autoVideoSink = ElementFactory.make("autovideosink", "videosink")

  val pipeline = new Pipeline("test-pipeline")
  pipeline.addMany(uriDecodeBin, convert, audioResample, autoAudioSink, autoVideoSink)
  convert.link(audioResample)
  audioResample.link(autoAudioSink)

  assert(
    uriDecodeBin != null &&
      convert != null &&
      audioResample != null &&
      autoAudioSink != null,
    "Not all elements initialised"
  )

  /* Set the URI to play */
  uriDecodeBin.set(
    "uri",
    "https://www.freedesktop.org/software/gstreamer-sdk/data/media/sintel_trailer-480p.webm"
  )

  /* Connect to the pad-added signal */
  uriDecodeBin.connect(padAddedHandler)

  /* Start playing */
  val ret = pipeline.setState(State.PLAYING)
  assert(ret != StateChangeReturn.FAILURE, s"Unable to set the pipeline to the playing state")

  val bus = pipeline.getBus
  bus.connect(eosHandler)
  bus.connect(errorHandler)
  bus.connect(stateChangedHandler)

  latch.await()
}
