package com.kingscan.app

import com.kingscan.app.data.DocumentRepository
import com.kingscan.app.imaging.ImageCodec
import com.kingscan.app.imaging.RgbaImage
import com.kingscan.app.imaging.createImageCodec
import com.kingscan.app.model.Quad

object AppGraph {
    lateinit var repository: DocumentRepository
        private set
    lateinit var codec: ImageCodec
        private set
    val session = ScanSession()

    fun init(storageRoot: String) {
        if (::repository.isInitialized) return
        repository = DocumentRepository(storageRoot)
        codec = createImageCodec()
    }
}

class ScanSession {
    var documentId: String? = null
    var source: RgbaImage? = null
    var quad: Quad = Quad.inset(0.06f)
    var warped: RgbaImage? = null

    fun beginNew() {
        documentId = null
        clearImage()
    }

    fun beginAppend(documentId: String) {
        this.documentId = documentId
        clearImage()
    }

    fun clearImage() {
        source = null
        warped = null
        quad = Quad.inset(0.06f)
    }

    fun cancel() {
        beginNew()
    }
}
