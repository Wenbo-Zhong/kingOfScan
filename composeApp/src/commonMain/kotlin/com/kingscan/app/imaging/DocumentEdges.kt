package com.kingscan.app.imaging

import com.kingscan.app.model.PointF
import com.kingscan.app.model.Quad
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Small, platform-independent preview detector. Enclosed spaces between edges
 * provide candidates even when text and the desk belong to the same tonal region.
 * The four straight sides are intersected rather than using contour extrema:
 * rounded corners and card notches must not move the document's outer rectangle.
 */
internal class DocumentEdges(gray: IntArray, private val width: Int, private val height: Int) {
    private val size = gray.size
    private val gx = IntArray(size)
    private val gy = IntArray(size)
    private val magnitude = IntArray(size)
    private val peaks = IntArray(size)
    private val queue = IntArray(size)
    private val candidates = ArrayList<Candidate>()

    init {
        val smooth = IntArray(size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var sum = 0
                for (dy in -1..1) {
                    val row = (y + dy).coerceIn(0, height - 1) * width
                    val weightY = if (dy == 0) 2 else 1
                    for (dx in -1..1) {
                        sum += gray[row + (x + dx).coerceIn(0, width - 1)] *
                            weightY * if (dx == 0) 2 else 1
                    }
                }
                smooth[y * width + x] = (sum + 8) / 16
            }
        }
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val i = y * width + x
                gx[i] = smooth[i - width + 1] + 2 * smooth[i + 1] + smooth[i + width + 1] -
                    smooth[i - width - 1] - 2 * smooth[i - 1] - smooth[i + width - 1]
                gy[i] = smooth[i + width - 1] + 2 * smooth[i + width] + smooth[i + width + 1] -
                    smooth[i - width - 1] - 2 * smooth[i - width] - smooth[i - width + 1]
                magnitude[i] = abs(gx[i]) + abs(gy[i])
            }
        }
        // Quantized gradient direction and nonmaximum suppression.
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val i = y * width + x
                val ax = abs(gx[i])
                val ay = abs(gy[i])
                val step = when {
                    ay * 1000 < ax * 414 -> 1
                    ay * 414 > ax * 1000 -> width
                    gx[i] * gy[i] > 0 -> width + 1
                    else -> width - 1
                }
                if (magnitude[i] > magnitude[i - step] && magnitude[i] >= magnitude[i + step]) {
                    peaks[i] = magnitude[i]
                }
            }
        }
    }

    fun detect(preferred: Quad? = null): Quad? {
        if (width < 16 || height < 16) return null
        // Low thresholds keep gray-on-gray outlines; stronger passes disconnect
        // background texture that can otherwise join the document's boundary.
        for (low in intArrayOf(8, 12, 20, 30)) {
            val edges = canny(low, low * 3)
            inspectRegions(closeEdges(edges, 1))
            inspectRegions(closeEdges(edges, 2))
            // A one-pixel junction gap can survive closing where a strong ink
            // stroke meets a weak paper edge. Also inspect a gently thickened map.
            inspectRegions(dilate(edges, 1))
        }
        val hint = preferred?.let { quad ->
            listOf(quad.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft)
                .map { Vertex(it.x.toDouble() * width, it.y.toDouble() * height) }
        }
        // A previous outline must still have four real edges in this frame.
        // This also recovers outlines interrupted by a transient contour gap.
        if (hint != null && validCorners(hint)) {
            val fitted = refine(hint)
            val supports = fitted.indices.map { support(fitted[it], fitted[(it + 1) % 4]) }
            if (supports.min() >= 0.65) {
                addCandidate(fitted, supports.min() + supports.average() * 0.5 + area(fitted) / size * 0.4,
                    supports.min())
            }
        }
        val eligible = candidates.filter { inner ->
            candidates.none { outer -> outer !== inner && outer.minimum >= 0.65 && enclosesSection(outer, inner) }
        }
        val chosen = eligible.maxByOrNull { candidate ->
            val continuity = if (hint == null) 0.0 else {
                val distance = candidate.corners.indices.maxOf { sqrt(distanceSquared(candidate.corners[it], hint[it])) }
                0.12 * (1 - distance / (minOf(width, height) * 0.08)).coerceIn(0.0, 1.0)
            }
            candidate.score + continuity
        } ?: return null
        val corners = refine(chosen.corners)
        val first = corners.indices.minBy { corners[it].x / width + corners[it].y / height }
        fun point(offset: Int): PointF {
            val p = corners[(first + offset) % 4]
            return PointF((p.x / width).toFloat(), (p.y / height).toFloat())
        }
        return Quad(point(0), point(1), point(2), point(3)).clamped()
    }

    private fun canny(low: Int, high: Int): BooleanArray {
        val edges = BooleanArray(size)
        var head = 0
        var tail = 0
        for (i in peaks.indices) {
            if (peaks[i] >= high) {
                edges[i] = true
                queue[tail++] = i
            }
        }
        while (head < tail) {
            val i = queue[head++]
            val x = i % width
            val y = i / width
            for (dy in -1..1) {
                for (dx in -1..1) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx !in 1 until width - 1 || ny !in 1 until height - 1) continue
                    val n = ny * width + nx
                    if (!edges[n] && peaks[n] >= low) {
                        edges[n] = true
                        queue[tail++] = n
                    }
                }
            }
        }
        return edges
    }

    private fun closeEdges(edges: BooleanArray, radius: Int): BooleanArray {
        val dilated = dilate(edges, radius)
        val gaps = dilate(BooleanArray(size) { !dilated[it] }, radius)
        return BooleanArray(size) {
            val x = it % width
            val y = it / width
            x >= radius * 2 && x < width - radius * 2 &&
                y >= radius * 2 && y < height - radius * 2 && !gaps[it]
        }
    }

    private fun inspectRegions(edges: BooleanArray, merge: Boolean = true, sampleMask: BooleanArray? = null) {
        val seen = edges.copyOf()
        val enclosed = if (merge) BooleanArray(size) else null
        val rowLeft = IntArray(height)
        val rowRight = IntArray(height)
        for (start in 0 until size) {
            if (seen[start]) continue
            var head = 0
            var tail = 1
            queue[0] = start
            seen[start] = true
            var left = width
            var right = 0
            var top = height
            var bottom = 0
            while (head < tail) {
                val i = queue[head++]
                val x = i % width
                val y = i / width
                left = minOf(left, x)
                right = maxOf(right, x)
                top = minOf(top, y)
                bottom = maxOf(bottom, y)
                // Four-connected free space stays enclosed by diagonal edge pixels.
                if (x > 0 && !seen[i - 1]) { seen[i - 1] = true; queue[tail++] = i - 1 }
                if (x + 1 < width && !seen[i + 1]) { seen[i + 1] = true; queue[tail++] = i + 1 }
                if (y > 0 && !seen[i - width]) { seen[i - width] = true; queue[tail++] = i - width }
                if (y + 1 < height && !seen[i + width]) { seen[i + width] = true; queue[tail++] = i + width }
            }
            val boxWidth = right - left + 1
            val boxHeight = bottom - top + 1
            if (left <= MARGIN || top <= MARGIN || right >= width - MARGIN - 1 ||
                bottom >= height - MARGIN - 1
            ) continue
            if (enclosed != null) {
                for (j in 0 until tail) enclosed[queue[j]] = true
            }
            if (tail < 100 || boxWidth * boxHeight < size * MIN_AREA) continue

            // Row extrema retain the exact convex hull without sorting all pixels.
            rowLeft.fill(width, top, bottom + 1)
            rowRight.fill(-1, top, bottom + 1)
            for (j in 0 until tail) {
                val i = queue[j]
                if (sampleMask != null && !sampleMask[i]) continue
                val y = i / width
                val x = i % width
                rowLeft[y] = minOf(rowLeft[y], x)
                rowRight[y] = maxOf(rowRight[y], x)
            }
            val boundary = ArrayList<Vertex>((bottom - top + 1) * 2)
            for (y in top..bottom) {
                if (rowRight[y] < 0) continue
                boundary.add(Vertex(rowLeft[y].toDouble(), y.toDouble()))
                if (rowRight[y] != rowLeft[y]) boundary.add(Vertex(rowRight[y].toDouble(), y.toDouble()))
            }
            val hull = convexHull(boundary)
            val hullArea = area(hull)
            if (hullArea / size !in MIN_AREA..MAX_AREA) continue
            inspectSides(simplify(hull, minOf(boxWidth, boxHeight) * 0.01), hullArea, minOf(boxWidth, boxHeight))
        }
        if (enclosed != null) {
            // Text or a crease can divide a page's interior into several enclosed
            // spaces. Join those across narrow edge strokes before fitting the
            // full outline; never include the open desk region in this union.
            val expanded = dilate(enclosed, 3)
            inspectRegions(BooleanArray(size) { !expanded[it] }, merge = false, sampleMask = enclosed)
        }
    }

    private fun dilate(mask: BooleanArray, radius: Int): BooleanArray {
        val horizontal = BooleanArray(size)
        val out = BooleanArray(size)
        for (y in 0 until height) {
            val row = y * width
            var count = 0
            for (x in 0..minOf(radius, width - 1)) if (mask[row + x]) count++
            for (x in 0 until width) {
                horizontal[row + x] = count > 0
                if (x - radius >= 0 && mask[row + x - radius]) count--
                if (x + radius + 1 < width && mask[row + x + radius + 1]) count++
            }
        }
        for (x in 0 until width) {
            var count = 0
            for (y in 0..minOf(radius, height - 1)) if (horizontal[y * width + x]) count++
            for (y in 0 until height) {
                out[y * width + x] = count > 0
                if (y - radius >= 0 && horizontal[(y - radius) * width + x]) count--
                if (y + radius + 1 < height && horizontal[(y + radius + 1) * width + x]) count++
            }
        }
        return out
    }

    private fun inspectSides(polygon: List<Vertex>, hullArea: Double, shortSide: Int) {
        val sides = polygon.indices.mapNotNull { index ->
            val a = polygon[index]
            val b = polygon[(index + 1) % polygon.size]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val length = sqrt(dx * dx + dy * dy)
            if (length < shortSide * 0.15) null else {
                val nx = -dy / length
                val ny = dx / length
                Side(index, length, nx, ny, nx * a.x + ny * a.y)
            }
        }.sortedByDescending { it.length }.take(8).sortedBy { it.index }
        for (a in 0 until sides.size - 3) {
            for (b in a + 1 until sides.size - 2) {
                for (c in b + 1 until sides.size - 1) {
                    for (d in c + 1 until sides.size) {
                        val selected = listOf(sides[a], sides[b], sides[c], sides[d])
                        val corners = ArrayList<Vertex>(4)
                        for (i in 0..3) {
                            val p = intersection(selected[(i + 3) % 4], selected[i]) ?: break
                            corners.add(p)
                        }
                        if (corners.size != 4 || !validCorners(corners)) continue
                        val quadArea = area(corners)
                        if (quadArea / size !in MIN_AREA..MAX_AREA ||
                            quadArea < hullArea * 0.85 || quadArea > hullArea * 1.2
                        ) continue
                        var minimum = 1.0
                        var sum = 0.0
                        for (i in 0..3) {
                            val support = support(corners[i], corners[(i + 1) % 4])
                            minimum = minOf(minimum, support)
                            sum += support
                        }
                        if (minimum < 0.55) continue
                        val score = minimum + sum / 8 + quadArea / size * 0.4
                        addCandidate(corners, score, minimum)
                    }
                }
            }
        }
    }

    private fun validCorners(corners: List<Vertex>): Boolean {
        for (i in 0..3) {
            val p = corners[i]
            if (p.x < MARGIN || p.x > width - MARGIN || p.y < MARGIN || p.y > height - MARGIN) return false
            if (cross(p, corners[(i + 1) % 4], corners[(i + 2) % 4]) <= 0) return false
        }
        return true
    }

    private fun ordered(corners: List<Vertex>): List<Vertex> {
        val first = corners.indices.minBy { corners[it].x / width + corners[it].y / height }
        return List(4) { corners[(first + it) % 4] }
    }

    private fun addCandidate(corners: List<Vertex>, score: Double, minimum: Double) {
        val points = ordered(corners)
        val duplicate = candidates.indexOfFirst { old ->
            old.corners.indices.all { distanceSquared(old.corners[it], points[it]) < 16 }
        }
        if (duplicate >= 0) {
            if (score > candidates[duplicate].score) candidates[duplicate] = Candidate(points, score, minimum)
        } else {
            candidates.add(Candidate(points, score, minimum))
            if (candidates.size > 48) {
                val largest = candidates.sortedByDescending { area(it.corners) }.take(12).toSet()
                candidates.filter { it !in largest }.minByOrNull { it.score }?.let(candidates::remove)
            }
        }
    }

    /** Printed rules may close a page section with stronger edges than the paper. */
    private fun enclosesSection(outer: Candidate, inner: Candidate): Boolean {
        if (area(outer.corners) < area(inner.corners) * 1.35) return false
        var strict = true
        val distances = ArrayList<List<Double>>(4)
        for (point in inner.corners) {
            val sides = outer.corners.indices.map { i ->
                val a = outer.corners[i]
                val b = outer.corners[(i + 1) % 4]
                cross(a, b, point) / sqrt(distanceSquared(a, b))
            }
            if (sides.any { it < -4 }) return false
            if (sides.any { it < 4 }) strict = false
            distances.add(sides)
        }
        if (strict) return true
        // Prefer the whole page when the section shares a straight outer side.
        // An unrelated background quad touching one corner does not qualify.
        return (0..3).any { side ->
            (0..3).any { i ->
                abs(distances[i][side]) < 5 && abs(distances[(i + 1) % 4][side]) < 5
            }
        }
    }

    private fun support(a: Vertex, b: Vertex): Double {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val length = sqrt(dx * dx + dy * dy)
        if (length < 1) return 0.0
        val nx = -dy / length
        val ny = dx / length
        var matched = 0
        for (sample in 0 until SAMPLES) {
            val t = 0.12 + 0.76 * sample / (SAMPLES - 1)
            var strongest = 0.0
            for (offset in -2..2) {
                val x = (a.x + dx * t + nx * offset).roundToInt()
                val y = (a.y + dy * t + ny * offset).roundToInt()
                if (x !in 1 until width - 1 || y !in 1 until height - 1) continue
                val i = y * width + x
                val normalGradient = abs(gx[i] * nx + gy[i] * ny)
                val gradientSquared = gx[i].toDouble() * gx[i] + gy[i].toDouble() * gy[i]
                if (normalGradient * normalGradient >= 0.49 * gradientSquared) {
                    strongest = maxOf(strongest, normalGradient)
                }
            }
            if (strongest >= 10) matched++
        }
        return matched.toDouble() / SAMPLES
    }

    /** Fit the actual boundary near the hull, rejecting text and corner outliers. */
    private fun refine(corners: List<Vertex>): List<Vertex> {
        val lines = corners.indices.map { i -> refineSide(corners[i], corners[(i + 1) % 4]) }
        val fitted = ArrayList<Vertex>(4)
        for (i in 0..3) fitted.add(intersection(lines[(i + 3) % 4], lines[i]) ?: return corners)
        if (!validCorners(fitted) || abs(area(fitted) / area(corners) - 1) > 0.1) return corners
        for (i in 0..3) {
            if (distanceSquared(fitted[i], corners[i]) > 144 ||
                support(fitted[i], fitted[(i + 1) % 4]) < 0.55
            ) return corners
        }
        return fitted
    }

    private fun refineSide(a: Vertex, b: Vertex): Side {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val length = sqrt(dx * dx + dy * dy)
        val nx = -dy / length
        val ny = dx / length
        val original = Side(0, length, nx, ny, nx * a.x + ny * a.y)
        val points = ArrayList<Vertex>()
        for (sample in 0 until SAMPLES) {
            val t = 0.12 + 0.76 * sample / (SAMPLES - 1)
            var bestPoint: Vertex? = null
            var strength = 10.0
            for (offset in -6..6) {
                val x = (a.x + dx * t + nx * offset).roundToInt()
                val y = (a.y + dy * t + ny * offset).roundToInt()
                if (x !in 1 until width - 1 || y !in 1 until height - 1) continue
                val i = y * width + x
                val normalGradient = abs(gx[i] * nx + gy[i] * ny)
                val gradientSquared = gx[i].toDouble() * gx[i] + gy[i].toDouble() * gy[i]
                if (normalGradient * normalGradient < 0.64 * gradientSquared) continue
                val weighted = normalGradient / (1 + 0.03 * offset * offset)
                if (weighted > strength) {
                    strength = weighted
                    bestPoint = Vertex(x.toDouble(), y.toDouble())
                }
            }
            bestPoint?.let(points::add)
        }
        // Deterministic consensus fit: short text strokes must not tilt a side.
        var inliers = emptyList<Vertex>()
        var residual = Double.MAX_VALUE
        for (i in points.indices step 3) {
            for (j in i + 1 until points.size step 3) {
                val p = points[i]
                val q = points[j]
                val separation = sqrt(distanceSquared(p, q))
                if (separation < length * 0.35) continue
                val accepted = ArrayList<Vertex>()
                var error = 0.0
                for (point in points) {
                    val distance = abs(cross(p, q, point)) / separation
                    if (distance <= 1.25) { accepted.add(point); error += distance }
                }
                if (accepted.size > inliers.size || (accepted.size == inliers.size && error < residual)) {
                    inliers = accepted
                    residual = error
                }
            }
        }
        if (inliers.size < SAMPLES * 0.55) return original
        val cx = inliers.sumOf { it.x } / inliers.size
        val cy = inliers.sumOf { it.y } / inliers.size
        var xx = 0.0
        var yy = 0.0
        var xy = 0.0
        for (point in inliers) {
            xx += (point.x - cx) * (point.x - cx)
            yy += (point.y - cy) * (point.y - cy)
            xy += (point.x - cx) * (point.y - cy)
        }
        val angle = atan2(2 * xy, xx - yy) * 0.5
        val fittedX = -sin(angle)
        val fittedY = cos(angle)
        if (abs(fittedX * nx + fittedY * ny) < 0.98) return original
        return Side(0, length, fittedX, fittedY, fittedX * cx + fittedY * cy)
    }

    private fun convexHull(points: List<Vertex>): List<Vertex> {
        val sorted = points.sortedWith(compareBy<Vertex> { it.x }.thenBy { it.y })
        val hull = ArrayList<Vertex>(sorted.size * 2)
        for (p in sorted) {
            while (hull.size >= 2 && cross(hull[hull.lastIndex - 1], hull.last(), p) <= 0) hull.removeAt(hull.lastIndex)
            hull.add(p)
        }
        val lower = hull.size
        for (i in sorted.size - 2 downTo 0) {
            val p = sorted[i]
            while (hull.size > lower && cross(hull[hull.lastIndex - 1], hull.last(), p) <= 0) hull.removeAt(hull.lastIndex)
            hull.add(p)
        }
        if (hull.size > 1) hull.removeAt(hull.lastIndex)
        return hull
    }

    private fun simplify(hull: List<Vertex>, epsilon: Double): List<Vertex> {
        if (hull.size <= 4) return hull
        val first = hull.first()
        val split = hull.indices.maxBy { distanceSquared(first, hull[it]) }
        val out = ArrayList<Vertex>()
        fun appendChain(chain: List<Vertex>) {
            fun append(start: Int, end: Int) {
                val a = chain[start]
                val b = chain[end]
                val lengthSquared = distanceSquared(a, b)
                var furthest = -1
                var maximum = epsilon * epsilon * lengthSquared
                for (i in start + 1 until end) {
                    val distance = cross(a, b, chain[i])
                    if (distance * distance > maximum) {
                        maximum = distance * distance
                        furthest = i
                    }
                }
                if (furthest < 0) out.add(a) else {
                    append(start, furthest)
                    append(furthest, end)
                }
            }
            append(0, chain.lastIndex)
        }
        appendChain(hull.subList(0, split + 1))
        appendChain(hull.subList(split, hull.size) + first)
        return out
    }

    private fun intersection(a: Side, b: Side): Vertex? {
        val determinant = a.nx * b.ny - a.ny * b.nx
        if (abs(determinant) < 0.15) return null
        return Vertex((a.offset * b.ny - a.ny * b.offset) / determinant,
            (a.nx * b.offset - a.offset * b.nx) / determinant)
    }

    private fun area(points: List<Vertex>): Double {
        var sum = 0.0
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            sum += a.x * b.y - a.y * b.x
        }
        return abs(sum) * 0.5
    }

    private fun cross(a: Vertex, b: Vertex, c: Vertex): Double =
        (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)

    private fun distanceSquared(a: Vertex, b: Vertex): Double =
        (b.x - a.x) * (b.x - a.x) + (b.y - a.y) * (b.y - a.y)

    private data class Vertex(val x: Double, val y: Double)
    private data class Candidate(val corners: List<Vertex>, val score: Double, val minimum: Double)
    private data class Side(val index: Int, val length: Double, val nx: Double, val ny: Double, val offset: Double)

    private companion object {
        const val MIN_AREA = 0.06
        const val MAX_AREA = 0.85
        const val MARGIN = 3
        const val SAMPLES = 40
    }
}
