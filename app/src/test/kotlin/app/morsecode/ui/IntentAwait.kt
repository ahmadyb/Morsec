package app.morsecode.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import org.robolectric.Shadows.shadowOf

/**
 * Waits for an action that leaves the app, and returns the intent it handed out.
 *
 * Sharing stages its file on an IO dispatcher before it starts a chooser, so the intent
 * arrives after the click's own main-loop work is done — and how much later depends on
 * how busy the machine running the tests is. A test that idles the looper once and reads
 * the queue straight away is therefore asserting how fast the machine was, which is how a
 * suite gets the reputation of being flaky. This waits for the intent, idling the main
 * looper between attempts so the continuation that starts it can run, and gives up only
 * after a bound nobody's machine should need.
 *
 * [idle] is the caller's own settle step and comes last so a call site reads as a call
 * with a body: these tests drive Compose, and a Compose test rule has to be told to wait
 * as well as the looper.
 *
 * The file is named for what it waits for rather than for the Android class it
 * returns, because tools/verify reads a file called *Activity.kt as a component this
 * app starts and asks the manifest to declare it — which is the right question to ask
 * of a file that defines one, and the wrong question to ask of a test helper.
 */
internal fun awaitStartedActivity(
    context: Context,
    attempts: Int = 200,
    pauseMillis: Long = 10L,
    idle: () -> Unit,
): Intent? {
    val application = context.applicationContext as Application
    repeat(attempts) {
        idle()
        shadowOf(application).nextStartedActivity?.let { return it }
        Thread.sleep(pauseMillis)
    }
    return null
}
