package io.github.gohenderson.drawio

import android.content.Intent
import android.net.Uri
import android.view.View
import android.webkit.WebView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opens the sample files (androidTest/assets) and checks the viewer draws them. */
@RunWith(AndroidJUnit4::class)
class ViewerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    /** A test asset, copied where the app can read it. */
    private fun sample(name: String): Uri {
        val f = File(instrumentation.targetContext.cacheDir, name)
        instrumentation.context.assets.open(name).use { input -> f.outputStream().use { input.copyTo(it) } }
        return Uri.fromFile(f)
    }

    private fun waitForStatus(timeoutMs: Long = 30_000): String {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            MainActivity.status?.let { return it }
            Thread.sleep(100)
        }
        throw AssertionError("nothing shown after $timeoutMs ms")
    }

    private fun opened(name: String, check: (MainActivity, String) -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val uri = sample(name)
            scenario.onActivity { it.open(uri) }
            val status = waitForStatus()
            scenario.onActivity { check(it, status) }
        }
    }

    @Test
    fun theDataModelDiagramIsDrawn() = opened("gist-data-model.drawio") { activity, status ->
        assertEquals("rendered:1", status)
        assertEquals("gist-data-model.drawio", activity.title.toString())
        assertEquals(View.VISIBLE, activity.findViewById<View>(android.R.id.content).findWebView().visibility)
    }

    @Test
    fun everyPageIsThereIncludingCompressedOnes() = opened("two-pages.drawio") { _, status ->
        assertEquals("rendered:2", status)
    }

    @Test
    fun aDiagramInAPngIsDrawn() = opened("embedded.drawio.png") { _, status ->
        assertEquals("rendered:1", status)
    }

    @Test
    fun aFileThatIsNotADiagramSaysSo() = opened("not-a-diagram.drawio") { activity, status ->
        assertTrue(status, status.startsWith("problem:"))
        val message = activity.window.decorView.findViewWithTag<TextView>("message")
        assertTrue(message.isShown)
        assertTrue(message.text.toString(), message.text.contains("isn't"))
    }

    @Test
    fun withNothingOpenItOffersOpen() {
        ActivityScenario.launch<MainActivity>(Intent(instrumentation.targetContext, MainActivity::class.java)).use { scenario ->
            scenario.onActivity {
                assertNotNull(it.window.decorView.findViewWithTag<View>("open"))
                assertTrue(it.window.decorView.findViewWithTag<View>("open").isShown)
            }
        }
    }

    private fun View.findWebView(): View {
        if (this is WebView) return this
        if (this is android.view.ViewGroup) for (i in 0 until childCount) {
            runCatching { return getChildAt(i).findWebView() }
        }
        throw NoSuchElementException("no WebView")
    }
}
