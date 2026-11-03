package com.campdf.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.acos
import kotlin.math.sqrt
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Pure-Kotlin image pipeline: no network, no external SDKs. */
object Imaging {

    // ---------- loading ----------

    fun loadOriented(path: String, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bm = BitmapFactory.decodeFile(path, opts) ?: return null
        val m = exifMatrix(path)
        if (m != null) {
            val r = Bitmap.createBitmap(bm, 0, 0, bm.width, bm.height, m, true)
            if (r !== bm) bm.recycle()
            bm = r
        }
        val longest = max(bm.width, bm.height)
        if (longest > maxDim) {
            val s = maxDim.toFloat() / longest
            val r = Bitmap.createScaledBitmap(
                bm,
                (bm.width * s).roundToInt().coerceAtLeast(1),
                (bm.height * s).roundToInt().coerceAtLeast(1),
                true
            )
            if (r !== bm) bm.recycle()
            bm = r
        }
        return bm
    }

    private fun exifMatrix(path: String): Matrix? {
        val o = try {
            ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val m = Matrix()
        when (o) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return null
        }
        return m
    }

    fun rotate(bm: Bitmap, quarterTurns: Int): Bitmap {
        val q = ((quarterTurns % 4) + 4) % 4
        if (q == 0) return bm
        val m = Matrix().apply { postRotate(q * 90f) }
        val r = Bitmap.createBitmap(bm, 0, 0, bm.width, bm.height, m, true)
        if (r !== bm) bm.recycle()
        return r
    }

    // ---------- perspective crop ----------

    private const val A4_RATIO = 1.41421356

    /** Snaps a width/height ratio to A4 (portrait or landscape) when it is within 5%. */
    private fun snapA4(r: Double): Double {
        if (abs((1.0 / r) / A4_RATIO - 1.0) < 0.05) return 1.0 / A4_RATIO
        if (abs(r / A4_RATIO - 1.0) < 0.05) return A4_RATIO
        return r
    }

