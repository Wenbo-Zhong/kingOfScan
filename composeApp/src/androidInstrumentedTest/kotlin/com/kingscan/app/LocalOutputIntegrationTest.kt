package com.kingscan.app

import android.content.ContentUris
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.print.*
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kingscan.app.data.PageOperations
import com.kingscan.app.imaging.*
import com.kingscan.app.model.*
import com.kingscan.app.pdf.AndroidPdfResources
import com.kingscan.app.ui.printPageCount
import com.kingscan.app.ui.writePrintPages
import com.kingscan.app.ui.saveImagesToGallery
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LocalOutputIntegrationTest {
    @Test fun retouchSurvivesRotationFilteringAndReopenAndOutputsStayLocal() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "output-verification").apply { mkdirs() }
        AppGraph.init(File(context.filesDir, "kingscan").absolutePath)
        PDFBoxResourceLoader.init(context); AndroidPdfResources.context = context
        val repo = AppGraph.repository
        val paper = PageMarkup.render(RgbaImage.filled(1000, 700, 0xFFF8F8F8.toInt()), listOf(
            PageMark("fixture", MarkType.Text, listOf(PointF(0.05f, 0.08f)), text = "修复测试 OFFLINE 2026", strokeWidth = 0.055f),
        ))
        val dirty = RgbaImage(1000, 700, paper.pixels.copyOf())
        for (y in 350..365) for (x in 100..430) dirty.pixels[y * 1000 + x] = 0xFFDD2233.toInt()
        for (y in 425..440) for (x in 100..430) dirty.pixels[y * 1000 + x] = 0xFF2244DD.toInt()
        for (y in 510..530) for (x in 750..800) dirty.pixels[y * 1000 + x] = 0xFF161616.toInt()
        val originalBytes = AppGraph.codec.encodeJpeg(dirty, 98)
        File(root, "retouch-original.jpg").writeBytes(originalBytes)
        var document = repo.create("离线修复与输出验证", listOf(EncodedPage(
            originalBytes, 1000, 700, FilterType.Original, originalBytes = originalBytes,
            edit = PageEdit(filter = FilterType.Original),
        )))
        val effects = listOf(
            RetouchEdit(RetouchKind.ColoredInk),
            RetouchEdit(strokes = listOf(RetouchStroke(listOf(PointF(0.775f, 0.74f)), width = 0.11f))),
        )
        val page = document.pages.single()
        val source = PageOperations.geometricSource(document.id, page)
        val restored = PageRetouch.apply(source, effects)
        assertTrue(restored.pixel(260, 357).green() > 235)
        assertTrue(restored.pixel(260, 430).red() > 235)
        assertTrue(restored.pixel(775, 520).red() > 235)
        val outputBytes = AppGraph.codec.encodeJpeg(restored, 98)
        File(root, "retouch-result.jpg").writeBytes(outputBytes)
        document = repo.replacePage(document.id, page.id, EncodedPage(outputBytes, 1000, 700,
            FilterType.Original, edit = page.edit!!.copy(retouches = effects), annotationBaseBytes = outputBytes))!!
        document = PageOperations.rotate(document.id, document.pages.single())!!
        document = PageOperations.filter(document.id, document.pages.single(), FilterType.Gray)!!
        val reopened = repo.load(document.id)!!
        assertEquals(2, reopened.pages.single().edit!!.retouches.size)
        assertArrayEquals(originalBytes, repo.readBytes(repo.originalPath(document.id, reopened.pages.single())!!))
        val redecoded = AppGraph.codec.decode(repo.readBytes(repo.pagePath(document.id, reopened.pages.single())), 2000)
        assertTrue(redecoded.pixel(179, 775).red() > 225)
        assertTrue("Colour erasure remains effective after grayscale", redecoded.pixel(342, 260).red() > 235)
        assertTrue("Blue erasure remains effective after grayscale", redecoded.pixel(269, 260).red() > 235)
        assertNull(reopened.pages.single().ocr)
        File(root, "retouch-rotated-refiltered.jpg").writeBytes(AppGraph.codec.encodeJpeg(redecoded, 98))

        fun galleryIds(): Set<Long> {
            val ids = mutableSetOf<Long>()
            context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID),
                "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?", arrayOf("KingOfScan_%"), null)?.use { cursor ->
                while (cursor.moveToNext()) ids += cursor.getLong(0)
            }
            return ids
        }
        val before = galleryIds()
        assertEquals(1, saveImagesToGallery(context, listOf(repo.pagePath(document.id, reopened.pages.single()))))
        val published = galleryIds() - before
        assertEquals(1, published.size)
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, published.single())
        try {
            val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            assertArrayEquals(repo.readBytes(repo.pagePath(document.id, reopened.pages.single())), bytes)
            File(root, "gallery-published.jpg").writeBytes(bytes)
        } finally { context.contentResolver.delete(uri, null, null) }

        val pages = (1..3).map { index ->
            val image = PageMarkup.render(paper, listOf(PageMark("number", MarkType.Text,
                listOf(PointF(0.1f, 0.8f)), text = "PAGE $index", strokeWidth = 0.05f)))
            EncodedPage(AppGraph.codec.encodeJpeg(image, 98), image.width, image.height, FilterType.Original)
        }
        val printDoc = repo.create("打印页面选择验证", pages)
        val sourcePdf = File(repo.exportPdf(printDoc.id, ExportOptions(searchable = false)))
        assertEquals(3, printPageCount(sourcePdf))
        val printed = File(root, "print-selected-page.pdf")
        printed.outputStream().use { writePrintPages(sourcePdf, arrayOf(PageRange(1, 1)), it, CancellationSignal()) }
        PDDocument.load(printed).use { assertEquals(1, it.numberOfPages) }
        fun render(file: File, index: Int): Bitmap {
            val bitmap = Bitmap.createBitmap(350, 500, Bitmap.Config.ARGB_8888)
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { pdf -> pdf.openPage(index).use { it.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT) } }
            }
            return bitmap
        }
        val expected = render(sourcePdf, 1); val actual = render(printed, 0)
        assertTrue("Printed image is the second source page", expected.sameAs(actual))
        File(root, "print-selected-page.jpg").outputStream().use { actual.compress(Bitmap.CompressFormat.JPEG, 98, it) }
        expected.recycle(); actual.recycle()
        File(root, "result.txt").writeText(
            "PASS local colour and selected-mask restoration\nPASS original/edit persistence after rotation and filter\n" +
                "PASS gallery publishes byte-identical JPEG\nPASS printer reports 3 pages and outputs selected page only\n")
    }
}
