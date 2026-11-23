package com.kingscan.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kingscan.app.imaging.PageMarkup
import com.kingscan.app.imaging.RgbaImage
import com.kingscan.app.model.*
import com.kingscan.app.ocr.OcrLanguage
import com.kingscan.app.ocr.createOcrEngine
import com.kingscan.app.pdf.AndroidPdfResources
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.rendering.PDFRenderer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import com.kingscan.app.ui.Navigator
import com.kingscan.app.ui.importFiles
import com.kingscan.app.ui.processNextImport
import com.kingscan.app.ui.openEditor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exercises bundled models and native PDF libraries on an actual Android runtime. */
@RunWith(AndroidJUnit4::class)
class OfflineDocumentIntegrationTest {
    @Test
    fun bundledChineseOcrSearchableEncryptedPdfAndRedaction() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "offline-verification").apply { mkdirs() }
        AppGraph.init(root.absolutePath)
        PDFBoxResourceLoader.init(context)
        AndroidPdfResources.context = context
        val bitmap = Bitmap.createBitmap(1200, 1600, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; textSize = 62f
            typeface = Typeface.createFromAsset(context.assets, "fonts/NotoSansSC-Regular.ttf")
        }
        canvas.drawText("离线扫描测试", 80f, 160f, paint)
        canvas.drawText("KingOfScan OFFLINE", 80f, 320f, paint)
        canvas.drawText("2026 123456", 80f, 480f, paint)
        val pixels = IntArray(1200 * 1600)
        bitmap.getPixels(pixels, 0, 1200, 0, 0, 1200, 1600)
        bitmap.recycle()
        val image = RgbaImage(1200, 1600, pixels)
        val input = AppGraph.codec.encodeJpeg(image, 98)
        File(root, "input.jpg").writeBytes(input)
        val ocr = createOcrEngine().recognize(image, OcrLanguage.Chinese)
        File(root, "recognized.txt").writeText(ocr.text)
        assertTrue("Chinese text must be recognized offline: ${ocr.text}", ocr.text.contains("离线扫描测试"))
        assertTrue(ocr.text.contains("123456"))
        assertTrue(ocr.lines.isNotEmpty())
        val document = AppGraph.repository.create("离线验证", listOf(
            EncodedPage(input, image.width, image.height, FilterType.Original, input, PageEdit(filter = FilterType.Original), ocr)))
        val path = AppGraph.repository.exportPdf(document.id, ExportOptions(paper = PdfPaper.A4, pageNumbers = true,
            password = "scan-2026", searchable = true, marginPoints = 24f, jpegQuality = 80))
        var rejected = false
        try { PDDocument.load(File(path), "wrong").close() } catch (_: InvalidPasswordException) { rejected = true }
        assertTrue("Wrong password must be rejected", rejected)
        PDDocument.load(File(path), "scan-2026").use { pdf ->
            assertEquals(1, pdf.numberOfPages)
            assertEquals(595.28f, pdf.getPage(0).mediaBox.width, 0.1f)
            val text = PDFTextStripper().getText(pdf)
            File(root, "pdf-text.txt").writeText(text)
            assertTrue("PDF Chinese text layer must be extractable: $text", text.contains("离线扫描测试"))
            assertTrue(text.contains("123456"))
            val rendered = PDFRenderer(pdf).renderImage(0, 1.5f)
            try {
                File(root, "pdf-page.jpg").outputStream().use { rendered.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            } finally { rendered.recycle() }
        }
        File(path).copyTo(File(root, "encrypted-searchable.pdf"), overwrite = true)
        val navigatorScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val snapshotObserver = Snapshot.registerGlobalWriteObserver {
            navigatorScope.launch { Snapshot.sendApplyNotifications() }
        }
        try {
            val navigator = Navigator(navigatorScope)
            val pdfImport = AppGraph.repository.stageImport(File(path).readBytes(), "encrypted.pdf", isPdf = true)
            val imageImport = AppGraph.repository.stageImport(input, "next.jpg")
            withContext(Dispatchers.Main) {
                AppGraph.session.beginNew()
                importFiles(listOf(pdfImport, imageImport), {}, navigator, {})
            }
            val unlock = withTimeout(30_000) {
                snapshotFlow { navigator.current }.first { it is Screen.PdfUnlock }
            } as Screen.PdfUnlock
            withContext(Dispatchers.Main) { processNextImport({}, navigator, {}, unlock.file, "scan-2026") }
            withTimeout(30_000) { snapshotFlow { navigator.current }.first { it == Screen.Crop } }
            assertNotNull(AppGraph.session.source)
            val imported = AppGraph.repository.load(AppGraph.session.documentId!!)!!
            assertEquals(1, imported.pages.size)
            assertEquals(1, imported.originalPdfs.size)
            assertArrayEquals(File(path).readBytes(),
                AppGraph.repository.readBytes(AppGraph.repository.originalPdfPath(imported.id, imported.originalPdfs.first())))
            // A bad final image must leave earlier successful PDF pages accessible.
            withContext(Dispatchers.Main) { openEditor(byteArrayOf(1, 2, 3), {}, navigator, {}) }
            withTimeout(30_000) { snapshotFlow { navigator.current }.first { it is Screen.Detail } }
            assertEquals(imported.id, (navigator.current as Screen.Detail).documentId)
        } finally {
            snapshotObserver.dispose()
            withContext(Dispatchers.Main) { AppGraph.session.cancel() }
            navigatorScope.cancel()
        }
        // A burned-in redaction clears old OCR before the next searchable export.
        val redaction = PageMark("redaction", MarkType.Redaction,
            listOf(PointF(0f, 0.23f), PointF(1f, 0.33f)))
        val marked = PageMarkup.render(image, listOf(redaction))
        assertEquals(Color.BLACK, marked.pixel(200, 450))
        val updated = AppGraph.repository.replacePage(document.id, document.pages.first().id,
            EncodedPage(AppGraph.codec.encodeJpeg(marked, 95), 1200, 1600, FilterType.Original,
                edit = PageEdit(filter = FilterType.Original, marks = listOf(redaction)), annotationBaseBytes = input))
        assertNull(updated!!.pages.first().ocr)
        assertArrayEquals(input, AppGraph.repository.readBytes(AppGraph.repository.annotationBasePath(updated.id, updated.pages.first())))
        File(root, "redacted.jpg").writeBytes(AppGraph.repository.readBytes(AppGraph.repository.pagePath(updated.id, updated.pages.first())))
        File(root, "result.txt").writeText("PASS\nBundled Chinese OCR\nCJK searchable PDF\nAES password and A4 layout\nEncrypted PDF followed by image import\nOriginal vector PDF preserved\nInvalid final image keeps prior pages accessible\nRedaction clears OCR and preserves editable base\n")
    }
}
