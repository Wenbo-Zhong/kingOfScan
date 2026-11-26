package com.kingscan.app

import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kingscan.app.core.imaging.*
import java.io.File
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Pass input=/data/local/tmp/outline-input.png to replay a retained image on ART. */
@RunWith(AndroidJUnit4::class)
class PreviewLatencyIntegrationTest {
    @Test fun retainedCameraInput() {
        val arguments = InstrumentationRegistry.getArguments()
        val path = arguments.getString("input") ?: return
        val bitmap = requireNotNull(BitmapFactory.decodeFile(path))
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val small = RgbaImage(bitmap.width, bitmap.height, pixels).scaledDown(ScanLimits.DETECT_EDGE)
        bitmap.recycle()
        val image = RgbaImage(small.width, small.height, IntArray(small.pixels.size) { i ->
            val p = small.pixels[i]
            val gray = (p.red() * 77 + p.green() * 150 + p.blue() * 29) shr 8
            argb(255, gray, gray, gray)
        })
        fun timed(block: () -> Unit): Double {
            val start = SystemClock.elapsedRealtimeNanos()
            block()
            return (SystemClock.elapsedRealtimeNanos() - start) / 1e6
        }
        val result = JSONObject()
        result.put("cold_analysis_ms", timed {
            val quad = requireNotNull(CaptureAnalysis.analyze(image, 0).quad)
            val points = listOf(quad.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft)
            result.put("quad", JSONArray().apply {
                points.forEach { put(JSONArray().put(it.x.toDouble()).put(it.y.toDouble())) }
            })
            arguments.getString("expected_quad")?.let { reference ->
                val expected = JSONArray(reference)
                assertTrue("Expected four corners", expected.length() == 4)
                points.forEachIndexed { index, point ->
                    val corner = expected.getJSONArray(index)
                    assertTrue("Corner $index differs from expected document",
                        abs(point.x - corner.getDouble(0)) < 0.025 &&
                            abs(point.y - corner.getDouble(1)) < 0.025)
                }
            }
        })
        repeat(12) { CaptureAnalysis.analyze(image, it * 120L) }
        fun measure(name: String, block: () -> Unit) {
            val values = DoubleArray(40) { timed(block) }.sorted()
            result.put(name, JSONObject().put("median_ms", values[20]).put("p95_ms", values[37]))
        }
        measure("detector") { DocumentDetector.detectOrNull(image) }
        measure("analysis") { CaptureAnalysis.analyze(image, 0) }
        val analyzer = CapturePreviewAnalyzer()
        val acquisition = JSONArray()
        repeat(12) { index ->
            val frame = analyzer.analyze(image, index * 120L)
            acquisition.put(JSONObject().put("time_ms", frame.timeMillis)
                .put("visible", frame.quad != null).put("fresh", frame.geometryFresh))
        }
        assertTrue(acquisition.getJSONObject(3).getBoolean("visible"))
        result.put("acquisition", acquisition)
        result.put("device", "${android.os.Build.MODEL} / ${android.os.Build.SUPPORTED_ABIS.joinToString()}")
        result.put("debuggable", InstrumentationRegistry.getInstrumentation().targetContext
            .applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
        val output = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
            "preview-latency-${arguments.getString("label") ?: "run"}.json")
        output.writeText(result.toString(2))
    }
}
