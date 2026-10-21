package com.kingscan.app.model

import kotlinx.serialization.Serializable

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
data class PageMeta(
    val id: String,
    val fileName: String,
    val width: Int,
    val height: Int,
    val filter: FilterType,
)

@Serializable
data class ScanDocument(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pages: List<PageMeta>,
)

data class EncodedPage(
    val jpeg: ByteArray,
    val width: Int,
    val height: Int,
    val filter: FilterType,
)

sealed interface Screen {
    data object Library : Screen
    data object Capture : Screen
    data object Crop : Screen
    data object Filter : Screen
    data class Detail(val documentId: String) : Screen
}
