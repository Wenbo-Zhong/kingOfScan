package com.kingscan.app.imaging

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OrtProvider
import java.io.FileInputStream
import java.nio.FloatBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import kotlinx.serialization.json.*

object AndroidDocumentModels : DocumentModelRuntime {
    lateinit var context: Context
    private val environment by lazy { OrtEnvironment.getEnvironment().also { it.setTelemetry(false) } }
    private var session: OrtSession? = null
    private var current: DocumentModel? = null
    private val verified = mutableSetOf<DocumentModel>()

    @Synchronized
    override fun predict(model: DocumentModel, input: FloatArray): FloatArray {
        require(input.size == model.inputs * model.edge * model.edge)
        check(::context.isInitialized) { "模型运行环境尚未初始化" }
        if (current != model) {
            session?.close(); session = null; current = null
            val manifest = Json.parseToJsonElement(context.assets.open("ai_models/models.json").bufferedReader().use { it.readText() }).jsonObject
            require(manifest.getValue("bundle_version").jsonPrimitive.content == DocumentModel.VERSION) { "模型资源版本不匹配" }
            val entry = manifest.getValue("models").jsonArray.first { it.jsonObject.getValue("id").jsonPrimitive.content == model.id }.jsonObject
            context.assets.openFd("ai_models/${model.id}.onnx").use { asset ->
                FileInputStream(asset.fileDescriptor).use { stream ->
                    // Read-only mmap avoids copying a large model into the Java heap or application storage.
                    val mapped = stream.channel.map(FileChannel.MapMode.READ_ONLY, asset.startOffset, asset.length)
                    if (model !in verified) {
                        val actual = MessageDigest.getInstance("SHA-256").apply { update(mapped.duplicate()) }.digest()
                            .joinToString("") { "%02x".format(it.toInt() and 255) }
                        require(actual == entry.getValue("onnx_sha256").jsonPrimitive.content) { "模型文件校验失败" }
                        verified += model
                    }
                    OrtSession.SessionOptions().use { options ->
                        // ORT 1.23.2 XNNPACK graph partitioning rejects this serialized RefineNet DAG.
                        // The CPU provider supports it; choose that provider before session creation.
                        if (model != DocumentModel.Fingers && OrtProvider.XNNPACK in OrtEnvironment.getAvailableProviders()) {
                            options.addXnnpack(mapOf("intra_op_num_threads" to "4"))
                            options.setIntraOpNumThreads(1)
                        } else options.setIntraOpNumThreads(2)
                        options.setInterOpNumThreads(1)
                        session = environment.createSession(mapped, options)
                    }
                }
            }
            current = model
        }
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(input),
            longArrayOf(1, model.inputs.toLong(), model.edge.toLong(), model.edge.toLong())).use { tensor ->
            session!!.run(mapOf("image" to tensor)).use { result ->
                val output = result[0] as? OnnxTensor ?: error("模型输出不是图像")
                val expected = model.outputs * model.edge * model.edge
                val buffer = output.floatBuffer
                require(buffer.remaining() == expected) { "模型输出尺寸不匹配" }
                return FloatArray(expected).also { buffer.get(it) }
            }
        }
    }
}

actual fun documentModelRuntime(): DocumentModelRuntime = AndroidDocumentModels
