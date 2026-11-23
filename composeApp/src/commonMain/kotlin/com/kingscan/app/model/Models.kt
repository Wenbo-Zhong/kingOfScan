package com.kingscan.app.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi

@Serializable
enum class FilterType {
    Original,
    Enhance,
    Gray,
    BlackWhite,
    Soft,
    Brighten,
    Sharpen,
    RemoveShadow,
    InkSaver,
    Invert,
}

fun FilterType.label(): String = when (this) {
    FilterType.Original -> "原图"
    FilterType.Enhance -> "增强"
    FilterType.Gray -> "灰度"
    FilterType.BlackWhite -> "黑白"
    FilterType.Soft -> "柔和"
    FilterType.Brighten -> "提亮"
    FilterType.Sharpen -> "锐化"
    FilterType.RemoveShadow -> "去阴影"
    FilterType.InkSaver -> "省墨"
    FilterType.Invert -> "反色"
}

@Serializable
data class PointF(val x: Float, val y: Float) {
    fun clamped(): PointF = PointF(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
}

/**
 * Document corners in normalized image coordinates, origin at the top-left.
 * Order is top-left, top-right, bottom-right, bottom-left.
 */
@Serializable
data class Quad(
    val topLeft: PointF,
    val topRight: PointF,
    val bottomRight: PointF,
    val bottomLeft: PointF,
) {
    fun clamped(): Quad = Quad(
        topLeft.clamped(),
        topRight.clamped(),
        bottomRight.clamped(),
        bottomLeft.clamped(),
    )

    fun translate(dx: Float, dy: Float): Quad {
        val xs = listOf(topLeft.x, topRight.x, bottomRight.x, bottomLeft.x)
        val ys = listOf(topLeft.y, topRight.y, bottomRight.y, bottomLeft.y)
        val allowedX = dx.coerceIn(-xs.min(), 1f - xs.max())
        val allowedY = dy.coerceIn(-ys.min(), 1f - ys.max())
        fun shift(p: PointF) = PointF(p.x + allowedX, p.y + allowedY)
        return Quad(shift(topLeft), shift(topRight), shift(bottomRight), shift(bottomLeft))
    }

    fun withCorner(index: Int, point: PointF): Quad = when (index) {
        0 -> copy(topLeft = point.clamped())
        1 -> copy(topRight = point.clamped())
        2 -> copy(bottomRight = point.clamped())
        3 -> copy(bottomLeft = point.clamped())
        else -> this
    }

    /** Clockwise 90 degrees, matching [com.kingscan.app.imaging.RgbaImage.rotate90CW]. */
    fun rotate90CW(): Quad {
        fun turn(p: PointF) = PointF(1f - p.y, p.x)
        return Quad(
            topLeft = turn(bottomLeft),
            topRight = turn(topLeft),
            bottomRight = turn(topRight),
            bottomLeft = turn(bottomRight),
        )
    }

    fun isAlmostRect(inset: Float = 0.012f): Boolean {
        val full = full()
        return distance(topLeft, full.topLeft) < inset &&
            distance(topRight, full.topRight) < inset &&
            distance(bottomRight, full.bottomRight) < inset &&
            distance(bottomLeft, full.bottomLeft) < inset
    }

    private fun distance(a: PointF, b: PointF): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    companion object {
        fun full(): Quad = Quad(PointF(0f, 0f), PointF(1f, 0f), PointF(1f, 1f), PointF(0f, 1f))

        fun inset(margin: Float): Quad {
            val m = margin.coerceIn(0f, 0.45f)
            return Quad(
                PointF(m, m),
                PointF(1f - m, m),
                PointF(1f - m, 1f - m),
                PointF(m, 1f - m),
            )
        }
    }
}

@Serializable
data class ImageAdjustments(
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
)

@Serializable
enum class MarkType { Pen, Signature, Text, Redaction }

/** Coordinates are relative to the corrected page, so exports are resolution independent. */
@Serializable
data class PageMark(
    val id: String,
    val type: MarkType,
    val points: List<PointF> = emptyList(),
    val text: String = "",
    val color: Long = 0xFF202020,
    val strokeWidth: Float = 0.004f,
    val opacity: Float = 1f,
    val quarterTurns: Int = 0,
)

@Serializable
data class SavedSignature(
    val id: String, val name: String, val points: List<PointF>, val strokeWidth: Float = 0.005f,
    val paths: List<List<PointF>> = emptyList(), val aspectRatio: Float = 3f,
)

@Serializable
data class PageEdit(
    val quad: Quad = Quad.full(),
    val quarterTurns: Int = 0,
    val filter: FilterType = FilterType.Enhance,
    val adjustments: ImageAdjustments = ImageAdjustments(),
    val marks: List<PageMark> = emptyList(),
    val book: BookPageEdit? = null,
    val outputQuarterTurns: Int = 0,
    val retouches: List<RetouchEdit> = emptyList(),
)

@Serializable
enum class RetouchKind { Erase, ColoredInk, Denoise, Deblur, SmartHd, Handwriting, Fingers, Watermark, Demoire }

@Serializable
data class RetouchStroke(val points: List<PointF>, val width: Float = 0.035f)

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class RetouchEdit(
    @EncodeDefault val kind: RetouchKind = RetouchKind.Erase,
    val strokes: List<RetouchStroke> = emptyList(),
    @EncodeDefault val strength: Float = 0.5f,
    @EncodeDefault val modelVersion: String = "kingscan-document-ai-20261123-v1",
    @EncodeDefault val seed: Int = 20261123,
    @EncodeDefault val orientation: Int = 0,
)

@Serializable
data class BookPageEdit(
    val rightPage: Boolean = false,
    val seamTop: Float = 0.5f,
    val seamBottom: Float = 0.5f,
    val topCurve: Float = 0f,
    val bottomCurve: Float = 0f,
)

@Serializable
data class OcrLine(
    val text: String,
    val topLeft: PointF,
    val bottomRight: PointF,
)

@Serializable
data class OcrResult(
    val text: String,
    val lines: List<OcrLine> = emptyList(),
    val languages: List<String> = emptyList(),
)

@Serializable
data class PageMeta(
    val id: String,
    val fileName: String,
    val width: Int,
    val height: Int,
    val filter: FilterType,
    val originalFileName: String? = null,
    val edit: PageEdit? = null,
    val ocr: OcrResult? = null,
    val annotationBaseFileName: String? = null,
)

@Serializable
data class ScanDocument(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pages: List<PageMeta>,
    val folderId: String? = null,
    val tags: List<String> = emptyList(),
    val starred: Boolean = false,
    val notes: String = "",
    val deletedAt: Long? = null,
    val originalPdfs: List<OriginalPdf> = emptyList(),
    val sourcePages: List<PageMeta> = emptyList(),
    val composition: CompositionOptions? = null,
    val compositionSourceIds: List<String> = emptyList(),
)

@Serializable
enum class CompositionKind { Grid, Vertical, Horizontal, IdCard, Overlap }

@Serializable
data class CompositionOptions(
    val kind: CompositionKind = CompositionKind.Grid,
    val columns: Int = 2,
    val gap: Int = 24,
    val outputWidth: Int = 1600,
)

@Serializable
data class OriginalPdf(val name: String, val fileName: String)

@Serializable
data class DocumentFolder(val id: String, val name: String)

@Serializable
enum class ScanMode { Document, IdCard, Book, LargeFormat }

fun ScanMode.label(): String = when (this) {
    ScanMode.Document -> "文档"
    ScanMode.IdCard -> "证件"
    ScanMode.Book -> "书籍"
    ScanMode.LargeFormat -> "大幅面"
}

@Serializable
enum class PdfPaper { FitImage, A4, Letter }

@Serializable
data class ExportOptions(
    val paper: PdfPaper = PdfPaper.FitImage,
    val landscape: Boolean = false,
    val marginPoints: Float = 18f,
    val jpegQuality: Int = 90,
    val pageNumbers: Boolean = false,
    val searchable: Boolean = true,
    val password: String = "",
)

@Serializable
data class AppSettings(
    val defaultFilter: FilterType = FilterType.Enhance,
    val autoCapture: Boolean = false,
    val continuousCapture: Boolean = false,
    val scanMode: ScanMode = ScanMode.Document,
    val bookRightToLeft: Boolean = false,
    val autoSaveToGallery: Boolean = false,
    val deviceLock: Boolean = false,
    val export: ExportOptions = ExportOptions(),
)

data class EncodedPage(
    val jpeg: ByteArray,
    val width: Int,
    val height: Int,
    val filter: FilterType,
    val originalBytes: ByteArray? = null,
    val edit: PageEdit? = null,
    val ocr: OcrResult? = null,
    val annotationBaseBytes: ByteArray? = null,
)

data class ImportFile(val path: String, val name: String, val isPdf: Boolean = false)

sealed interface Screen {
    data object Library : Screen
    data object Capture : Screen
    data object Crop : Screen
    data object Filter : Screen
    data object Book : Screen
    data class Detail(val documentId: String) : Screen
    data class PdfUnlock(val file: ImportFile) : Screen
    data class Ocr(val documentId: String, val pageId: String) : Screen
    data object Settings : Screen
    data object Codes : Screen
    data class Export(val documentId: String) : Screen
    data class Annotate(val documentId: String, val pageId: String) : Screen
    data class Retouch(val documentId: String, val pageId: String) : Screen
    data class Pages(val documentId: String) : Screen
    data class ComposePages(val documentId: String, val pageIds: List<String> = emptyList(),
        val kind: CompositionKind = CompositionKind.Grid) : Screen
}
