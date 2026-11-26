package com.kingscan.app.core.imaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals

class BinaryMorphologyTest {
    @Test fun packedDilationMatchesSquareNeighborhoodIncludingWordAndImageBorders() {
        val random = Random(261126)
        for ((width, height) in listOf(1 to 1, 3 to 2, 8 to 13, 31 to 20,
            32 to 9, 33 to 13, 64 to 8, 65 to 17, 360 to 12)) {
            for (density in listOf(0, 10, 60, 100)) {
                val mask = BooleanArray(width * height) { random.nextInt(100) < density }
                for (radius in 1..3) for (inverted in listOf(false, true)) {
                    val expected = BooleanArray(mask.size) { i ->
                        val x = i % width
                        val y = i / width
                        ((y - radius).coerceAtLeast(0)..(y + radius).coerceAtMost(height - 1)).any { sy ->
                            ((x - radius).coerceAtLeast(0)..(x + radius).coerceAtMost(width - 1)).any { sx ->
                                mask[sy * width + sx] != inverted
                            }
                        }
                    }
                    assertContentEquals(expected,
                        BinaryMorphology.dilate(mask, width, height, radius, inverted),
                        "${width}x$height radius=$radius inverted=$inverted density=$density")
                }
            }
        }
    }
}