    private fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0]
    )

    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    /**
     * Estimates the true width/height of the (rectangular) page from its four photographed corners
     * (Zhang and He, "Whiteboard scanning"). pts = TL,TR,BR,BL in pixels; (u0,v0) = image centre.
     * Returns null for degenerate cases (page almost front-on, bad corners).
     */
    private fun pageAspect(pts: FloatArray, u0: Double, v0: Double): Double? {
        val m1 = doubleArrayOf(pts[0].toDouble(), pts[1].toDouble(), 1.0)   // TL
        val m2 = doubleArrayOf(pts[2].toDouble(), pts[3].toDouble(), 1.0)   // TR
        val m4 = doubleArrayOf(pts[4].toDouble(), pts[5].toDouble(), 1.0)   // BR
        val m3 = doubleArrayOf(pts[6].toDouble(), pts[7].toDouble(), 1.0)   // BL
        val c14 = cross(m1, m4)
        val den2 = dot(cross(m2, m4), m3)
        val den3 = dot(cross(m3, m4), m2)
        if (abs(den2) < 1e-9 || abs(den3) < 1e-9) return null
        val k2 = dot(c14, m3) / den2
        val k3 = dot(c14, m2) / den3
        val n2 = doubleArrayOf(k2 * m2[0] - m1[0], k2 * m2[1] - m1[1], k2 * m2[2] - m1[2])
        val n3 = doubleArrayOf(k3 * m3[0] - m1[0], k3 * m3[1] - m1[1], k3 * m3[2] - m1[2])
        if (abs(n2[2]) < 1e-6 || abs(n3[2]) < 1e-6) return null
        val f2 = -1.0 / (n2[2] * n3[2]) * (
            (n2[0] * n3[0] - (n2[0] * n3[2] + n2[2] * n3[0]) * u0 + n2[2] * n3[2] * u0 * u0) +
                (n2[1] * n3[1] - (n2[1] * n3[2] + n2[2] * n3[1]) * v0 + n2[2] * n3[2] * v0 * v0)
            )
        if (f2.isNaN() || f2.isInfinite() || f2 <= 0.0) return null
        fun q(n: DoubleArray): Double {
            val a = n[0] - u0 * n[2]
            val b = n[1] - v0 * n[2]
            return (a * a + b * b) / f2 + n[2] * n[2]
        }
        val q3 = q(n3)
        if (q3 <= 0.0) return null
        val r = sqrt(q(n2) / q3)
        if (r.isNaN() || r.isInfinite() || r < 0.2 || r > 5.0) return null
        return r
    }

    /**
     * corners: normalized TL,TR,BR,BL. Returns an upright, de-skewed bitmap.
     * The output keeps the page's real proportions (estimated from the perspective), and is
     * snapped to exact A4 when snapToA4 is true and the page is within 5% of A4.
     */
    fun warp(src: Bitmap, corners: FloatArray, maxOut: Int, snapToA4: Boolean = true): Bitmap {
        val pts = FloatArray(8) { if (it % 2 == 0) corners[it] * src.width else corners[it] * src.height }
        fun d(i: Int, j: Int) = hypot(pts[2 * i] - pts[2 * j], pts[2 * i + 1] - pts[2 * j + 1])
        var w = max(d(0, 1), d(3, 2))
        var h = max(d(0, 3), d(1, 2))
        if (w < 8f) w = 8f
        if (h < 8f) h = 8f
        var ratio: Double = pageAspect(pts, src.width / 2.0, src.height / 2.0) ?: (w.toDouble() / h)
        if (snapToA4) ratio = snapA4(ratio)
        val area = w.toDouble() * h
        val nh = sqrt(area / ratio)
        w = (nh * ratio).toFloat()
        h = nh.toFloat()
        val scale = min(1f, maxOut / max(w, h))
        val ow = (w * scale).roundToInt().coerceAtLeast(1)
        val oh = (h * scale).roundToInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        val dst = floatArrayOf(0f, 0f, ow.toFloat(), 0f, ow.toFloat(), oh.toFloat(), 0f, oh.toFloat())
        val m = Matrix()
        m.setPolyToPoly(pts, 0, dst, 0, 4)
        Canvas(out).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    // ---------- automatic edge detection ----------

    private class Cand(val score: Float, val q: List<IntArray>)

    /**
     * Looks for the page in three different ways (a bright blob, an area whose colour differs from
     * the picture border, and a "paper-white" low-colour area), turns each blob into its best
     * four-corner shape, and keeps the most convincing one. The user can always drag the corners.
     * Returns normalized TL,TR,BR,BL, or a default frame when nothing convincing is found.
     */
    fun detectCorners(path: String): FloatArray {
        val fallback = floatArrayOf(0.05f, 0.05f, 0.95f, 0.05f, 0.95f, 0.95f, 0.05f, 0.95f)
        val bm = loadOriented(path, 320) ?: return fallback
        val w = bm.width
        val h = bm.height
        val total = w * h
        val px = IntArray(total)
        bm.getPixels(px, 0, w, 0, 0, w, h)
        bm.recycle()
        val rc = IntArray(total) { (px[it] shr 16) and 255 }
        val gc = IntArray(total) { (px[it] shr 8) and 255 }
        val bc = IntArray(total) { px[it] and 255 }
        val gray = IntArray(total) { (rc[it] * 299 + gc[it] * 587 + bc[it] * 114) / 1000 }
        val masks = ArrayList<BooleanArray>()

        // 1) bright blob
        val g = boxBlurInt(gray, w, h, 2)
        val thr = otsu(g)
        masks.add(BooleanArray(total) { g[it] > thr })

        // 2) colour that differs from the picture border
        val m = max(2, (0.04f * min(w, h)).toInt())
        val mr = frameMedian(rc, w, h, m)
        val mg = frameMedian(gc, w, h, m)
        val mb = frameMedian(bc, w, h, m)
        val dist0 = IntArray(total) { abs(rc[it] - mr) + abs(gc[it] - mg) + abs(bc[it] - mb) }
        val dist = boxBlurInt(dist0, w, h, 2)
        val dthr = otsu(dist)
        masks.add(BooleanArray(total) { dist[it] > dthr })

        // 3) paper-white (little colour) when the table is coloured
        val chroma0 = IntArray(total) {
            max(rc[it], max(gc[it], bc[it])) - min(rc[it], min(gc[it], bc[it]))
        }
        val chroma = boxBlurInt(chroma0, w, h, 2)
        val cthr = otsu(chroma)
        var sumIn = 0L
        var nIn = 0
        var sumOut = 0L
        var nOut = 0
        for (i in 0 until total) {
            if (chroma[i] <= cthr) { sumIn += chroma[i]; nIn++ } else { sumOut += chroma[i]; nOut++ }
        }
        if (nIn > 0 && nOut > 0 && (sumOut.toDouble() / nOut - sumIn.toDouble() / nIn) >= 25.0) {
            masks.add(BooleanArray(total) { chroma[it] <= cthr })
        }

        var best: Cand? = null
        for (mk in masks) {
            val c = candidate(mk, w, h) ?: continue
            val b = best
            if (b == null || c.score > b.score) best = c
        }
        val bq = best ?: return fallback
        val out = FloatArray(8)
        for (i in 0 until 4) {
            out[2 * i] = (bq.q[i][0] + 0.5f) / w
            out[2 * i + 1] = (bq.q[i][1] + 0.5f) / h
        }
        return out
    }

    private fun boxBlurInt(a: IntArray, w: Int, h: Int, r: Int): IntArray {
        val n = 2 * r + 1
        val tmp = IntArray(a.size)
        val out = IntArray(a.size)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var s = 0
                for (k in -r..r) s += a[row + (x + k).coerceIn(0, w - 1)]
                tmp[row + x] = s / n
            }
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                var s = 0
                for (k in -r..r) s += tmp[(y + k).coerceIn(0, h - 1) * w + x]
                out[y * w + x] = s / n
            }
        }
        return out
    }

    private fun otsu(v: IntArray): Int {
        val hist = IntArray(256)
        for (x in v) hist[x.coerceIn(0, 255)]++
        val total = v.size
        var sum = 0L
        for (i in 0..255) sum += i.toLong() * hist[i]
        var sumB = 0L
        var wB = 0
        var best = -1.0
        var thr = 128
        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break
            sumB += t.toLong() * hist[t]
            val mB = sumB.toDouble() / wB
            val mF = (sum - sumB).toDouble() / wF
            val vv = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (vv > best) { best = vv; thr = t }
        }
        return thr
    }

    private fun frameMedian(ch: IntArray, w: Int, h: Int, m: Int): Int {
        val hist = IntArray(256)
        var n = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (x < m || y < m || x >= w - m || y >= h - m) {
                    hist[ch[y * w + x].coerceIn(0, 255)]++
                    n++
                }
            }
        }
        var acc = 0
        for (i in 0..255) {
            acc += hist[i]
            if (acc * 2 >= n) return i
        }
        return 128
    }

    /** Largest 4-connected blob of true pixels, as a list of pixel indices (or null). */
    private fun largestComponent(mask: BooleanArray, w: Int, h: Int): IntArray? {
        val total = w * h
        val visited = BooleanArray(total)
        val queue = IntArray(total)
        var best: IntArray? = null
        var bestSize = 0
        for (start in 0 until total) {
            if (visited[start] || !mask[start]) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            while (head < tail) {
                val idx = queue[head++]
                val x = idx % w
                val y = idx / w
                if (x > 0) { val n = idx - 1; if (!visited[n] && mask[n]) { visited[n] = true; queue[tail++] = n } }
                if (x < w - 1) { val n = idx + 1; if (!visited[n] && mask[n]) { visited[n] = true; queue[tail++] = n } }
                if (y > 0) { val n = idx - w; if (!visited[n] && mask[n]) { visited[n] = true; queue[tail++] = n } }
                if (y < h - 1) { val n = idx + w; if (!visited[n] && mask[n]) { visited[n] = true; queue[tail++] = n } }
            }
            if (tail > bestSize) {
                bestSize = tail
                best = queue.copyOf(tail)
            }
        }
        return best
    }

    private fun cross3(ox: Int, oy: Int, ax: Int, ay: Int, bx: Int, by: Int): Long =
        (ax - ox).toLong() * (by - oy) - (ay - oy).toLong() * (bx - ox)

    /** Andrew's monotone chain. keys = x * 4096 + y (distinct). Returns hull points as [x, y]. */
    private fun convexHull(keys: IntArray): List<IntArray> {
        keys.sort()
        val n = keys.size
        val xs = IntArray(n) { keys[it] / 4096 }
        val ys = IntArray(n) { keys[it] % 4096 }
        if (n < 3) return List(n) { intArrayOf(xs[it], ys[it]) }
        val st = IntArray(2 * n + 2)
        var k = 0
        for (i in 0 until n) {
            while (k >= 2 && cross3(xs[st[k - 2]], ys[st[k - 2]], xs[st[k - 1]], ys[st[k - 1]], xs[i], ys[i]) <= 0L) k--
            st[k++] = i
        }
        val lower = k + 1
        for (i in n - 2 downTo 0) {
            while (k >= lower && cross3(xs[st[k - 2]], ys[st[k - 2]], xs[st[k - 1]], ys[st[k - 1]], xs[i], ys[i]) <= 0L) k--
            st[k++] = i
        }
        val out = ArrayList<IntArray>()
        for (i in 0 until k - 1) out.add(intArrayOf(xs[st[i]], ys[st[i]]))
        return out
    }

    private fun polyArea(p: List<IntArray>): Double {
        var s = 0L
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % p.size]
            s += a[0].toLong() * b[1] - b[0].toLong() * a[1]
        }
        return abs(s) / 2.0
    }

    /** Drops the least important vertices until at most maxN remain. */
    private fun reduceHull(hull: List<IntArray>, maxN: Int): List<IntArray> {
        val h = ArrayList(hull)
        while (h.size > maxN) {
            var bestArea = Long.MAX_VALUE
            var bi = 0
            for (i in h.indices) {
                val a = h[(i + h.size - 1) % h.size]
                val b = h[i]
                val c = h[(i + 1) % h.size]
                val ar = abs(cross3(a[0], a[1], b[0], b[1], c[0], c[1]))
                if (ar < bestArea) { bestArea = ar; bi = i }
            }
            h.removeAt(bi)
        }
        return h
    }

    /** The four hull vertices that enclose the largest area. */
    private fun bestQuad(hull: List<IntArray>): List<IntArray>? {
        val h = reduceHull(hull, 14)
        val n = h.size
        if (n < 4) return null
        var bestA = -1.0
        var bq: List<IntArray>? = null
        for (a in 0 until n - 3) for (b in a + 1 until n - 2) for (c in b + 1 until n - 1) for (d in c + 1 until n) {
            val q = listOf(h[a], h[b], h[c], h[d])
            val ar = polyArea(q)
            if (ar > bestA) { bestA = ar; bq = q }
        }
        return bq
    }

    /** Orders four points clockwise starting at the top-left: TL, TR, BR, BL. */
    private fun orderQuad(q: List<IntArray>): List<IntArray> {
        val cx = (q[0][0] + q[1][0] + q[2][0] + q[3][0]) / 4.0
        val cy = (q[0][1] + q[1][1] + q[2][1] + q[3][1]) / 4.0
        val s = q.sortedBy { atan2(it[1] - cy, it[0] - cx) }
        var k = 0
        for (i in 1..3) if (s[i][0] + s[i][1] < s[k][0] + s[k][1]) k = i
        return List(4) { s[(k + it) % 4] }
    }

    private fun anglesOk(q: List<IntArray>): Boolean {
        for (i in 0..3) {
            val p = q[i]
            val a = q[(i + 3) % 4]
            val b = q[(i + 1) % 4]
            val ax = (a[0] - p[0]).toDouble()
            val ay = (a[1] - p[1]).toDouble()
            val bx = (b[0] - p[0]).toDouble()
            val by = (b[1] - p[1]).toDouble()
            val c = (ax * bx + ay * by) / (sqrt(ax * ax + ay * ay) * sqrt(bx * bx + by * by) + 1e-9)
            val ang = Math.toDegrees(acos(c.coerceIn(-1.0, 1.0)))
            if (ang < 50.0 || ang > 130.0) return false
        }
        return true
    }

    /** Turns a mask into a scored four-corner page candidate, or null if it does not look like a page. */
    private fun candidate(mask: BooleanArray, w: Int, h: Int): Cand? {
        val total = w * h
        val compIdx = largestComponent(mask, w, h) ?: return null
        val inComp = BooleanArray(total)
        for (i in compIdx) inComp[i] = true
        // fill holes (text, shadows): flood the outside from the picture border
        val outside = BooleanArray(total)
        val queue = IntArray(total)
        var head = 0
        var tail = 0
        for (x in 0 until w) {
            val t = x
            val b = (h - 1) * w + x
            if (!inComp[t] && !outside[t]) { outside[t] = true; queue[tail++] = t }
            if (!inComp[b] && !outside[b]) { outside[b] = true; queue[tail++] = b }
        }
        for (y in 0 until h) {
            val l = y * w
            val r = y * w + w - 1
            if (!inComp[l] && !outside[l]) { outside[l] = true; queue[tail++] = l }
            if (!inComp[r] && !outside[r]) { outside[r] = true; queue[tail++] = r }
        }
        while (head < tail) {
            val idx = queue[head++]
            val x = idx % w
            val y = idx / w
            if (x > 0) { val n = idx - 1; if (!inComp[n] && !outside[n]) { outside[n] = true; queue[tail++] = n } }
            if (x < w - 1) { val n = idx + 1; if (!inComp[n] && !outside[n]) { outside[n] = true; queue[tail++] = n } }
            if (y > 0) { val n = idx - w; if (!inComp[n] && !outside[n]) { outside[n] = true; queue[tail++] = n } }
            if (y < h - 1) { val n = idx + w; if (!inComp[n] && !outside[n]) { outside[n] = true; queue[tail++] = n } }
        }
        var area = 0
        for (i in 0 until total) if (!outside[i]) area++
        if (area < 0.12 * total || area > 0.97 * total) return null

        // boundary pixels of the filled blob
        val keys = ArrayList<Int>()
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                if (outside[i]) continue
                val edge = x == 0 || y == 0 || x == w - 1 || y == h - 1 ||
                    outside[i - 1] || outside[i + 1] || outside[i - w] || outside[i + w]
                if (edge) keys.add(x * 4096 + y)
            }
        }
        if (keys.size < 4) return null
        val hull = convexHull(keys.toIntArray())
        val quad = bestQuad(hull) ?: return null
        val q = orderQuad(quad)
        if (!anglesOk(q)) return null
        val qa = polyArea(q)
        if (qa < 0.12 * total) return null
        val fill = min(1.0, area / qa)
        if (fill < 0.75) return null
        var score = (fill * fill * sqrt(qa / total)).toFloat()
        for (p in q) {
            if (p[0] <= 1 || p[1] <= 1 || p[0] >= w - 2 || p[1] >= h - 2) score *= 0.8f
        }
        return Cand(score, q)
    }

    // ---------- filters ----------

    fun applyFilter(src: Bitmap, mode: FilterMode): Bitmap = when (mode) {
        FilterMode.ORIGINAL -> src
        FilterMode.LIGHTEN -> lighten(src)
        FilterMode.ENHANCE -> {
            val n = normalize(src, FilterMode.MAGIC)
            src.recycle()
            sharpen(n, 0.6f)
        }
        else -> normalize(src, mode).also { src.recycle() }
    }

    /** Light sharpening (cross kernel, row by row to keep memory low) so thin text stays crisp. */
    private fun sharpen(src: Bitmap, amount: Float): Bitmap {
        val w = src.width
        val h = src.height
        if (w < 3 || h < 3) return src
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        var prev = IntArray(w)
        var cur = IntArray(w)
        var next = IntArray(w)
        val res = IntArray(w)
        src.getPixels(cur, 0, w, 0, 0, w, 1)
        cur.copyInto(prev)
        src.getPixels(next, 0, w, 0, 1, w, 1)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val xl = if (x > 0) x - 1 else 0
                val xr = if (x < w - 1) x + 1 else w - 1
                val c = cur[x]
                val l = cur[xl]
                val r = cur[xr]
                val u = prev[x]
                val d = next[x]
                val rr = sharpCh(c, l, r, u, d, 16, amount)
                val gg = sharpCh(c, l, r, u, d, 8, amount)
                val bb = sharpCh(c, l, r, u, d, 0, amount)
                res[x] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
            }
            out.setPixels(res, 0, w, 0, y, w, 1)
            val t = prev
            prev = cur
            cur = next
            next = t
            val ny = if (y + 2 < h) y + 2 else h - 1
            src.getPixels(next, 0, w, 0, ny, w, 1)
        }
        src.recycle()
        return out
    }

    private fun sharpCh(c: Int, l: Int, r: Int, u: Int, d: Int, shift: Int, a: Float): Int {
        val cv = (c shr shift) and 255
        val nb = ((l shr shift) and 255) + ((r shr shift) and 255) + ((u shr shift) and 255) + ((d shr shift) and 255)
        return (cv * (1f + 4f * a) - a * nb).toInt().coerceIn(0, 255)
    }

    private fun lighten(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val cm = ColorMatrix(
            floatArrayOf(
                1.15f, 0f, 0f, 0f, 18f,
                0f, 1.15f, 0f, 0f, 18f,
                0f, 0f, 1.15f, 0f, 18f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        val p = Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }
        Canvas(out).drawBitmap(src, 0f, 0f, p)
        src.recycle()
        return out
    }

    private fun luma(p: Int): Float =
        ((p shr 16) and 255) * 0.299f + ((p shr 8) and 255) * 0.587f + (p and 255) * 0.114f

    private fun dilate(a: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val out = FloatArray(a.size)
        for (y in 0 until h) for (x in 0 until w) {
            var m = 0f
            for (yy in max(0, y - r)..min(h - 1, y + r)) for (xx in max(0, x - r)..min(w - 1, x + r)) {
                val v = a[yy * w + xx]
                if (v > m) m = v
            }
            out[y * w + x] = m
        }
        return out
    }

    private fun boxBlur(a: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val out = FloatArray(a.size)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f
            var n = 0
            for (yy in max(0, y - r)..min(h - 1, y + r)) for (xx in max(0, x - r)..min(w - 1, x + r)) {
                s += a[yy * w + xx]
                n++
            }
            out[y * w + x] = s / n
        }
        return out
    }

    /** Estimates paper brightness per area (ignoring text) at low resolution. */
    private fun backgroundMap(src: Bitmap): Triple<FloatArray, Int, Int> {
        val sw = (src.width / 20).coerceAtLeast(6)
        val sh = (src.height / 20).coerceAtLeast(6)
        var cur = src
        while (cur.width / 2 >= sw * 2 && cur.height / 2 >= sh * 2) {
            val next = Bitmap.createScaledBitmap(cur, cur.width / 2, cur.height / 2, true)
            if (cur !== src) cur.recycle()
            cur = next
        }
        val small = Bitmap.createScaledBitmap(cur, sw, sh, true)
        if (cur !== src && cur !== small) cur.recycle()
        val px = IntArray(sw * sh)
        small.getPixels(px, 0, sw, 0, 0, sw, sh)
        if (small !== src) small.recycle()
        var lum = FloatArray(sw * sh) { luma(px[it]) }
        lum = dilate(lum, sw, sh, 2)
        lum = boxBlur(lum, sw, sh, 3)
        lum = boxBlur(lum, sw, sh, 3)
        return Triple(lum, sw, sh)
    }

    private fun enhance(c: Float): Float = ((c - 120f) * 1.3f + 130f).coerceIn(0f, 255f)

    /** Gentler than enhance(): keeps natural colours, only whitens the paper. */
    private fun soft(c: Float): Float = ((c - 128f) * 1.08f + 133f).coerceIn(0f, 255f)

    /** Shadow removal + whitening. MAGIC keeps colour, GRAY is monochrome, BW is crisp black/white. */
    private fun normalize(src: Bitmap, mode: FilterMode): Bitmap {
        val w = src.width
        val h = src.height
        val (bg, sw, sh) = backgroundMap(src)
        val x0 = IntArray(w)
        val fx = FloatArray(w)
        for (x in 0 until w) {
            val gx = ((x + 0.5f) * sw / w - 0.5f).coerceIn(0f, sw - 1.001f)
            x0[x] = gx.toInt()
            fx[x] = gx - x0[x]
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val rows = 32
        val buf = IntArray(w * rows)
        var y = 0
        while (y < h) {
            val n = min(rows, h - y)
            src.getPixels(buf, 0, w, 0, y, w, n)
            for (j in 0 until n) {
                val gy = (((y + j) + 0.5f) * sh / h - 0.5f).coerceIn(0f, sh - 1.001f)
                val y0 = gy.toInt()
                val fy = gy - y0
                val rowA = y0 * sw
                val rowB = (y0 + 1) * sw
                for (x in 0 until w) {
                    val i = j * w + x
                    val p = buf[i]
                    val xi = x0[x]
                    val fxx = fx[x]
                    val top = bg[rowA + xi] * (1 - fxx) + bg[rowA + xi + 1] * fxx
                    val bot = bg[rowB + xi] * (1 - fxx) + bg[rowB + xi + 1] * fxx
                    val b0 = top * (1 - fy) + bot * fy
                    val k = 255f / b0.coerceAtLeast(50f)
                    val r = (p shr 16) and 255
                    val g = (p shr 8) and 255
                    val b = p and 255
                    val rr: Float
                    val gg: Float
                    val bb: Float
                    when (mode) {
                        FilterMode.MAGIC -> {
                            rr = enhance(r * k); gg = enhance(g * k); bb = enhance(b * k)
                        }
                        FilterMode.NO_SHADOW -> {
                            rr = soft(r * k); gg = soft(g * k); bb = soft(b * k)
                        }
                        FilterMode.GRAY -> {
                            val v = enhance((r * 0.299f + g * 0.587f + b * 0.114f) * k)
                            rr = v; gg = v; bb = v
                        }
                        else -> {
                            val gv = (r * 0.299f + g * 0.587f + b * 0.114f) * k
                            val t = ((gv - 105f) / (190f - 105f)).coerceIn(0f, 1f)
                            val v = 255f * t * t * (3f - 2f * t)
                            rr = v; gg = v; bb = v
                        }
                    }
                    buf[i] = (0xFF shl 24) or (rr.toInt() shl 16) or (gg.toInt() shl 8) or bb.toInt()
                }
            }
            out.setPixels(buf, 0, w, 0, y, w, n)
            y += n
        }
        return out
    }

    // ---------- pages ----------

    fun pageBitmap(page: PageItem, maxDim: Int, withFilter: Boolean = true): Bitmap? {
        var bm = loadOriented(page.basePath, maxDim) ?: return null
        bm = rotate(bm, page.rotation)
        return if (withFilter) applyFilter(bm, page.filter) else bm
    }

    fun stamp(base: Bitmap, sig: Bitmap, fx: Float, fy: Float, fw: Float): Bitmap {
        val out = base.copy(Bitmap.Config.ARGB_8888, true)
        val tw = fw * out.width
        val th = tw * sig.height / sig.width
        val cx = fx * out.width
        val cy = fy * out.height
        val dst = RectF(cx - tw / 2, cy - th / 2, cx + tw / 2, cy + th / 2)
        Canvas(out).drawBitmap(sig, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    fun toJpeg(bm: Bitmap, quality: Int): ByteArray {
        val o = ByteArrayOutputStream(512 * 1024)
        bm.compress(Bitmap.CompressFormat.JPEG, quality, o)
        return o.toByteArray()
    }

    fun saveJpeg(bm: Bitmap, file: File, quality: Int) {
        FileOutputStream(file).use { bm.compress(Bitmap.CompressFormat.JPEG, quality, it) }
    }
}
