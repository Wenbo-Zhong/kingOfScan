package com.kingscan.app.core.imaging

import com.kingscan.app.core.document.EncodedPage
import com.kingscan.app.core.document.PageEdit

/** Identifies completed operations; a saved base must never be warped or filtered again. */
enum class PageStage { OrientedOriginal, Geometry, Retouched, Adjusted }

object PageRenderer {
    fun base(source: RgbaImage, edit: PageEdit, stage: PageStage): RgbaImage {
        var image = source
        if (stage == PageStage.OrientedOriginal) image = PageGeometry.apply(image, edit)
        if (stage <= PageStage.Geometry) image = PageRetouch.apply(image, edit.retouches, edit.outputQuarterTurns)
        if (stage <= PageStage.Retouched) image = PageMarkup.adjust(ImageFilters.apply(image, edit.filter), edit.adjustments)
        return image
    }

    fun render(source: RgbaImage, edit: PageEdit, stage: PageStage): RgbaImage =
        PageMarkup.render(base(source, edit, stage), edit.marks)

    fun encode(
        codec: ImageCodec, source: RgbaImage, edit: PageEdit, stage: PageStage,
        originalBytes: ByteArray? = null,
        retainBase: Boolean = edit.marks.isNotEmpty() || edit.retouches.isNotEmpty(),
    ): EncodedPage {
        val base = base(source, edit, stage)
        val rendered = PageMarkup.render(base, edit.marks)
        return EncodedPage(
            codec.encodeJpeg(rendered, ScanLimits.JPEG_QUALITY), rendered.width, rendered.height, edit.filter,
            originalBytes = originalBytes, edit = edit,
            annotationBaseBytes = if (retainBase) codec.encodeJpeg(base, ScanLimits.JPEG_QUALITY) else null,
        )
    }
}
