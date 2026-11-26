package com.kingscan.app.core.imaging

import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DocumentAiCancellationTest {
    @Test fun cancellationInterruptsEveryModelsPreprocessingWithoutChangingActiveInput() {
        val image = RgbaImage(16, 16, IntArray(256) { i ->
            argb(255, i, (i * 7) % 256, (i * 13) % 256)
        })
        for (model in DocumentModel.entries) {
            val expected = DocumentAi.input(image, model, 20261123, 0, 0)
            val active = DocumentAi.input(image, model, 20261123, 0, 0) {}
            assertTrue(expected.contentEquals(active), model.id)
            var checks = 0
            val cancelled = assertFailsWith<CancellationException>(model.id) {
                DocumentAi.input(image, model, 20261123, 0, 0) {
                    if (++checks == 3) throw CancellationException("cancelled while preprocessing")
                }
            }
            assertEquals("cancelled while preprocessing", cancelled.message)
            assertTrue(checks < image.height, "Cancelled preprocessing must stop before completing ${model.id}")
        }
    }
}
