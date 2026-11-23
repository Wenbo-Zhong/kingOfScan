package com.kingscan.app

import com.kingscan.app.data.DocumentRepository
import com.kingscan.app.imaging.*
import com.kingscan.app.model.*
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.*

class PageCompositionTest {
    @Test fun idPrintMaintainsPhysicalSizeAndBothSides() {
        val front = RgbaImage.filled(856, 540, 0xFFCC2222.toInt())
        val back = RgbaImage.filled(856, 540, 0xFF2244CC.toInt())
        val image = PageComposer.compose(listOf(front, back), CompositionOptions(kind = CompositionKind.IdCard, outputWidth = 2100))
        assertEquals(2970, image.height)
        val red = (0 until image.width).filter { image.pixel(it, 750).red() == 204 }
        val blue = (0 until image.width).filter { image.pixel(it, 1750).blue() == 204 }
        assertEquals(856, red.size)
        assertEquals(856, blue.size)
        assertEquals(0xFFFFFFFF.toInt(), image.pixel(20, 20))
    }

    @Test fun registeredOverlapReconstructsSharedTextureAndRejectsBlankImages() {
        val random = Random(722)
        val cells = IntArray(100 * 60) { random.nextInt(30, 250) }
        val world = RgbaImage(600, 360, IntArray(600 * 360) { i ->
            val value = cells[(i / 600 / 6) * 100 + i % 600 / 6]
            argb(255, value, value, value)
        })
        fun crop(left: Int) = RgbaImage(400, 360, IntArray(400 * 360) { i -> world.pixel(i % 400 + left, i / 400) })
        val stitched = OverlapStitcher.stitch(listOf(crop(0), crop(200)))
        assertTrue(stitched.width in 598..604 && stitched.height in 358..364, "${stitched.width} × ${stitched.height}")
        val error = (0..3).flatMap { dx -> (0..3).map { dy ->
            var difference = 0L; var count = 0
            for (y in 30..320 step 5) for (x in 30..560 step 5) {
                difference += abs(world.pixel(x, y).red() - stitched.pixelClamped(x + dx, y + dy).red())
                count++
            }
            difference.toFloat() / count
        } }.min()
        assertTrue(error < 4f, "Stitch reconstruction MAE=$error")
        assertFailsWith<IllegalArgumentException> {
            OverlapStitcher.stitch(listOf(RgbaImage.filled(300, 300, -1), RgbaImage.filled(300, 300, -1)))
        }
    }

    @Test fun composedCopiesRetainAllRealOriginalsAfterSourceDeletion() {
        val fs = FakeFileSystem()
        val repository = DocumentRepository("/scan", fs)
        val pages = listOf(EncodedPage(byteArrayOf(1), 10, 20, FilterType.Original, originalBytes = byteArrayOf(11)),
            EncodedPage(byteArrayOf(2), 10, 20, FilterType.Original, originalBytes = byteArrayOf(22)))
        val source = repository.create("两面", pages)
        val composed = repository.composePages(source.id, source.pages.map { it.id },
            EncodedPage(byteArrayOf(3), 20, 40, FilterType.Original), CompositionOptions(kind = CompositionKind.IdCard))
        assertNull(composed.pages.single().originalFileName)
        assertEquals(2, composed.compositionSourceIds.size)
        val copy = repository.duplicate(composed.id)
        assertEquals(CompositionKind.IdCard, copy.composition?.kind)
        repository.delete(source.id); repository.purge(source.id)
        repository.delete(composed.id); repository.purge(composed.id)
        val extracted = repository.extractCompositionSources(copy.id)
        assertEquals(2, extracted.pages.size)
        assertContentEquals(byteArrayOf(11), repository.readBytes(repository.originalPath(extracted.id, extracted.pages[0])!!))
        assertContentEquals(byteArrayOf(22), repository.readBytes(repository.originalPath(extracted.id, extracted.pages[1])!!))
        assertTrue(fs.exists(repository.pagePath(copy.id, copy.pages.single()).toPath()))
    }
}
