package com.kingscan.app.core.imaging

import kotlin.math.*
import kotlin.random.Random

/** Local corner matching + projective RANSAC. Insufficient overlap is reported instead of concatenated. */
object OverlapStitcher {
    private data class Feature(val x: Double, val y: Double, val score: Double, val patch: FloatArray)
    private data class Match(val from: Feature, val to: Feature)
    private class Matrix(val m: DoubleArray) {
        fun point(x: Double, y: Double): Pair<Double, Double> {
            val z = m[6] * x + m[7] * y + m[8]
            return (m[0] * x + m[1] * y + m[2]) / z to (m[3] * x + m[4] * y + m[5]) / z
        }
        operator fun times(other: Matrix) = Matrix(DoubleArray(9) { i ->
            (0..2).sumOf { k -> m[i / 3 * 3 + k] * other.m[k * 3 + i % 3] }
        })
        fun inverse(): Matrix {
            val a = m
            val out = doubleArrayOf(a[4]*a[8]-a[5]*a[7], a[2]*a[7]-a[1]*a[8], a[1]*a[5]-a[2]*a[4],
                a[5]*a[6]-a[3]*a[8], a[0]*a[8]-a[2]*a[6], a[2]*a[3]-a[0]*a[5],
                a[3]*a[7]-a[4]*a[6], a[1]*a[6]-a[0]*a[7], a[0]*a[4]-a[1]*a[3])
            val determinant = a[0]*out[0]+a[1]*out[3]+a[2]*out[6]
            require(abs(determinant) > 1e-10) { "图像变换无效，请增加重叠区域" }
            return Matrix(out.map { it / determinant }.toDoubleArray())
        }
    }
    private fun identity() = Matrix(doubleArrayOf(1.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,1.0))
    private fun scale(x: Double, y: Double) = Matrix(doubleArrayOf(x,0.0,0.0,0.0,y,0.0,0.0,0.0,1.0))

    fun stitch(images: List<RgbaImage>): RgbaImage {
        require(images.size in 2..8) { "重叠拼接需要 2–8 张图" }
        val small = images.map { it.scaledDown(720) }
        val features = small.map(::features)
        val transforms = mutableListOf(identity())
        for (index in 1 until images.size) {
            val matches = match(features[index], features[index - 1])
            require(matches.size >= 8) { "第 $index 与 ${index + 1} 张缺少可匹配内容，请保留至少约三分之一重叠" }
            val mapping = estimate(matches)
            val a = images[index - 1]; val b = images[index]
            val sa = small[index - 1]; val sb = small[index]
            val originalMapping = scale(a.width.toDouble()/sa.width, a.height.toDouble()/sa.height) * mapping *
                scale(sb.width.toDouble()/b.width, sb.height.toDouble()/b.height)
            transforms += transforms.last() * originalMapping
        }
        val corners = images.indices.flatMap { index ->
            val image = images[index]; val h = transforms[index]
            listOf(h.point(0.0,0.0), h.point(image.width.toDouble(),0.0),
                h.point(image.width.toDouble(),image.height.toDouble()), h.point(0.0,image.height.toDouble()))
        }
        require(corners.all { it.first.isFinite() && it.second.isFinite() }) { "图像变换超出范围" }
        val left = floor(corners.minOf { it.first }); val top = floor(corners.minOf { it.second })
        val width = ceil(corners.maxOf { it.first } - left).toInt()
        val height = ceil(corners.maxOf { it.second } - top).toInt()
        PageComposer.validateSize(width, height)
        val pixels = IntArray(width * height) { 0xFFFFFFFF.toInt() }
        val weights = FloatArray(width * height)
        images.forEachIndexed { index, image ->
            val h = transforms[index]
            val inverse = h.inverse()
            val bounds = listOf(h.point(0.0,0.0), h.point(image.width.toDouble(),0.0),
                h.point(image.width.toDouble(),image.height.toDouble()), h.point(0.0,image.height.toDouble()))
            val x0 = floor(bounds.minOf { it.first } - left).toInt().coerceIn(0, width - 1)
            val x1 = ceil(bounds.maxOf { it.first } - left).toInt().coerceIn(0, width - 1)
            val y0 = floor(bounds.minOf { it.second } - top).toInt().coerceIn(0, height - 1)
            val y1 = ceil(bounds.maxOf { it.second } - top).toInt().coerceIn(0, height - 1)
            for (y in y0..y1) for (x in x0..x1) {
                val (sx, sy) = inverse.point(x + left, y + top)
                if (sx < 0 || sy < 0 || sx > image.width - 1 || sy > image.height - 1) continue
                val position = y * width + x
                val weight = (minOf(sx, sy, image.width - 1 - sx, image.height - 1 - sy) / 40).toFloat().coerceIn(0.01f, 1f)
                val sample = sampleBilinear(image, sx.toFloat(), sy.toFloat())
                val previous = pixels[position]
                val alpha = weight / (weights[position] + weight)
                fun blend(a: Int, b: Int) = (a * (1 - alpha) + b * alpha).roundToInt().coerceIn(0, 255)
                pixels[position] = argb(255, blend(previous.red(), sample.red()),
                    blend(previous.green(), sample.green()), blend(previous.blue(), sample.blue()))
                weights[position] += weight
            }
        }
        return RgbaImage(width, height, pixels)
    }

