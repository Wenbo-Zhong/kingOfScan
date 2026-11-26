package com.kingscan.app

import com.kingscan.app.core.storage.DocumentRepository
import com.kingscan.app.core.imaging.*
import com.kingscan.app.core.document.*
import kotlin.math.abs
import okio.FileSystem
import okio.Buffer
import okio.Path.Companion.toPath
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.time.TimeSource

/** Real supplied images and reference tensors; excluded from ordinary application builds. */
suspend fun verifyDocumentAi(root: String): String {
    val fs = FileSystem.SYSTEM
    val context = currentCoroutineContext()
    fun floats(path: String): FloatArray = fs.read(path.toPath()) {
        val data = readByteArray()
        require(data.size % 4 == 0)
        val buffer = Buffer().write(data)
        FloatArray(data.size / 4) { Float.fromBits(buffer.readIntLe()) }
    }
    fun writeFloats(path: String, data: FloatArray) = fs.write(path.toPath()) { data.forEach { writeIntLe(it.toBits()) } }
    fun bytes(path: String) = fs.read(path.toPath()) { readByteArray() }
    fun writeImage(name: String, image: RgbaImage) = fs.write("$root/$name.jpg".toPath()) { write(AppGraph.codec.encodeJpeg(image, 98)) }
    val lines = mutableListOf<String>()
    for (model in DocumentModel.entries) {
        val input = floats("$root/${model.id}-input.f32")
        val expected = floats("$root/${model.id}-reference.f32")
        val start = TimeSource.Monotonic.markNow()
        val actual = documentModelRuntime().predict(model, input)
        val elapsed = start.elapsedNow().inWholeMilliseconds
        require(actual.size == expected.size && actual.all { it.isFinite() })
        val mae = actual.indices.sumOf { abs(actual[it] - expected[it]).toDouble() } / actual.size
        val max = actual.indices.maxOf { abs(actual[it] - expected[it]) }
        require(mae < 0.003) { "${model.id} numerical mismatch: $mae / $max" }
        writeFloats("$root/${model.id}-native.f32", actual)
        if (model != DocumentModel.Watermark) {
            val image = AppGraph.codec.decode(bytes("$root/${model.id}-input.png"), model.edge)
            val processedInput = DocumentAi.input(image, model, 20261123, 0, 0)
            val preError = input.indices.maxOf { abs(input[it] - processedInput[it]) }
            require(preError <= 1f / 255 + 1e-6f) { "${model.id} preprocessing mismatch: $preError" }
        }
        lines += "PASS ${model.id} native reference MAE=$mae max=$max firstLoadAndPredictionMs=$elapsed"
        fs.write("$root/progress.txt".toPath()) { writeUtf8(lines.joinToString("\n")) }
    }
    fun effect(kind: RetouchKind) = RetouchEdit(kind = kind)
    for ((kind, name) in listOf(RetouchKind.SmartHd to "docres", RetouchKind.Demoire to "esdnet",
        RetouchKind.Handwriting to "handwriting", RetouchKind.Fingers to "fingers", RetouchKind.Watermark to "watermark")) {
        val source = AppGraph.codec.decode(bytes("$root/$name-page.png"), 2400)
        val result = PageRetouch.apply(source, listOf(effect(kind)), checkActive = { context.ensureActive() })
        require(result.width == source.width && result.height == source.height)
        require(!result.pixels.contentEquals(source.pixels)) { "$name no pixels changed" }
        writeImage("$name-page-result", result)
        if (kind == RetouchKind.Fingers || kind == RetouchKind.Handwriting) {
            val unchanged = source.pixels.indices.count { source.pixels[it] == result.pixels[it] }
            require(unchanged > source.pixels.size * 0.35f) { "$name changed too much of the original" }
            lines += "PASS $name unselected pixels preserved=$unchanged/${source.pixels.size}"
        } else lines += "PASS $name full page dimensions preserved"
    }
    val sourceBytes = bytes("$root/handwriting-page.png")
    val source = AppGraph.codec.decode(sourceBytes, 2400)
    val edit = PageEdit(filter = FilterType.Original, retouches = listOf(effect(RetouchKind.Handwriting)))
    val restored = PageRetouch.apply(source, edit.retouches)
    val document = AppGraph.repository.create("AI模型验收", listOf(EncodedPage(AppGraph.codec.encodeJpeg(restored, 98),
        restored.width, restored.height, FilterType.Original, originalBytes = sourceBytes, edit = edit,
        ocr = OcrResult("must become stale"))))
    var page = document.pages.single()
    val originalPath = AppGraph.repository.originalPath(document.id, page)!!
    AppGraph.pageOperations.rotate(document.id, page)
    page = AppGraph.repository.load(document.id)!!.pages.single()
    AppGraph.pageOperations.filter(document.id, page, FilterType.Gray)
    page = DocumentRepository(originalPath.substringBefore("/documents/")).load(document.id)!!.pages.single()
    require(page.ocr == null)
    require(bytes(originalPath).contentEquals(sourceBytes))
    require(page.edit!!.retouches.single().modelVersion == DocumentModel.VERSION && page.edit!!.outputQuarterTurns == 1)
    val rerender = PageRetouch.apply(AppGraph.pageOperations.geometricSource(document.id, page), page.edit!!.retouches, 1)
    require(rerender.pixels.contentEquals(restored.rotate90CW().pixels)) { "rotation changed model-selected pixels" }
    lines += "PASS original bytes, model parameters, rotated rerender and OCR invalidation survive repository reload"
    // A clean page must not silently succeed with a fabricated selection.
    val blank = RgbaImage.filled(512, 512, -1)
    for (kind in listOf(RetouchKind.Handwriting, RetouchKind.Fingers)) {
        require(runCatching { PageRetouch.apply(blank, listOf(effect(kind))) }.isFailure) { "$kind selected clean paper" }
    }
    lines += "PASS clean paper rejected by both segmentation operations"
    val seedReference = floats("$root/noise-reference.f32")
    val noise = FloatArray(seedReference.size) { i -> DocumentAi.noise(20261123, i % 37, i / 37, i % 3) }
    require(noise.indices.maxOf { abs(noise[it] - seedReference[it]) } < 1e-5f)
    lines += "PASS coordinate-stable watermark noise matches independent reference"
    require(runCatching { PageRetouch.apply(source, listOf(effect(RetouchKind.Handwriting).copy(modelVersion = "missing"))) }.isFailure)
    var cancelled = false
    runCatching { PageRetouch.apply(source, edit.retouches, checkActive = { cancelled = true; error("cancelled") }) }
    require(cancelled && bytes(originalPath).contentEquals(sourceBytes))
    lines += "PASS unavailable model version and cancellation preserve saved original"
    return lines.joinToString("\n").also { fs.write("$root/result.txt".toPath()) { writeUtf8(it) } }
}
