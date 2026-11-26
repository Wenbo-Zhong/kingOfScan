package com.kingscan.app.feature.editor

import com.kingscan.app.core.storage.DocumentRepository

import com.kingscan.app.core.imaging.ImageCodec
import com.kingscan.app.core.imaging.PageMarkup
import com.kingscan.app.core.imaging.ScanLimits
import com.kingscan.app.core.imaging.RgbaImage
import com.kingscan.app.core.document.*

/** Shared save path for the editor, including retries after a partial batch. */
internal fun saveAnnotations(
    repository: DocumentRepository, codec: ImageCodec, document: ScanDocument,
    page: PageMeta, snapshot: List<PageMark>, allPages: Boolean,
    render: (RgbaImage, List<PageMark>) -> RgbaImage = PageMarkup::render, onSaved: () -> Unit = {},
) {
    val baselineIds = page.edit?.marks.orEmpty().map { it.id }.toSet()
    val addedWatermarks = snapshot.filter { it.type == MarkType.Text && it.id !in baselineIds }
    val targets = if (allPages && addedWatermarks.isNotEmpty()) document.pages.map { it.id } else listOf(page.id)
    targets.forEach { targetId ->
        val target = repository.load(document.id)?.pages?.firstOrNull { it.id == targetId } ?: error("页面不存在")
        val bytes = repository.readBytes(repository.annotationBasePath(document.id, target))
        val original = codec.decode(bytes, ScanLimits.MAX_EDGE)
        val addedIds = addedWatermarks.map { it.id }.toSet()
        val applied = if (target.id == page.id) snapshot
            else target.edit?.marks.orEmpty().filterNot { it.id in addedIds } + addedWatermarks
        val rendered = render(original, applied)
        repository.replacePage(document.id, target.id, EncodedPage(
            codec.encodeJpeg(rendered, ScanLimits.JPEG_QUALITY), rendered.width, rendered.height, target.filter,
            edit = (target.edit ?: PageEdit(filter = target.filter)).copy(marks = applied),
            annotationBaseBytes = bytes.takeIf { target.annotationBaseFileName == null },
        ), keepAnnotationBase = true) ?: error("页面不存在")
        onSaved()
    }
}
