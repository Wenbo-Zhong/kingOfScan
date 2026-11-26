package com.kingscan.app.navigation

import com.kingscan.app.core.document.CompositionKind
import com.kingscan.app.core.document.ImportFile

sealed interface Screen {
    data object Library : Screen
    data object Capture : Screen
    data object Crop : Screen
    data object Filter : Screen
    data object Book : Screen
    data class Detail(val documentId: String) : Screen
    data class PdfUnlock(val file: ImportFile) : Screen
    data class Ocr(val documentId: String, val pageId: String) : Screen
    data object Settings : Screen
    data object Codes : Screen
    data class Export(val documentId: String) : Screen
    data class Annotate(val documentId: String, val pageId: String) : Screen
    data class Retouch(val documentId: String, val pageId: String) : Screen
    data class Pages(val documentId: String) : Screen
    data class ComposePages(val documentId: String, val pageIds: List<String> = emptyList(),
        val kind: CompositionKind = CompositionKind.Grid) : Screen
}
