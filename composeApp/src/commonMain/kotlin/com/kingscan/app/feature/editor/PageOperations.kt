package com.kingscan.app.feature.editor

import com.kingscan.app.core.storage.DocumentRepository

import com.kingscan.app.core.imaging.ImageCodec
import com.kingscan.app.core.imaging.PageRenderer
import com.kingscan.app.core.imaging.PageStage
import com.kingscan.app.core.imaging.PageGeometry
import com.kingscan.app.core.imaging.RgbaImage
import com.kingscan.app.core.imaging.ScanLimits
import com.kingscan.app.core.document.*

class PageOperations(private val repository: DocumentRepository, private val codec: ImageCodec) {
    fun rotate(documentId: String, page: PageMeta): ScanDocument? {
        val rotated = codec.decode(repository.readBytes(repository.pagePath(documentId, page)), ScanLimits.MAX_EDGE).rotate90CW()
        val base = page.annotationBaseFileName?.let {
            codec.decode(repository.readBytes(repository.annotationBasePath(documentId, page)), ScanLimits.MAX_EDGE).rotate90CW()
        }
        val edit = page.edit ?: PageEdit(filter = page.filter)
        return repository.replacePage(documentId, page.id, EncodedPage(
            codec.encodeJpeg(rotated, ScanLimits.JPEG_QUALITY), rotated.width, rotated.height, page.filter,
            edit = edit.copy(outputQuarterTurns = (edit.outputQuarterTurns + 1).mod(4),
                retouches = edit.retouches.map { effect -> effect.copy(strokes = effect.strokes.map { stroke ->
                    stroke.copy(points = stroke.points.map { PointF(1f - it.y, it.x) })
                }) },
                marks = edit.marks.map { mark -> mark.copy(points = mark.points.map { PointF(1f - it.y, it.x) },
                    quarterTurns = (mark.quarterTurns + 1).mod(4)) }),
            annotationBaseBytes = base?.let { codec.encodeJpeg(it, ScanLimits.JPEG_QUALITY) },
        ))
    }

    fun filter(documentId: String, page: PageMeta, filter: FilterType): ScanDocument? {
        val edit = (page.edit ?: PageEdit(filter = page.filter)).copy(filter = filter)
        return repository.replacePage(documentId, page.id,
            PageRenderer.encode(codec, geometricSource(documentId, page, edit), edit, PageStage.Geometry))
    }

    fun geometricSource(documentId: String, page: PageMeta, edit: PageEdit = page.edit ?: PageEdit(filter = page.filter)): RgbaImage {
        val original = repository.originalPath(documentId, page)
        val source = if (original != null) {
            var image = codec.decode(repository.readBytes(original), ScanLimits.MAX_EDGE)
            repeat(edit.quarterTurns.mod(4)) { image = image.rotate90CW() }
            PageGeometry.apply(image, edit)
        } else codec.decode(repository.readBytes(repository.annotationBasePath(documentId, page)), ScanLimits.MAX_EDGE)
        return source
    }
}
