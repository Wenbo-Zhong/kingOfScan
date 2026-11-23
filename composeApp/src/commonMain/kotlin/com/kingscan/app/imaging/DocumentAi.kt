package com.kingscan.app.imaging

import com.kingscan.app.model.RetouchEdit
import com.kingscan.app.model.RetouchKind
import kotlin.math.*

enum class DocumentModel(val id: String, val edge: Int, val inputs: Int, val outputs: Int) {
    Hd("docres", 256, 6, 3), Handwriting("handwriting", 512, 3, 1),
    Fingers("fingers", 512, 3, 1), Watermark("watermark", 256, 6, 3), Demoire("esdnet", 512, 3, 3);
    companion object { const val VERSION = "kingscan-document-ai-20261123-v1" }
}

interface DocumentModelRuntime {
    /** Contiguous float32 NCHW. Implementations serialize inference and keep at most one model resident. */
    fun predict(model: DocumentModel, input: FloatArray): FloatArray
}
expect fun documentModelRuntime(): DocumentModelRuntime

fun RetouchKind.isAi(): Boolean = this in listOf(RetouchKind.SmartHd, RetouchKind.Handwriting,
    RetouchKind.Fingers, RetouchKind.Watermark, RetouchKind.Demoire)

/**
 * Native trained models with shared preprocessing, masks, overlap blending and reproducible noise.
 * Image restoration retains native page resolution; segmentation only resizes its selection map.
 */
object DocumentAi {
    fun apply(source: RgbaImage, edit: RetouchEdit, progress: (Float) -> Unit, checkActive: () -> Unit): RgbaImage {
        require(edit.modelVersion == DocumentModel.VERSION) { "页面使用的模型版本不可用，请撤销这项修复后重新处理" }
        val model = when (edit.kind) {
            RetouchKind.SmartHd -> DocumentModel.Hd
            RetouchKind.Handwriting -> DocumentModel.Handwriting
            RetouchKind.Fingers -> DocumentModel.Fingers
            RetouchKind.Watermark -> DocumentModel.Watermark
            RetouchKind.Demoire -> DocumentModel.Demoire
            else -> error("不支持的模型操作")
        }
        checkActive()
        return if (model.outputs == 1) {
            val mask = selection(source, model, edit.strength, progress, checkActive)
            checkActive()
            require(mask.any { it }) { "未识别到可去除区域，可调整识别强度或涂抹选区" }
            // Printed ink bordering a selection must not bleed back into the reconstructed paper.
            val radius = maxOf(2, (8f * maxOf(source.width, source.height) / model.edge).roundToInt())
            val background = paperBackground(source, radius)
            val filled = PageRetouch.fill(background, mask, if (model == DocumentModel.Fingers) 0.65f else 0.4f)
            RgbaImage(source.width, source.height, IntArray(mask.size) { if (mask[it]) filled.pixels[it] else source.pixels[it] })
        } else restore(source, model, edit.seed, progress, checkActive)
    }

    private fun selection(source: RgbaImage, model: DocumentModel, strength: Float,
        progress: (Float) -> Unit, checkActive: () -> Unit): BooleanArray {
        val edge = model.edge
        val scale = edge.toFloat() / maxOf(source.width, source.height)
        val w = (source.width * scale).roundToInt().coerceIn(1, edge)
        val h = (source.height * scale).roundToInt().coerceIn(1, edge)
        val image = resize(source, w, h)
        val padded = RgbaImage(edge, edge, IntArray(edge * edge) { i ->
            if (i % edge < w && i / edge < h) image.pixel(i % edge, i / edge)
            else if (model == DocumentModel.Fingers) argb(255, 128, 128, 128) else argb(255, 1, 1, 1)
        })
        val confidence = FloatArray(edge * edge)
        val rotations = if (model == DocumentModel.Fingers) 4 else 1
        var rotated = padded
        repeat(rotations) { turn ->
            checkActive()
            val tensor = input(rotated, model, 0, 0, 0, checkActive)
            checkActive()
            val values = documentModelRuntime().predict(model, tensor)
            require(values.size == edge * edge && values.all { it.isFinite() }) { "模型输出无效" }
            for (y in 0 until edge) for (x in 0 until edge) {
                val index = when (turn) {
                    0 -> y * edge + x
                    1 -> x * edge + edge - 1 - y
                    2 -> (edge - 1 - y) * edge + edge - 1 - x
                    else -> (edge - 1 - x) * edge + y
                }
                val i = y * edge + x
                confidence[i] = max(confidence[i], values[index])
            }
            rotated = rotated.rotate90CW()
            progress((turn + 1f) / rotations)
        }
        // DIS publishes a normalized mask; retain that contract without binarizing the whole page.
        if (model == DocumentModel.Handwriting) {
            val low = confidence.min(); val range = confidence.max() - low
            if (range < 0.01f) return BooleanArray(source.pixels.size)
            for (i in confidence.indices) confidence[i] = (confidence[i] - low) / range
        }
        val threshold = if (model == DocumentModel.Fingers) 0.35f - strength.coerceIn(0f, 1f) * 0.3f
            else 0.55f - strength.coerceIn(0f, 1f) * 0.4f
        val mask = BooleanArray(source.pixels.size) { i ->
            sample(confidence, edge, edge, (i % source.width + 0.5f) * w / source.width - 0.5f,
                (i / source.width + 0.5f) * h / source.height - 0.5f) >= threshold
        }
        // Cover antialiased edges using a bounded dilation, never a colour or skin classifier.
        val radius = maxOf(1, ((if (model == DocumentModel.Fingers) 18f else 3f) *
            maxOf(source.width, source.height) / edge).roundToInt())
        return dilate(mask, source.width, source.height, radius)
    }

