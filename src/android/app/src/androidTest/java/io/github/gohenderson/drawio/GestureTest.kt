package io.github.gohenderson.drawio

import android.net.Uri
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Fingers on the diagram: pinch zooms, a drag pans, a double tap fits it again. */
@RunWith(AndroidJUnit4::class)
class GestureTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)

    private data class View(val scale: Double, val x: Double, val y: Double)

    /** The diagram's zoom and position, from the viewer page. */
    private fun view(scenario: ActivityScenario<MainActivity>): View {
        val done = CountDownLatch(1)
        var result = ""
        scenario.onActivity { a ->
            a.window.decorView.findViewWithTag<WebView>("viewer").evaluateJavascript(
                "(function(v){return [v.scale, v.translate.x, v.translate.y]})(window.currentViewer.graph.view)"
            ) { result = it; done.countDown() }
        }
        assertTrue("the page answers", done.await(10, TimeUnit.SECONDS))
        val a = JSONArray(result)
        return View(a.getDouble(0), a.getDouble(1), a.getDouble(2))
    }

    private fun opened(block: (ActivityScenario<MainActivity>) -> Unit) {
        val f = File(instrumentation.targetContext.cacheDir, "gist-data-model.drawio")
        instrumentation.context.assets.open("gist-data-model.drawio").use { i -> f.outputStream().use { i.copyTo(it) } }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.open(Uri.fromFile(f)) }
            val end = System.currentTimeMillis() + 30_000
            while (MainActivity.status == null && System.currentTimeMillis() < end) Thread.sleep(100)
            assertEquals("rendered:1", MainActivity.status)
            block(scenario)
        }
    }

    private fun web() = device.wait(Until.findObject(By.clazz(WebView::class.java)), 10_000)!!

    private fun settle() {
        device.waitForIdle()
        Thread.sleep(500)
    }

    @Test
    fun pinchingZoomsInAndOut() = opened { scenario ->
        val start = view(scenario)
        web().pinchOpen(0.6f, 2000)
        settle()
        val bigger = view(scenario)
        assertTrue("zoomed in: $start → $bigger", bigger.scale > start.scale * 1.3)
        web().pinchClose(0.6f, 2000)
        settle()
        val smaller = view(scenario)
        assertTrue("zoomed out: $bigger → $smaller", smaller.scale < bigger.scale * 0.8)
    }

    @Test
    fun draggingMovesTheDiagram() = opened { scenario ->
        web().pinchOpen(0.6f, 2000) // in, so there's room to move it
        settle()
        val before = view(scenario)
        val b = web().visibleBounds
        device.swipe(b.centerX(), b.centerY(), b.centerX() - b.width() / 4, b.centerY() - b.height() / 6, 40)
        settle()
        val after = view(scenario)
        assertEquals("a drag doesn't zoom", before.scale, after.scale, 1e-6)
        assertNotEquals("it moved", before.x, after.x)
        assertTrue("moved left: $before → $after", after.x < before.x)
        assertTrue("moved up: $before → $after", after.y < before.y)
    }

    @Test
    fun aDoubleTapFitsItAgain() = opened { scenario ->
        val fitted = view(scenario)
        web().pinchOpen(0.6f, 2000)
        settle()
        assertTrue(view(scenario).scale > fitted.scale * 1.3)
        val b = web().visibleBounds
        device.click(b.centerX(), b.centerY())
        device.click(b.centerX(), b.centerY())
        settle()
        assertEquals(fitted.scale, view(scenario).scale, fitted.scale * 0.01)
    }
}
