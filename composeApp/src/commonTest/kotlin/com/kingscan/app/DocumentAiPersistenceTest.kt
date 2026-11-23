package com.kingscan.app

import com.kingscan.app.data.DocumentRepository
import com.kingscan.app.model.*
import kotlinx.serialization.json.*
import kotlin.test.*
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

class DocumentAiPersistenceTest {
    @Test fun savedModelIdentityDoesNotDependOnFutureApplicationDefaults() {
        val fs = FakeFileSystem().apply { emulateUnix() }
        val repository = DocumentRepository("/docs", fs)
        val effects = listOf(RetouchKind.SmartHd, RetouchKind.Handwriting, RetouchKind.Fingers,
            RetouchKind.Watermark, RetouchKind.Demoire).map { RetouchEdit(kind = it) }
        val original = byteArrayOf(7, 8, 9)
        val document = repository.create("version contract", listOf(EncodedPage(
            byteArrayOf(1), 20, 30, FilterType.Original, originalBytes = original,
            edit = PageEdit(filter = FilterType.Original, retouches = effects))))
        val raw = fs.read("/docs/documents/${document.id}/meta.json".toPath()) { readUtf8() }
        val saved = Json.parseToJsonElement(raw).jsonObject["pages"]!!.jsonArray.single()
            .jsonObject["edit"]!!.jsonObject["retouches"]!!.jsonArray
        for (effect in saved) {
            // A same-version decode alone would miss omitted defaults. Require their on-disk identity.
            val fields = effect.jsonObject
            assertEquals("kingscan-document-ai-20261123-v1", fields["modelVersion"]?.jsonPrimitive?.content)
            assertEquals(20261123, fields["seed"]?.jsonPrimitive?.int)
            assertEquals(0, fields["orientation"]?.jsonPrimitive?.int)
            assertEquals(0.5f, fields["strength"]?.jsonPrimitive?.float)
        }
        val reopened = DocumentRepository("/docs", fs).load(document.id)!!.pages.single()
        assertEquals(effects, reopened.edit!!.retouches)
        assertContentEquals(original, repository.readBytes(repository.originalPath(document.id, reopened)!!))
    }
}
