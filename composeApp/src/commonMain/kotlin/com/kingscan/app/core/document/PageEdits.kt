package com.kingscan.app.core.document

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
