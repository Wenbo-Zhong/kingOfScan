package com.kingscan.app.core.document

import kotlinx.serialization.Serializable

@Serializable
enum class PdfPaper { FitImage, A4, Letter }

@Serializable
data class ExportOptions(
    val paper: PdfPaper = PdfPaper.FitImage,
    val landscape: Boolean = false,
    val marginPoints: Float = 18f,
    val jpegQuality: Int = 90,
    val pageNumbers: Boolean = false,
    val searchable: Boolean = true,
    val password: String = "",
)
