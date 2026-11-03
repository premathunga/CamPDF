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

    /** corners: normalized TL,TR,BR,BL. Returns an upright, de-skewed bitmap. */
    fun warp(src: Bitmap, corners: FloatArray, maxOut: Int): Bitmap {
        val pts = FloatArray(8) { if (it % 2 == 0) corners[it] * src.width else corners[it] * src.height }
        fun d(i: Int, j: Int) = hypot(pts[2 * i] - pts[2 * j], pts[2 * i + 1] - pts[2 * j + 1])
        var w = max(d(0, 1), d(3, 2))
        var h = max(d(0, 3), d(1, 2))
        if (w < 8f) w = 8f
        if (h < 8f) h = 8f
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

    /**
     * Finds the brightest large blob (the paper), then takes its four extreme points.
     * Works best for a light page on a darker surface; the user can always drag the corners.
     */
    fun detectCorners(path: String): FloatArray {
        val fallback = floatArrayOf(0.05f, 0.05f, 0.95f, 0.05f, 0.95f, 0.95f, 0.05f, 0.95f)
        val bm = loadOriented(path, 240) ?: return fallback
        val w = bm.width
        val h = bm.height
        val total = w * h
        val px = IntArray(total)
        bm.getPixels(px, 0, w, 0, 0, w, h)
        bm.recycle()
        val g = IntArray(total) {
            val p = px[it]
            (((p shr 16) and 255) * 299 + ((p shr 8) and 255) * 587 + (p and 255) * 114) / 1000
        }
        // Otsu threshold
        val hist = IntArray(256)
        for (v in g) hist[v]++
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
            val v = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (v > best) { best = v; thr = t }
        }
        // largest bright connected component
        val visited = BooleanArray(total)
        val queue = IntArray(total)
        var bestSize = 0
        val bestC = IntArray(8)
        for (start in 0 until total) {
            if (visited[start] || g[start] <= thr) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            var minS = Int.MAX_VALUE
            var maxS = Int.MIN_VALUE
            var minD = Int.MAX_VALUE
            var maxD = Int.MIN_VALUE
            val cs = IntArray(8)
            while (head < tail) {
                val idx = queue[head++]
                val x = idx % w
                val y = idx / w
                val s = x + y
                val d = x - y
                if (s < minS) { minS = s; cs[0] = x; cs[1] = y }   // top-left
                if (d > maxD) { maxD = d; cs[2] = x; cs[3] = y }   // top-right
                if (s > maxS) { maxS = s; cs[4] = x; cs[5] = y }   // bottom-right
                if (d < minD) { minD = d; cs[6] = x; cs[7] = y }   // bottom-left
                if (x > 0 && !visited[idx - 1] && g[idx - 1] > thr) { visited[idx - 1] = true; queue[tail++] = idx - 1 }
                if (x < w - 1 && !visited[idx + 1] && g[idx + 1] > thr) { visited[idx + 1] = true; queue[tail++] = idx + 1 }
                if (y > 0 && !visited[idx - w] && g[idx - w] > thr) { visited[idx - w] = true; queue[tail++] = idx - w }
                if (y < h - 1 && !visited[idx + w] && g[idx + w] > thr) { visited[idx + w] = true; queue[tail++] = idx + w }
            }
            if (tail > bestSize) {
                bestSize = tail
                cs.copyInto(bestC)
            }
        }
        if (bestSize < total * 0.15 || bestSize > total * 0.97) return fallback
        val out = FloatArray(8) { i ->
            if (i % 2 == 0) (bestC[i] + 0.5f) / w else (bestC[i] + 0.5f) / h
        }
        val area = abs(
            (out[0] * out[3] - out[2] * out[1]) +
                (out[2] * out[5] - out[4] * out[3]) +
                (out[4] * out[7] - out[6] * out[5]) +
                (out[6] * out[1] - out[0] * out[7])
        ) / 2f
        if (area < 0.15f) return fallback
        return out
    }

    // ---------- filters ----------

    fun applyFilter(src: Bitmap, mode: FilterMode): Bitmap = when (mode) {
        FilterMode.ORIGINAL -> src
        FilterMode.LIGHTEN -> lighten(src)
        else -> normalize(src, mode).also { src.recycle() }
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
