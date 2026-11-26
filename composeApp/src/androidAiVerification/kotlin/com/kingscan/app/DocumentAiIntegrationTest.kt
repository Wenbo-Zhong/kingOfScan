package com.kingscan.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kingscan.app.core.imaging.AndroidDocumentModels
import com.kingscan.app.core.imaging.DocumentModel
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class DocumentAiIntegrationTest {
    @Test fun actualModelsAndOriginalPreservation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AppGraph.init(File(context.filesDir, "kingscan").absolutePath)
        AndroidDocumentModels.context = context.applicationContext
        verifyDocumentAi(File(context.filesDir, "ai-verification").absolutePath)
        Unit
    }

    @Test fun compressedModelCacheRecoversFromCorruptionAndEviction() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AndroidDocumentModels.context = context.applicationContext
        val fixtures = File(context.filesDir, "ai-verification")
        fun input(model: DocumentModel): FloatArray {
            val buffer = ByteBuffer.wrap(File(fixtures, "${model.id}-input.f32").readBytes())
                .order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            return FloatArray(buffer.remaining()).also { buffer.get(it) }
        }
        val model = DocumentModel.Demoire
        val tensor = input(model)
        val reference = AndroidDocumentModels.predict(model, tensor)
        val cache = File(context.cacheDir, "ai_models").listFiles()!!
            .single { it.name.startsWith("${model.id}-") && it.extension == "onnx" }
        val length = cache.length()
        AndroidDocumentModels.predict(DocumentModel.Hd, input(DocumentModel.Hd))
        // Same-length corruption must be detected by SHA, not merely by the filename or length.
        RandomAccessFile(cache, "rw").use { file ->
            val first = file.readByte()
            file.seek(0)
            file.writeByte(first.toInt() xor 1)
        }
        val recovered = AndroidDocumentModels.predict(model, tensor)
        check(cache.length() == length && recovered.contentEquals(reference))
        AndroidDocumentModels.predict(DocumentModel.Hd, input(DocumentModel.Hd))
        check(cache.delete())
        val recreated = AndroidDocumentModels.predict(model, tensor)
        check(cache.isFile && recreated.contentEquals(reference))
        check(File(context.cacheDir, "ai_models").listFiles()!!.none { it.extension == "tmp" })
    }
}