    private fun features(image: RgbaImage): List<Feature> {
        if (image.width < 32 || image.height < 32) return emptyList()
        val gray = FloatArray(image.pixels.size) { i ->
            val p = image.pixels[i]; (p.red()*77+p.green()*150+p.blue()*29)/256f
        }
        fun g(x: Int, y: Int) = gray[y * image.width + x]
        val candidates = mutableListOf<Feature>()
        for (y in 10 until image.height - 10 step 2) for (x in 10 until image.width - 10 step 2) {
            var xx = 0.0; var yy = 0.0; var xy = 0.0
            for (dy in -2..2) for (dx in -2..2) {
                val gx = g(x+dx+1,y+dy)-g(x+dx-1,y+dy)
                val gy = g(x+dx,y+dy+1)-g(x+dx,y+dy-1)
                xx += gx*gx; yy += gy*gy; xy += gx*gy
            }
            val score = (xx+yy-sqrt((xx-yy)*(xx-yy)+4*xy*xy))/2
            if (score < 800) continue
            candidates += Feature(x.toDouble(), y.toDouble(), score, FloatArray(0))
        }
        val chosen = mutableListOf<Feature>()
        for (candidate in candidates.sortedByDescending { it.score }) {
            if (chosen.any { abs(it.x - candidate.x) < 9 && abs(it.y - candidate.y) < 9 }) continue
            val x = candidate.x.toInt(); val y = candidate.y.toInt()
            val patch = FloatArray(64) { i ->
                val px = x - 7 + i%8*2; val py = y - 7 + i/8*2
                (g(px,py)+g(px+1,py)+g(px,py+1)+g(px+1,py+1))/4
            }
            val mean = patch.average().toFloat()
            val norm = sqrt(patch.sumOf { ((it-mean)*(it-mean)).toDouble() }).toFloat()
            if (norm < 1) continue
            chosen += candidate.copy(patch = patch.map { (it-mean)/norm }.toFloatArray())
            if (chosen.size == 420) break
        }
        return chosen
    }

    private fun match(from: List<Feature>, to: List<Feature>): List<Match> {
        fun distance(a: FloatArray, b: FloatArray): Float {
            var sum = 0f
            for (i in a.indices) { val d = a[i]-b[i]; sum += d*d }
            return sum
        }
        val pairs = mutableListOf<Triple<Int,Int,Float>>()
        from.forEachIndexed { i, feature ->
            var best = Float.MAX_VALUE; var second = Float.MAX_VALUE; var index = -1
            to.forEachIndexed { j, other ->
                val d = distance(feature.patch, other.patch)
                if (d < best) { second = best; best = d; index = j } else if (d < second) second = d
            }
            if (index >= 0 && best < 0.6f && best < second*0.65f) pairs += Triple(i,index,best)
        }
        return pairs.groupBy { it.second }.values.map { candidates ->
            val selected = candidates.minBy { it.third }
            Match(from[selected.first], to[selected.second])
        }
    }

    private fun estimate(matches: List<Match>): Matrix {
        val random = Random(1701)
        var best = emptyList<Match>()
        repeat(350) {
            val indices = mutableSetOf<Int>()
            while (indices.size < 4) indices += random.nextInt(matches.size)
            val h = fit(indices.map { matches[it] }) ?: return@repeat
            val inliers = matches.filter { pair ->
                val p = h.point(pair.from.x,pair.from.y)
                hypot(p.first-pair.to.x,p.second-pair.to.y) < 3.0
            }
            if (inliers.size > best.size) best = inliers
        }
        require(best.size >= 8 && best.size >= matches.size*0.3) { "重叠匹配不稳定，请增加重叠并保持相机方向一致" }
        val h = fit(best) ?: error("重叠区域无法配准")
        val inverse = h.inverse()
        val error = best.map {
            val p = h.point(it.from.x,it.from.y); val q = inverse.point(it.to.x,it.to.y)
            hypot(p.first-it.to.x,p.second-it.to.y) + hypot(q.first-it.from.x,q.second-it.from.y)
        }.average()
        require(error < 5) { "配准误差过大，请重新拍摄" }
        return h
    }

    private fun fit(matches: List<Match>): Matrix? {
        val matrix = Array(8) { DoubleArray(9) }
        for (match in matches) {
            val x = match.from.x; val y = match.from.y; val u = match.to.x; val v = match.to.y
            val rows = listOf(doubleArrayOf(x,y,1.0,0.0,0.0,0.0,-u*x,-u*y,u),
                doubleArrayOf(0.0,0.0,0.0,x,y,1.0,-v*x,-v*y,v))
            for (row in rows) for (i in 0..7) for (j in 0..8) matrix[i][j] += row[i]*row[j]
        }
        for (column in 0..7) {
            val pivot = (column..7).maxBy { abs(matrix[it][column]) }
            if (abs(matrix[pivot][column]) < 1e-7) return null
            val tmp = matrix[column]; matrix[column] = matrix[pivot]; matrix[pivot] = tmp
            val divisor = matrix[column][column]
            for (j in column..8) matrix[column][j] /= divisor
            for (i in 0..7) if (i != column) {
                val factor = matrix[i][column]
                for (j in column..8) matrix[i][j] -= factor*matrix[column][j]
            }
        }
        return Matrix(DoubleArray(9) { if (it == 8) 1.0 else matrix[it][8] }).takeIf { it.m.all(Double::isFinite) }
    }
}