    private fun restore(source: RgbaImage, model: DocumentModel, seed: Int,
        progress: (Float) -> Unit, checkActive: () -> Unit): RgbaImage {
        val edge = model.edge; val stride = edge * 3 / 4
        fun positions(size: Int): List<Int> {
            if (size <= edge) return listOf(0)
            return (0..size - edge step stride).toList().let { if (it.last() == size - edge) it else it + (size - edge) }
        }
        val xs = positions(source.width); val ys = positions(source.height)
        // Rolling rows bound accumulation memory independently of page height.
        val rowSize = source.width * edge
        val sums = Array(3) { FloatArray(rowSize) }; val weights = FloatArray(rowSize)
        val output = IntArray(source.pixels.size)
        var completed = 0
        for ((row, top) in ys.withIndex()) {
            for (left in xs) {
                checkActive()
                val tile = RgbaImage(edge, edge, IntArray(edge * edge) { i ->
                    source.pixel(reflect(left + i % edge, source.width), reflect(top + i / edge, source.height))
                })
                val tensor = input(tile, model, seed, left, top, checkActive)
                checkActive()
                val values = documentModelRuntime().predict(model, tensor)
                require(values.size == edge * edge * 3 && values.all { it.isFinite() }) { "模型输出无效" }
                for (y in 0 until minOf(edge, source.height - top)) for (x in 0 until minOf(edge, source.width - left)) {
                    val overlap = edge - stride
                    val wx = minOf(1f, (x + 1f) / overlap, (edge - x.toFloat()) / overlap)
                    val wy = minOf(1f, (y + 1f) / overlap, (edge - y.toFloat()) / overlap)
                    val weight = wx * wy
                    val dest = ((top + y) % edge) * source.width + left + x
                    weights[dest] += weight
                    for (c in 0..2) {
                        val channel = if (model == DocumentModel.Hd) 2 - c else c
                        sums[c][dest] += values[channel * edge * edge + y * edge + x].coerceIn(0f, 1f) * weight
                    }
                }
                completed++
                progress(completed.toFloat() / (xs.size * ys.size))
            }
            val flushEnd = ys.getOrNull(row + 1) ?: source.height
            for (y in top until flushEnd) for (x in 0 until source.width) {
                val i = (y % edge) * source.width + x
                val weight = weights[i].coerceAtLeast(1e-8f)
                output[y * source.width + x] = argb(255, (sums[0][i] / weight * 255).roundToInt(),
                    (sums[1][i] / weight * 255).roundToInt(), (sums[2][i] / weight * 255).roundToInt())
                weights[i] = 0f
                for (c in 0..2) sums[c][i] = 0f
            }
        }
        checkActive()
        return RgbaImage(source.width, source.height, output)
    }

    /** Reference contracts: DocRes BGR+OpenCV Sobel, DIS BGR/255-.5, RefineNet RGB-128. */
    internal fun input(image: RgbaImage, model: DocumentModel, seed: Int, left: Int, top: Int,
        checkActive: () -> Unit = {}): FloatArray {
        val n = image.pixels.size
        val out = FloatArray(n * model.inputs)
        for (i in 0 until n) {
            if (i % image.width == 0) checkActive()
            val p = image.pixels[i]
            val bgr = model == DocumentModel.Hd || model == DocumentModel.Handwriting
            val channels = if (bgr) intArrayOf(p.blue(), p.green(), p.red()) else intArrayOf(p.red(), p.green(), p.blue())
            for (c in 0..2) out[c * n + i] = when (model) {
                DocumentModel.Fingers -> channels[c] - 128f
                DocumentModel.Handwriting -> channels[c] / 255f - 0.5f
                else -> channels[c] / 255f
            }
            if (model == DocumentModel.Hd) {
                val x = i % image.width; val y = i / image.width
                val gradient = IntArray(3) { c ->
                    fun channel(dx: Int, dy: Int): Int {
                        val color = image.pixel(reflect(x + dx, image.width), reflect(y + dy, image.height))
                        return when (c) { 0 -> color.blue(); 1 -> color.green(); else -> color.red() }
                    }
                    val gx = channel(1,-1) + 2*channel(1,0) + channel(1,1) - channel(-1,-1) - 2*channel(-1,0) - channel(-1,1)
                    val gy = channel(-1,1) + 2*channel(0,1) + channel(1,1) - channel(-1,-1) - 2*channel(0,-1) - channel(1,-1)
                    roundEven((minOf(255, abs(gx)) + minOf(255, abs(gy))) * 0.5f)
                }
                val gray = ((gradient[2] * 4899 + gradient[1] * 9617 + gradient[0] * 1868 + 8192) shr 14) / 255f
                for (c in 3..5) out[c * n + i] = gray
            } else if (model == DocumentModel.Watermark) {
                for (c in 0..2) out[(c + 3) * n + i] = noise(seed, left + i % image.width, top + i / image.width, c)
            }
        }
        return out
    }

