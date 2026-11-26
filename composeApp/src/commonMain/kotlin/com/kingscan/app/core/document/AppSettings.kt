package com.kingscan.app.core.document

import kotlinx.serialization.Serializable

@Serializable
enum class ScanMode { Document, IdCard, Book, LargeFormat }

fun ScanMode.label(): String = when (this) {
    ScanMode.Document -> "文档"
    ScanMode.IdCard -> "证件"
    ScanMode.Book -> "书籍"
    ScanMode.LargeFormat -> "大幅面"
}

@Serializable
data class AppSettings(
    val defaultFilter: FilterType = FilterType.Enhance,
    val autoCapture: Boolean = false,
    val continuousCapture: Boolean = false,
    val scanMode: ScanMode = ScanMode.Document,
    val bookRightToLeft: Boolean = false,
    val autoSaveToGallery: Boolean = false,
    val deviceLock: Boolean = false,
    val export: ExportOptions = ExportOptions(),
)
