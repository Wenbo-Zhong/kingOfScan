package com.kingscan.app.data

import com.kingscan.app.AppGraph
import com.kingscan.app.imaging.ImageFilters
import com.kingscan.app.imaging.PageMarkup
import com.kingscan.app.imaging.PageGeometry
import com.kingscan.app.imaging.PageRetouch
import com.kingscan.app.imaging.RgbaImage
import com.kingscan.app.imaging.ScanLimits
import com.kingscan.app.model.*

object PageOperations {
    fun rotate(documentId: String, page: PageMeta): ScanDocument? {
        val repository = AppGraph.repository
        val rotated = AppGraph.codec.decode(repository.readBytes(repository.pagePath(documentId, page)), ScanLimits.MAX_EDGE).rotate90CW()
        val base = page.annotationBaseFileName?.let {
            AppGraph.codec.decode(repository.readBytes(repository.annotationBasePath(documentId, page)), ScanLimits.MAX_EDGE).rotate90CW()
        }
        val edit = page.edit ?: PageEdit(filter = page.filter)
        return repository.replacePage(documentId, page.id, EncodedPage(
            AppGraph.codec.encodeJpeg(rotated, ScanLimits.JPEG_QUALITY), rotated.width, rotated.height, page.filter,
            edit = edit.copy(outputQuarterTurns = (edit.outputQuarterTurns + 1).mod(4),
                retouches = edit.retouches.map { effect -> effect.copy(strokes = effect.strokes.map { stroke ->
                    stroke.copy(points = stroke.points.map { PointF(1f - it.y, it.x) })
                }) },
                marks = edit.marks.map { mark -> mark.copy(points = mark.points.map { PointF(1f - it.y, it.x) },
                    quarterTurns = (mark.quarterTurns + 1).mod(4)) }),
            annotationBaseBytes = base?.let { AppGraph.codec.encodeJpeg(it, ScanLimits.JPEG_QUALITY) },
        ))
    }

    fun filter(documentId: String, page: PageMeta, filter: FilterType): ScanDocument? {
        val repository = AppGraph.repository
        val edit = page.edit ?: PageEdit(filter = page.filter)
        val restored = PageRetouch.apply(geometricSource(documentId, page, edit), edit.retouches, edit.outputQuarterTurns)
        val base = PageMarkup.adjust(ImageFilters.apply(restored, filter), edit.adjustments)
        val rendered = PageMarkup.render(base, edit.marks)
        return repository.replacePage(documentId, page.id, EncodedPage(
            AppGraph.codec.encodeJpeg(rendered, ScanLimits.JPEG_QUALITY), rendered.width, rendered.height, filter,
            edit = edit.copy(filter = filter),
            annotationBaseBytes = if (edit.marks.isNotEmpty() || edit.retouches.isNotEmpty()) AppGraph.codec.encodeJpeg(base, ScanLimits.JPEG_QUALITY) else null,
        ))
    }

    fun geometricSource(documentId: String, page: PageMeta, edit: PageEdit = page.edit ?: PageEdit(filter = page.filter)): RgbaImage {
        val repository = AppGraph.repository
        val original = repository.originalPath(documentId, page)
        val source = if (original != null) {
            var image = AppGraph.codec.decode(repository.readBytes(original), ScanLimits.MAX_EDGE)
            repeat(edit.quarterTurns.mod(4)) { image = image.rotate90CW() }
            PageGeometry.apply(image, edit)
        } else AppGraph.codec.decode(repository.readBytes(repository.annotationBasePath(documentId, page)), ScanLimits.MAX_EDGE)
        return source
    }
}
