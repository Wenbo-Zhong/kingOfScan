package com.kingscan.app.core.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.kingscan.app.core.imaging.RgbaImage
import com.kingscan.app.core.document.OcrLine
import com.kingscan.app.core.document.OcrResult
import com.kingscan.app.core.document.PointF
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

actual fun createOcrEngine(): OcrEngine = object : OcrEngine {
    override suspend fun recognize(image: RgbaImage, language: OcrLanguage): OcrResult =
        suspendCancellableCoroutine { continuation ->
            val recognizer = when (language) {
                OcrLanguage.Chinese -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                OcrLanguage.Latin -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            }
            val bitmap = Bitmap.createBitmap(image.pixels, image.width, image.height, Bitmap.Config.ARGB_8888)
            val task = recognizer.process(InputImage.fromBitmap(bitmap, 0))
            task.addOnSuccessListener { text ->
                if (continuation.isActive) {
                    val lines = text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                        val box = line.boundingBox ?: return@mapNotNull null
                        OcrLine(
                            line.text,
                            PointF(box.left / image.width.toFloat(), box.top / image.height.toFloat()).clamped(),
                            PointF(box.right / image.width.toFloat(), box.bottom / image.height.toFloat()).clamped(),
                        )
                    }
                    continuation.resume(OcrResult(text.text, lines,
                        if (language == OcrLanguage.Chinese) listOf("zh", "en") else listOf("Latn")))
                }
            }.addOnFailureListener {
                if (continuation.isActive) continuation.resumeWithException(it)
            }.addOnCompleteListener {
                // An ML Kit task cannot be cancelled; release input only after processing ends.
                bitmap.recycle()
                recognizer.close()
            }
        }
}
