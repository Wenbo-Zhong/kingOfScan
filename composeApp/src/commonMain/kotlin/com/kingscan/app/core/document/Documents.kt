package com.kingscan.app.core.document

import kotlinx.serialization.Serializable

@Serializable
data class PageMeta(
    val id: String,
    val fileName: String,
    val width: Int,
    val height: Int,
    val filter: FilterType,
    val originalFileName: String? = null,
    val edit: PageEdit? = null,
    val ocr: OcrResult? = null,
    val annotationBaseFileName: String? = null,
)

@Serializable
data class ScanDocument(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pages: List<PageMeta>,
    val folderId: String? = null,
    val tags: List<String> = emptyList(),
    val starred: Boolean = false,
    val notes: String = "",
    val deletedAt: Long? = null,
    val originalPdfs: List<OriginalPdf> = emptyList(),
    val sourcePages: List<PageMeta> = emptyList(),
    val composition: CompositionOptions? = null,
    val compositionSourceIds: List<String> = emptyList(),
)

@Serializable
data class OriginalPdf(val name: String, val fileName: String)

@Serializable
data class DocumentFolder(val id: String, val name: String, val locked: Boolean = false)

data class EncodedPage(
    val jpeg: ByteArray,
    val width: Int,
    val height: Int,
    val filter: FilterType,
    val originalBytes: ByteArray? = null,
    val edit: PageEdit? = null,
    val ocr: OcrResult? = null,
    val annotationBaseBytes: ByteArray? = null,
)

data class ImportFile(val path: String, val name: String, val isPdf: Boolean = false)
