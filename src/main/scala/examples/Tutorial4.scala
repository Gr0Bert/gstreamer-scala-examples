package examples

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}

import org.freedesktop.gstreamer.query.SeekingQuery
import org.freedesktop.gstreamer.{
  Bus,
  ClockTime,
  Element,
  ElementFactory,
  Format,
  Gst,
  GstObject,
  State,
  StateChangeReturn
}

final case class CustomData(
  playbin: Element, /* Our one and only element */
  playing: AtomicBoolean, /* Are we in the PLAYING state? */
  terminate: AtomicBoolean, /* Should we terminate execution? */
  seek_enabled: AtomicBoolean, /* Is seeking enabled for this media? */
  seek_done: AtomicBoolean, /* Have we performed the seek already? */
  duration: AtomicLong, /* How long does this media last, in nanoseconds */
)

final class ApplicationLogic(applicationState: CustomData, latch: CountDownLatch)
    extends Bus.EOS with Bus.ERROR with Bus.STATE_CHANGED with Bus.DURATION_CHANGED {

  private def exit(): Unit = latch.countDown()

  override def endOfStream(source: GstObject): Unit = {
    println(s"End of stream ${source.getName}")
    applicationState.terminate.set(true)
    exit()
  }

  override def errorMessage(source: GstObject, code: Int, message: String): Unit = {
    println(s"Error: ${source.getName} $code $message")
    applicationState.terminate.set(true)
    exit()
  }

  override def stateChanged(source: GstObject, old: State, current: State, pending: State): Unit = {
    if (source.getName == applicationState.playbin.getName) {
      println(s"Pipeline state changed from $old to $current:")
      /* Remember whether we are in the PLAYING state or not */
      applicationState.playing.setRelease(current == State.PLAYING)
      if (applicationState.playing.get) {
        /* We just moved to PLAYING. Check if seeking is possible */
        val query = new SeekingQuery(Format.TIME)
        val couldBePerformed = applicationState.playbin.query(query)
        if (couldBePerformed) {
          if (query.isSeekable) {
            println(s"Seeking is enabled from: ${query.getStart} to ${query.getEnd}")
          } else {
            println(s"Seeking is disabled for this stream")
          }
        } else {
          println(s"Seeking is failed")
        }
      }
    }
  }

  override def durationChanged(source: GstObject): Unit = {
    /* The duration has changed, mark the current one as invalid */
    applicationState.duration.set(ClockTime.NONE)
  }
}

object Tutorial4 extends App {

  val latch = new CountDownLatch(1)

  Gst.init("tutorial 4")

  val playbin = ElementFactory.make("playbin", "playbin")
  val data = CustomData(
    playbin = playbin,
    playing = new AtomicBoolean(false),
    terminate = new AtomicBoolean(false),
    seek_enabled = new AtomicBoolean(false),
    seek_done = new AtomicBoolean(false),
    duration = new AtomicLong(ClockTime.NONE),
  )
  val applicationLogic = new ApplicationLogic(data, latch)

  assert(data.playbin != null, "Not all elements could be created")

  /* Set the URI to play */
  data.playbin.set(
    "uri",
    "https://www.freedesktop.org/software/gstreamer-sdk/data/media/sintel_trailer-480p.webm"
  )

  /* Start playing */
  val ret = data.playbin.setState(State.PLAYING)
  assert(ret != StateChangeReturn.FAILURE, "Unable to set the pipeline to the playing state")

  val bus = data.playbin.getBus
  bus.connect(applicationLogic: Bus.EOS)
  bus.connect(applicationLogic: Bus.ERROR)
  bus.connect(applicationLogic: Bus.STATE_CHANGED)
  bus.connect(applicationLogic: Bus.DURATION_CHANGED)

  latch.await()
}
