package com.kingscan.app.imaging

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OrtProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.FloatBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlinx.serialization.json.*

object AndroidDocumentModels : DocumentModelRuntime {
    lateinit var context: Context
    private val environment by lazy { OrtEnvironment.getEnvironment().also { it.setTelemetry(false) } }
    private var session: OrtSession? = null
    private var current: DocumentModel? = null

    @Synchronized
    override fun predict(model: DocumentModel, input: FloatArray): FloatArray {
        require(input.size == model.inputs * model.edge * model.edge)
        check(::context.isInitialized) { "模型运行环境尚未初始化" }
        if (current != model) {
            session?.close(); session = null; current = null
            val manifest = Json.parseToJsonElement(context.assets.open("ai_models/models.json").bufferedReader().use { it.readText() }).jsonObject
            require(manifest.getValue("bundle_version").jsonPrimitive.content == DocumentModel.VERSION) { "模型资源版本不匹配" }
            val entry = manifest.getValue("models").jsonArray.first { it.jsonObject.getValue("id").jsonPrimitive.content == model.id }.jsonObject
            val file = modelFile(model, entry)
            FileInputStream(file).use { stream ->
                // Compressed weights are streamed to the disposable cache, then mapped read-only.
                val mapped = stream.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
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

    private fun modelFile(model: DocumentModel, entry: JsonObject): File {
        val sha = entry.getValue("onnx_sha256").jsonPrimitive.content
        require(sha.matches(Regex("[0-9a-f]{64}"))) { "模型校验信息无效" }
        val bytes = entry.getValue("onnx_bytes").jsonPrimitive.long
        require(bytes > 0) { "模型长度无效" }
        val asset = entry.getValue("android_asset").jsonPrimitive.content
        require(entry.getValue("android_asset_compression").jsonPrimitive.content == "gzip") { "模型压缩格式不匹配" }
        val directory = File(context.cacheDir, "ai_models").apply {
            check(isDirectory || mkdirs()) { "无法创建模型缓存" }
        }
        val file = File(directory, "${model.id}-$sha.onnx")
        if (file.isFile && file.length() == bytes) {
            if (FileInputStream(file).use { stream ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
                digest.digest().hex() == sha
            }) {
                return file
            }
        }
        val temporary = File.createTempFile("${model.id}-", ".tmp", directory)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            GZIPInputStream(context.assets.open("ai_models/$asset")).use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= bytes) { "模型解压长度不匹配" }
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            require(total == bytes && digest.digest().hex() == sha) { "模型文件校验失败" }
            check(temporary.renameTo(file)) { "无法保存模型缓存" }
            // Remove only obsolete versions of this model, retaining other models for reuse.
            directory.listFiles()?.filter {
                it != file && it.name.startsWith("${model.id}-") && it.extension == "onnx"
            }?.forEach { it.delete() }
            return file
        } finally {
            temporary.delete()
        }
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }
}

actual fun documentModelRuntime(): DocumentModelRuntime = AndroidDocumentModels
