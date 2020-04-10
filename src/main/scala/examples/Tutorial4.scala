package examples

import java.util.concurrent.{CountDownLatch, ScheduledThreadPoolExecutor, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}

import org.freedesktop.gstreamer.query.{DurationQuery, PositionQuery, SeekingQuery}
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
  seekEnabled: AtomicBoolean, /* Is seeking enabled for this media? */
  seekDone: AtomicBoolean, /* Have we performed the seek already? */
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
            println {
              s"Seeking is enabled " +
                s"from: ${ClockTime.toSeconds(query.getStart)} " +
                s"to ${ClockTime.toSeconds(query.getEnd)}"
            }
            applicationState.seekEnabled.set(true)
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

final class UIHandler(applicationState: CustomData) extends Runnable {
  // taken from: https://github.com/Kurento/gstreamer/blob/master/gst/gstsegment.h
  private val GST_SEEK_FLAG_FLUSH = 1 << 0
  private val GST_SEEK_FLAG_KEY_UNIT = 1 << 2
  override def run(): Unit = {
    /* Query the current position of the stream */
    val positionQuery = new PositionQuery(Format.TIME)

    if (!applicationState.playbin.query(positionQuery)) {
      println("Could not query current position")
    }

    val currentPosition = positionQuery.getPosition

    /* If we didn't know it yet, query the stream duration */
    if (!ClockTime.isValid(applicationState.duration.get())) {
      val durationQuery = new DurationQuery(Format.TIME)
      if (!applicationState.playbin.query(durationQuery)) {
        println("Could not query current duration")
      }
      applicationState.duration.set(durationQuery.getDuration)
    }
    /* Print current position and total duration */
    println(
      s"Position " +
        s"current: ${ClockTime.toSeconds(currentPosition)} " +
        s"duration: ${ClockTime.toSeconds(applicationState.duration.get())}"
    )

    /* If seeking is enabled, we have not done it yet, and the time is right, seek */
    val seekEnabled = applicationState.seekEnabled.get
    val seekDone = applicationState.seekDone.get()
    val timeReached = currentPosition > ClockTime.fromSeconds(10)
    if (seekEnabled && !seekDone && timeReached) {
      println("Reached 10s, performing seek...")
      import org.freedesktop.gstreamer.lowlevel.GstElementAPI.GSTELEMENT_API
      GSTELEMENT_API
        .gst_element_seek_simple(
          applicationState.playbin,
          Format.TIME,
          GST_SEEK_FLAG_FLUSH | GST_SEEK_FLAG_KEY_UNIT,
          ClockTime.fromSeconds(30)
        )
      applicationState.seekDone.set(true)
    }
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
    seekEnabled = new AtomicBoolean(false),
    seekDone = new AtomicBoolean(false),
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

  val scheduler = new ScheduledThreadPoolExecutor(1)
  scheduler.scheduleWithFixedDelay(new UIHandler(data), 0, 100, TimeUnit.MILLISECONDS)
  latch.await()
  scheduler.shutdown()
}
