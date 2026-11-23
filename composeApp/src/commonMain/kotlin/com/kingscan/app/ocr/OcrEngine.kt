package com.kingscan.app.ocr

import com.kingscan.app.imaging.RgbaImage
import com.kingscan.app.model.OcrResult

enum class OcrLanguage(val label: String) {
    Chinese("中文与英文"),
    Latin("拉丁文字（英文等）"),
}

interface OcrEngine {
    suspend fun recognize(image: RgbaImage, language: OcrLanguage): OcrResult
}

expect fun createOcrEngine(): OcrEngine
