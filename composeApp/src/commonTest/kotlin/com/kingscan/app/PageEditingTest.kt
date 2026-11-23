package com.kingscan.app

import com.kingscan.app.imaging.EditHistory
import com.kingscan.app.imaging.PageMarkup
import com.kingscan.app.imaging.RgbaImage
import com.kingscan.app.model.*
import com.kingscan.app.pdf.PdfLayout
import kotlin.test.*

class PageEditingTest {
    @Test fun aNewEditDropsTheRedoBranch() {
        val history = EditHistory(listOf("original"))
        history.push(listOf("original", "signature"))
        history.push(listOf("original", "signature", "text"))
        history.undo()
        history.push(listOf("original", "signature", "redaction"))
        assertFalse(history.canRedo)
        assertEquals(listOf("original", "signature"), history.undo())
        assertEquals(listOf("original", "signature", "redaction"), history.redo())
    }
    @Test fun redactionIgnoresTransparencyAndReversedDragDirection() {
        val source = RgbaImage.filled(100, 100, 0xFFFFFFFF.toInt())
        val marked = PageMarkup.render(source, listOf(PageMark("hide", MarkType.Redaction,
            listOf(PointF(0.8f, 0.8f), PointF(0.2f, 0.2f)), color = 0xFFFFFFFF, opacity = 0.1f)))
        assertEquals(0xFF000000.toInt(), marked.pixel(50, 50))
        assertEquals(0xFFFFFFFF.toInt(), source.pixel(50, 50))
        assertEquals(0xFFFFFFFF.toInt(), marked.pixel(5, 5))
    }
    @Test fun pageNumbersKeepTheImageInsidePaperMargins() {
        val layout = PdfLayout.forPage(1000, 2000, ExportOptions(paper = PdfPaper.A4, marginPoints = 24f, pageNumbers = true))
        assertTrue(layout.imageX >= 24f)
        assertTrue(layout.imageY >= 24f)
        assertTrue(layout.imageY + layout.imageHeight <= layout.height - 24f - 18f + 0.01f)
        assertEquals(0.5f, layout.imageWidth / layout.imageHeight, 0.0001f)
    }
}
