package com.kingscan.app.core.document

import kotlinx.serialization.Serializable

@Serializable
data class OcrLine(
    val text: String,
    val topLeft: PointF,
    val bottomRight: PointF,
)

@Serializable
data class OcrResult(
    val text: String,
    val lines: List<OcrLine> = emptyList(),
    val languages: List<String> = emptyList(),
)