    private fun roundEven(value: Float): Int {
        val floor = floor(value).toInt()
        return if (value - floor == 0.5f) floor + (floor and 1) else value.roundToInt()
    }
    internal fun noise(seed: Int, x: Int, y: Int, channel: Int): Float {
        fun hash(salt: Int): Double {
            var v = seed xor (x * 0x1f123bb5) xor (y * 0x5f356495) xor (channel * 0x6c8e9cf5) xor salt
            v = (v xor (v ushr 16)) * -2048144789
            v = (v xor (v ushr 13)) * -1028477387
            v = v xor (v ushr 16)
            return ((v.toLong() and 0xffffffffL) + 0.5) / 4294967296.0
        }
        return (sqrt(-2 * ln(hash(0))) * cos(2 * PI * hash(0x13579bdf))).toFloat()
    }
    private fun reflect(i: Int, size: Int): Int {
        if (size == 1) return 0
        val n = i.mod(2 * size - 2)
        return if (n < size) n else 2 * size - 2 - n
    }
    internal fun resize(image: RgbaImage, width: Int, height: Int): RgbaImage = RgbaImage(width, height,
        IntArray(width * height) { i ->
            val x = (i % width + 0.5f) * image.width / width - 0.5f
            val y = (i / width + 0.5f) * image.height / height - 0.5f
            val x0 = floor(x).toInt(); val y0 = floor(y).toInt()
            val dx = x - x0; val dy = y - y0
            fun channel(get: (Int) -> Int): Int = ((get(image.pixelClamped(x0,y0)) * (1-dx) +
                get(image.pixelClamped(x0+1,y0)) * dx) * (1-dy) +
                (get(image.pixelClamped(x0,y0+1)) * (1-dx) + get(image.pixelClamped(x0+1,y0+1)) * dx) * dy).roundToInt()
            argb(255,channel { it.red() },channel { it.green() },channel { it.blue() })
        })
    private fun sample(values: FloatArray, width: Int, height: Int, x: Float, y: Float): Float {
        val x0 = floor(x).toInt(); val y0 = floor(y).toInt(); val dx=x-x0; val dy=y-y0
        fun at(x: Int,y: Int)=values[y.coerceIn(0,height-1)*width+x.coerceIn(0,width-1)]
        return (at(x0,y0)*(1-dx)+at(x0+1,y0)*dx)*(1-dy)+(at(x0,y0+1)*(1-dx)+at(x0+1,y0+1)*dx)*dy
    }
    private fun dilate(mask: BooleanArray, width: Int, height: Int, radius: Int): BooleanArray {
        fun pass(input: BooleanArray, horizontal: Boolean): BooleanArray {
            val out = BooleanArray(mask.size)
            val outer = if (horizontal) height else width
            val inner = if (horizontal) width else height
            for (line in 0 until outer) {
                fun index(p: Int) = if (horizontal) line * width + p else p * width + line
                var count = 0
                for (p in 0..minOf(radius, inner - 1)) if (input[index(p)]) count++
                for (p in 0 until inner) {
                    out[index(p)] = count > 0
                    if (p - radius >= 0 && input[index(p - radius)]) count--
                    if (p + radius + 1 < inner && input[index(p + radius + 1)]) count++
                }
            }
            return out
        }
        return pass(pass(mask, true), false)
    }

    /** A local paper colour estimate used only underneath the trained model's selection. */
    private fun paperBackground(image: RgbaImage, radius: Int): RgbaImage {
        fun luminance(p: Int) = p.red() * 77 + p.green() * 150 + p.blue() * 29
        fun pass(input: IntArray, horizontal: Boolean): IntArray {
            val out = IntArray(input.size)
            val outer = if (horizontal) image.height else image.width
            val inner = if (horizontal) image.width else image.height
            val queue = IntArray(inner)
            for (line in 0 until outer) {
                fun index(p: Int) = if (horizontal) line * image.width + p else p * image.width + line
                var head = 0; var tail = 0; var next = 0
                for (p in 0 until inner) {
                    val end = minOf(inner - 1, p + radius)
                    while (next <= end) {
                        while (tail > head && luminance(input[index(queue[tail - 1])]) <= luminance(input[index(next)])) tail--
                        queue[tail++] = next++
                    }
                    while (tail > head && queue[head] < p - radius) head++
                    out[index(p)] = input[index(queue[head])]
                }
            }
            return out
        }
        return RgbaImage(image.width, image.height, pass(pass(image.pixels, true), false))
    }
}
