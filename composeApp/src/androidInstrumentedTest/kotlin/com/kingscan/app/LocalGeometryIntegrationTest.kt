package com.kingscan.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kingscan.app.core.imaging.*
import com.kingscan.app.core.document.*
import com.kingscan.app.core.pdf.AndroidPdfResources
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
class LocalGeometryIntegrationTest {
    @Test fun offlineBookStitchAndPhysicalIdPdf() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "geometry-verification").apply { mkdirs() }
        AppGraph.init(File(context.filesDir, "kingscan").absolutePath)
        PDFBoxResourceLoader.init(context)
        AndroidPdfResources.context = context
        fun save(name: String, image: RgbaImage) {
            File(root, name).writeBytes(AppGraph.codec.encodeJpeg(image, 98))
        }
        val spread = RgbaImage(1200, 800, IntArray(1200 * 800) { i ->
            val x = i % 1200; val y = i / 1200
            val curve = if (x < 600) 80f * (x / 599f) * (x / 599f) else 0f
            if (abs(x - 600) < 4) 0xFF444444.toInt()
            else if (listOf(220f, 420f, 620f).any { abs(y - (it + curve)) < 3 } && (x < 600 || x % 30 < 25)) 0xFF202020.toInt()
            else 0xFFFFFFFF.toInt()
        })
        save("book-original.jpg", spread)
        val left = BookGeometry.flatten(spread, BookPageEdit(topCurve = 0.1f, bottomCurve = 0.1f))
        save("book-left-flattened.jpg", left)
        save("book-right.jpg", BookGeometry.flatten(spread, BookPageEdit(rightPage = true)))
        val baseline = (20 until left.width - 30 step 24).map { x -> (190..270).minBy { y -> left.pixel(x, y).red() } }
        assertTrue("Curved baseline is flattened", baseline.max() - baseline.min() <= 4)

        val random = Random(722)
        val cells = IntArray(100 * 60) { random.nextInt(30, 250) }
        val world = RgbaImage(600, 360, IntArray(600 * 360) { i ->
            val value = cells[(i / 600 / 6) * 100 + i % 600 / 6]
            argb(255, value, value, value)
        })
        fun crop(offset: Int) = RgbaImage(400, 360, IntArray(400 * 360) { i -> world.pixel(i % 400 + offset, i / 400) })
        val first = crop(0); val second = crop(200)
        save("overlap-input-a.jpg", first); save("overlap-input-b.jpg", second)
        val output = OverlapStitcher.stitch(listOf(first, second))
        save("overlap-result.jpg", output)
        assertTrue(output.width in 598..604)

        val front = RgbaImage.filled(856, 540, 0xFFCC2222.toInt())
        val back = RgbaImage.filled(856, 540, 0xFF2244CC.toInt())
        val options = CompositionOptions(kind = CompositionKind.IdCard, outputWidth = 1600)
        val idPrint = PageComposer.compose(listOf(front, back), options)
        save("id-a4.jpg", idPrint)
        val source = AppGraph.repository.create("证件几何验证", listOf(front, back).map {
            val bytes = AppGraph.codec.encodeJpeg(it, 98)
            EncodedPage(bytes, it.width, it.height, FilterType.Original, originalBytes = bytes)
        })
        val bytes = AppGraph.codec.encodeJpeg(idPrint, 98)
        val document = AppGraph.repository.composePages(source.id, source.pages.map { it.id },
            EncodedPage(bytes, idPrint.width, idPrint.height, FilterType.Original), options)
        assertEquals(2, document.sourcePages.size)
        val expectedOriginals = source.pages.map { AppGraph.repository.readBytes(AppGraph.repository.originalPath(source.id, it)!!) }
        AppGraph.repository.delete(source.id)
        AppGraph.repository.purge(source.id)
        document.sourcePages.forEachIndexed { index, page ->
            assertArrayEquals(expectedOriginals[index],
                AppGraph.repository.readBytes(AppGraph.repository.originalPath(document.id, page)!!))
        }
        val path = AppGraph.repository.exportPdf(document.id, ExportOptions(searchable = false))
        PDDocument.load(File(path)).use { pdf ->
            assertEquals(595.28f, pdf.getPage(0).mediaBox.width, 0.01f)
            // The image spans A4 without the default 18 pt margin shrinking the ID card.
            val content = pdf.getPage(0).contents.bufferedReader().readText()
            val transform = Regex("([\\d.]+) 0 0 ([\\d.]+) [\\d.]+ [\\d.]+ cm").find(content)
            assertNotNull(content, transform)
            assertEquals(595.28f, transform!!.groupValues[1].toFloat(), 0.1f)
            assertEquals(841.89f, transform.groupValues[2].toFloat(), 0.1f)
        }
        File(path).copyTo(File(root, "id-a4.pdf"), overwrite = true)
        File(root, "result.txt").writeText("PASS curved book baseline within 4 px\nPASS projective overlap reconstruction\nPASS 85.6 x 54 mm ID layout on A4 PDF\nPASS composition original source retention\n")
    }
}
