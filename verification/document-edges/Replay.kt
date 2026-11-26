import com.kingscan.app.core.imaging.DocumentDetector
import com.kingscan.app.core.imaging.RgbaImage
import java.io.File
import javax.imageio.ImageIO

/** JVM replay of the unchanged commonMain detector; no substitute implementation. */
fun main(args: Array<String>) {
    for (path in args) {
        val input = ImageIO.read(File(path))
        val pixels = IntArray(input.width * input.height)
        input.getRGB(0, 0, input.width, input.height, pixels, 0, input.width)
        val image = RgbaImage(input.width, input.height, pixels)
        val firstStart = System.nanoTime()
        val quad = DocumentDetector.detectOrNull(image)
        val firstMs = (System.nanoTime() - firstStart) / 1e6
        repeat(8) { DocumentDetector.detectOrNull(image) }
        val times = DoubleArray(20) {
            val start = System.nanoTime()
            DocumentDetector.detectOrNull(image)
            (System.nanoTime() - start) / 1e6
        }.sorted()
        val points = quad?.let {
            listOf(it.topLeft, it.topRight, it.bottomRight, it.bottomLeft)
                .joinToString(",", "[", "]") { p -> "[${p.x},${p.y}]" }
        } ?: "null"
        println("""{"file":"${File(path).name}","quad":$points,"first_ms":$firstMs,"median_ms":${times[10]},"p95_ms":${times[18]}}""")
    }
}
