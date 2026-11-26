@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.kingscan.app.core.ocr

import com.kingscan.app.AppGraph
import com.kingscan.app.core.imaging.RgbaImage
import com.kingscan.app.core.imaging.toNSData
import com.kingscan.app.core.document.OcrLine
import com.kingscan.app.core.document.OcrResult
import com.kingscan.app.core.document.PointF
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.useContents
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSError
import platform.Vision.VNImageRequestHandler
import platform.Vision.VNRecognizeTextRequest
import platform.Vision.VNRecognizedText
import platform.Vision.VNRecognizedTextObservation
import platform.Vision.VNRequestTextRecognitionLevelAccurate

actual fun createOcrEngine(): OcrEngine = object : OcrEngine {
    override suspend fun recognize(image: RgbaImage, language: OcrLanguage): OcrResult = withContext(Dispatchers.Default) {
        val request = VNRecognizeTextRequest()
        request.recognitionLevel = VNRequestTextRecognitionLevelAccurate
        request.usesLanguageCorrection = true
        val requested = if (language == OcrLanguage.Chinese) listOf("zh-Hans", "en-US") else listOf("en-US")
        val supported = request.supportedRecognitionLanguagesAndReturnError(null)?.filterIsInstance<String>().orEmpty()
        require(requested.all { it in supported }) { "当前 iOS 版本不支持所选离线识别语言" }
        request.recognitionLanguages = requested
        val handler = VNImageRequestHandler(data = AppGraph.codec.encodeJpeg(image, 96).toNSData(), options = emptyMap<Any?, Any>())
        memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            require(handler.performRequests(listOf(request), error.ptr)) {
                error.value?.localizedDescription ?: "文字识别失败"
            }
        }
        val lines = request.results?.filterIsInstance<VNRecognizedTextObservation>().orEmpty().mapNotNull { observation ->
            val candidate = observation.topCandidates(1u).firstOrNull() as? VNRecognizedText ?: return@mapNotNull null
            observation.boundingBox.useContents {
                OcrLine(candidate.string,
                    PointF(origin.x.toFloat(), (1.0 - origin.y - size.height).toFloat()).clamped(),
                    PointF((origin.x + size.width).toFloat(), (1.0 - origin.y).toFloat()).clamped())
            }
        }.sortedWith(compareBy<OcrLine> { it.topLeft.y }.thenBy { it.topLeft.x })
        OcrResult(lines.joinToString("\n") { it.text }, lines, requested)
    }
}
