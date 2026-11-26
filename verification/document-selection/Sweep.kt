import com.kingscan.app.core.imaging.*
import com.kingscan.app.core.document.*
import java.io.File
import javax.imageio.ImageIO

private fun points(quad: Quad?) = quad?.let {
    listOf(it.topLeft, it.topRight, it.bottomRight, it.bottomLeft)
        .joinToString(",", "[", "]") { "[${it.x},${it.y}]" }
} ?: "null"

/** Runs the actual commonMain detector, including an unrelated previous outline. */
fun main(args: Array<String>) {
    for (path in args) {
        val bitmap = requireNotNull(ImageIO.read(File(path)))
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getRGB(0, 0, bitmap.width, bitmap.height, pixels, 0, bitmap.width)
        var image = RgbaImage(bitmap.width, bitmap.height, pixels)
        repeat(4) { rotation ->
            println("""{"file":"${File(path).name}","degrees":${rotation * 90},"quad":${points(DocumentDetector.detectOrNull(image))}}""")
            image = image.rotate90CW()
        }
        image = RgbaImage(bitmap.width, bitmap.height, pixels)
        val background = if (File(path).name.startsWith("ours")) {
            Quad(PointF(.308f, .12f), PointF(.747f, .144f), PointF(.701f, .337f), PointF(.190f, .285f))
        } else {
            Quad(PointF(.34f, .211f), PointF(.717f, .251f), PointF(.68f, .392f), PointF(.234f, .361f))
        }
        println("""{"file":"${File(path).name}","degrees":0,"preferred_background":true,"quad":${points(DocumentDetector.detectOrNull(image, background))}}""")
    }
}
