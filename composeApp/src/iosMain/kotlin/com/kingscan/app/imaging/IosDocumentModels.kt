@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
package com.kingscan.app.imaging

import kotlinx.cinterop.*
import platform.CoreML.*
import platform.Foundation.*
import platform.posix.memcpy
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlinx.serialization.json.*

private object IosDocumentModels : DocumentModelRuntime {
    private val lock = NSLock()
    private var current: DocumentModel? = null
    private var cached: MLModel? = null
    private var checkedManifest = false

    override fun predict(model: DocumentModel, input: FloatArray): FloatArray {
        lock.lock()
        try {
            return autoreleasepool {
                require(input.size == model.inputs * model.edge * model.edge)
                val bundle = NSBundle.mainBundle
                if (!checkedManifest) {
                    val path = bundle.pathForResource("models", "json", "AIModels")
                        ?: bundle.pathForResource("models", "json") ?: error("未找到模型清单")
                    val manifest = Json.parseToJsonElement(FileSystem.SYSTEM.read(path.toPath()) { readUtf8() }).jsonObject
                    require(manifest.getValue("bundle_version").jsonPrimitive.content == DocumentModel.VERSION) { "模型资源版本不匹配" }
                    checkedManifest = true
                }
                memScoped {
                    val error = alloc<ObjCObjectVar<NSError?>>()
                    if (current != model) {
                        cached = null; current = null
                        val url = bundle.URLForResource(model.id, "mlmodelc", "AIModels")
                            ?: bundle.URLForResource(model.id, "mlmodelc") ?: error("未找到 ${model.id} 模型")
                        val configuration = MLModelConfiguration().apply { computeUnits = MLComputeUnitsCPUAndGPU }
                        cached = MLModel.modelWithContentsOfURL(url, configuration, error.ptr)
                            ?: error("模型加载失败：${error.value?.localizedDescription}")
                        current = model
                    }
                    val shape = listOf(1, model.inputs, model.edge, model.edge).map { NSNumber(int = it) }
                    val array = MLMultiArray.create(shape, MLMultiArrayDataTypeFloat32, error.ptr)
                        ?: error("无法分配模型输入：${error.value?.localizedDescription}")
                    input.usePinned { memcpy(array.dataPointer, it.addressOf(0), (input.size * 4).convert()) }
                    val provider = MLDictionaryFeatureProvider(mapOf("image" to array), error.ptr)
                    val result = cached!!.predictionFromFeatures(provider, error.ptr)
                        ?: error("模型处理失败：${error.value?.localizedDescription}")
                    val output = result.featureValueForName("output")?.multiArrayValue ?: error("模型输出不是图像")
                    require(output.dataType == MLMultiArrayDataTypeFloat32) { "模型输出类型不匹配" }
                    val expected = listOf(1, model.outputs, model.edge, model.edge)
                    require(output.shape.map { (it as NSNumber).intValue } == expected) { "模型输出尺寸不匹配" }
                    val strides = output.strides.map { (it as NSNumber).intValue }
                    val values = output.dataPointer!!.reinterpret<FloatVar>()
                    FloatArray(model.outputs * model.edge * model.edge) { i ->
                        val n = model.edge * model.edge
                        values[(i / n) * strides[1] + (i % n / model.edge) * strides[2] + (i % model.edge) * strides[3]]
                    }
                }
            }
        } finally { lock.unlock() }
    }
}

actual fun documentModelRuntime(): DocumentModelRuntime = IosDocumentModels
