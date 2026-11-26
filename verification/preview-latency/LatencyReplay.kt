import com.kingscan.app.core.document.Quad
import com.kingscan.app.core.imaging.*
import java.io.File
import javax.imageio.ImageIO

private fun load(path: String): RgbaImage {
    val input = requireNotNull(ImageIO.read(File(path)))
    val pixels = IntArray(input.width * input.height)
    input.getRGB(0, 0, input.width, input.height, pixels, 0, input.width)
    return RgbaImage(input.width, input.height, pixels)
}

private fun points(quad: Quad?) = quad?.let {
    listOf(it.topLeft, it.topRight, it.bottomRight, it.bottomLeft)
        .joinToString(",", "[", "]") { p -> "[${p.x},${p.y}]" }
} ?: "null"

private fun timed(block: () -> Unit): Double {
    val start = System.nanoTime()
    block()
    return (System.nanoTime() - start) / 1e6
}

fun main(args: Array<String>) {
    val image = load(args[0]).scaledDown(ScanLimits.DETECT_EDGE).let { small ->
        // Android analyzes luminance only; use the same integer grayscale on both runs.
        RgbaImage(small.width, small.height, IntArray(small.pixels.size) { i ->
            val p = small.pixels[i]
            val gray = (p.red() * 77 + p.green() * 150 + p.blue() * 29) shr 8
            argb(255, gray, gray, gray)
        })
    }
    val cold = timed { CaptureAnalysis.analyze(image, 0) }
    repeat(20) { CaptureAnalysis.analyze(image, it * 120L) }
    fun benchmark(name: String, block: () -> Unit) {
        val times = DoubleArray(60) { timed(block) }.sorted()
        println("""{"kind":"timing","stage":"$name","cold_analysis_ms":$cold,"median_ms":${times[30]},"p95_ms":${times[56]}}""")
    }
    benchmark("detector") { DocumentDetector.detectOrNull(image) }
    benchmark("analysis") { CaptureAnalysis.analyze(image, 0) }
    val tracker = CapturePreviewTracker()
    repeat(12) { index ->
        val frame = tracker.update(CaptureAnalysis.analyze(image, index * 120L))
        println("""{"kind":"acquisition","time_ms":${frame.timeMillis},"quad":${points(frame.quad)},"fresh":${frame.geometryFresh}}""")
    }
    var rotated = load(args[0])
    repeat(4) { turn ->
        println("""{"kind":"rotation","degrees":${turn * 90},"quad":${points(DocumentDetector.detectOrNull(rotated))}}""")
        rotated = rotated.rotate90CW()
    }
    if (args.size > 1) {
        val analyzer = CapturePreviewAnalyzer()
        File(args[1]).listFiles()!!.filter { it.extension == "png" }.sortedBy { it.name }
            .forEachIndexed { index, file ->
                val frame = analyzer.analyze(load(file.path).scaledDown(480), index * 125L)
                println("""{"kind":"replay","index":$index,"quad":${points(frame.quad)},"fresh":${frame.geometryFresh}}""")
            }
    }
}
