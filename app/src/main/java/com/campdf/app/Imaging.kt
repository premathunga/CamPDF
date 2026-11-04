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
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

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

    // ---------- detection / perspective ----------

    fun detectCorners(path: String): FloatArray = DocDetect.detect(path)

    private fun cross3(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0]
    )

    private fun dot3(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    /**
     * Real width/height ratio of a rectangle seen in perspective (Zhang & He, whiteboard scanning).
     * p = TL, TR, BR, BL in pixels. Returns null when there is no usable perspective.
     */
    private fun estimateAspect(p: FloatArray, imgW: Int, imgH: Int): Double? {
        val m1 = doubleArrayOf(p[0].toDouble(), p[1].toDouble(), 1.0) // TL
        val m2 = doubleArrayOf(p[2].toDouble(), p[3].toDouble(), 1.0) // TR
        val m3 = doubleArrayOf(p[6].toDouble(), p[7].toDouble(), 1.0) // BL
        val m4 = doubleArrayOf(p[4].toDouble(), p[5].toDouble(), 1.0) // BR
        val k2d = dot3(cross3(m2, m4), m3)
        val k3d = dot3(cross3(m3, m4), m2)
        if (abs(k2d) < 1e-9 || abs(k3d) < 1e-9) return null
        val c14 = cross3(m1, m4)
        val k2 = dot3(c14, m3) / k2d
        val k3 = dot3(c14, m2) / k3d
        val n2 = doubleArrayOf(k2 * m2[0] - m1[0], k2 * m2[1] - m1[1], k2 * m2[2] - m1[2])
        val n3 = doubleArrayOf(k3 * m3[0] - m1[0], k3 * m3[1] - m1[1], k3 * m3[2] - m1[2])
        val u0 = imgW / 2.0
        val v0 = imgH / 2.0
        val denom = n2[2] * n3[2]
        if (abs(denom) < 1e-6) return null
        val f2 = -1.0 / denom * (
            (n2[0] * n3[0] - (n2[0] * n3[2] + n2[2] * n3[0]) * u0 + n2[2] * n3[2] * u0 * u0) +
                (n2[1] * n3[1] - (n2[1] * n3[2] + n2[2] * n3[1]) * v0 + n2[2] * n3[2] * v0 * v0)
            )
        if (f2.isNaN() || f2 <= 0.0) return null
        val f = sqrt(f2)
        fun norm2(n: DoubleArray): Double {
            val a = (n[0] - u0 * n[2]) / f
            val b = (n[1] - v0 * n[2]) / f
            return a * a + b * b + n[2] * n[2]
        }
        val d3 = norm2(n3)
        if (d3 <= 0.0) return null
        val r = sqrt(norm2(n2) / d3)
        return if (r.isFinite() && r in 0.25..4.0) r else null
    }

    /** corners: normalized TL,TR,BR,BL. Returns an upright, de-skewed bitmap with the true page proportions. */
    /**
     * Perspective-correct a document quadrilateral. Corner coordinates are normalized TL,TR,BR,BL.
     * Reject malformed/degenerate input before asking Android's Matrix for a homography; this
     * avoids tiny/zero sized output bitmaps and unstable transforms from accidental corner crossing.
     */
    fun warp(src: Bitmap, corners: FloatArray, maxOut: Int): Bitmap {
        require(maxOut >= 256) { "Output resolution is too small" }
        val fallback = floatArrayOf(0.02f, 0.02f, 0.98f, 0.02f, 0.98f, 0.98f, 0.02f, 0.98f)
        val c = if (corners.size == 8 && corners.all { it.isFinite() }) corners else fallback
        val normalized = FloatArray(8) { c[it].coerceIn(0f, 1f) }
        val pts = FloatArray(8) {
            if (it % 2 == 0) normalized[it] * (src.width - 1) else normalized[it] * (src.height - 1)
        }

        fun cross(i: Int, j: Int, k: Int): Float {
            val ax = pts[2 * j] - pts[2 * i]
            val ay = pts[2 * j + 1] - pts[2 * i + 1]
            val bx = pts[2 * k] - pts[2 * j]
            val by = pts[2 * k + 1] - pts[2 * j + 1]
            return ax * by - ay * bx
        }
        val turns = (0..3).map { cross(it, (it + 1) % 4, (it + 2) % 4) }
        val convex = turns.all { it > 0f } || turns.all { it < 0f }
        val area = abs(
            (0..3).sumOf { i ->
                val j = (i + 1) % 4
                (pts[2 * i] * pts[2 * j + 1] - pts[2 * j] * pts[2 * i + 1]).toDouble()
            }
        ) / 2.0
        val safePts = if (convex && area >= src.width.toDouble() * src.height * 0.025) pts else
            FloatArray(8) { if (it % 2 == 0) fallback[it] * (src.width - 1) else fallback[it] * (src.height - 1) }

        fun d(i: Int, j: Int) = hypot(safePts[2 * i] - safePts[2 * j], safePts[2 * i + 1] - safePts[2 * j + 1])
        var w = ((d(0, 1) + d(3, 2)) / 2f).coerceAtLeast(8f)
        var h = ((d(0, 3) + d(1, 2)) / 2f).coerceAtLeast(8f)
        val a = estimateAspect(safePts, src.width, src.height)
        if (a != null && a in 0.20..5.0) {
            val hEst = (w / a).toFloat()
            if (hEst in (h * 0.75f)..(h * 1.33f)) h = hEst
        }
        val scale = min(1f, maxOut.toFloat() / max(w, h))
        val ow = (w * scale).roundToInt().coerceAtLeast(1)
        val oh = (h * scale).roundToInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        out.eraseColor(android.graphics.Color.WHITE)
        val dst = floatArrayOf(0f, 0f, ow.toFloat(), 0f, ow.toFloat(), oh.toFloat(), 0f, oh.toFloat())
        val m = Matrix()
        if (!m.setPolyToPoly(safePts, 0, dst, 0, 4)) {
            out.recycle()
            return Bitmap.createScaledBitmap(src, ow, oh, true)
        }
        Canvas(out).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    // ---------- filters ----------

    fun applyFilter(src: Bitmap, mode: FilterMode): Bitmap = when (mode) {
        FilterMode.ORIGINAL -> src
        FilterMode.LIGHTEN -> lighten(src)
        else -> process(src, mode).also { src.recycle() }
    }

    private fun lighten(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val cm = ColorMatrix(
            floatArrayOf(
                1.12f, 0f, 0f, 0f, 22f,
                0f, 1.12f, 0f, 0f, 22f,
                0f, 0f, 1.12f, 0f, 22f,
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

    private fun dilateF(a: FloatArray, w: Int, h: Int, r: Int): FloatArray {
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

    /** Estimates the paper brightness per area (ignoring the text) at low resolution -> shadow map. */
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
        lum = dilateF(lum, sw, sh, 2)   // removes dark text from the estimate
        lum = boxBlur(lum, sw, sh, 3)
        lum = boxBlur(lum, sw, sh, 3)
        return Triple(lum, sw, sh)
    }

    /** Black / white levels for auto-contrast (ignores extreme outliers). */
    private fun levels(src: Bitmap): Pair<Float, Float> {
        val sc = 300f / max(src.width, src.height)
        val small = if (sc < 1f) {
            Bitmap.createScaledBitmap(
                src,
                (src.width * sc).roundToInt().coerceAtLeast(1),
                (src.height * sc).roundToInt().coerceAtLeast(1),
                true
            )
        } else src
        val px = IntArray(small.width * small.height)
        small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
        if (small !== src) small.recycle()
        val hist = IntArray(256)
        for (p in px) hist[luma(p).toInt().coerceIn(0, 255)]++
        val total = px.size
        fun pct(f: Float): Float {
            var acc = 0
            val target = (total * f).toInt()
            for (i in 0..255) {
                acc += hist[i]
                if (acc >= target) return i.toFloat()
            }
            return 255f
        }
        return Pair(pct(0.005f), pct(0.97f))
    }

    private val WHITEN_LUT = IntArray(256) { i ->
        (255.0 * (i / 240.0).coerceIn(0.0, 1.0).pow(1.4)).toInt().coerceIn(0, 255)
    }

    private fun sharpenCh(c: Int, u: Int, d: Int, l: Int, r: Int, shift: Int, amt: Float): Int {
        val cc = (c shr shift) and 255
        val avg = (4 * cc + ((u shr shift) and 255) + ((d shr shift) and 255) +
            ((l shr shift) and 255) + ((r shr shift) and 255)) / 8f
        return (cc + amt * (cc - avg)).toInt().coerceIn(0, 255)
    }

    /**
     * ENHANCE   : auto-levels (contrast stretch) + light sharpening, keeps colour
     * NO_SHADOW : shadow removal by dividing by the estimated paper brightness, whitens the page, keeps colour
     * BW        : shadow removal + sharpening + adaptive black/white (crisp text for printing / filing)
     */
    private fun process(src: Bitmap, mode: FilterMode): Bitmap {
        val w = src.width
        val h = src.height
        val useBg = mode == FilterMode.NO_SHADOW || mode == FilterMode.BW
        var bg = FloatArray(0)
        var sw = 1
        var sh = 1
        if (useBg) {
            val t = backgroundMap(src)
            bg = t.first
            sw = t.second
            sh = t.third
        }
        var lo = 0f
        var hi = 255f
        var bwThreshold = 157.5f
        if (mode == FilterMode.ENHANCE || mode == FilterMode.BW) {
            // Robust percentile levels make both contrast enhancement and BW conversion adapt
            // to dim captures, aged paper and documents with different ink/paper densities.
            val p = levels(src)
            lo = p.first
            hi = p.second
            if (mode == FilterMode.BW) {
                bwThreshold = (lo + (hi - lo) * 0.58f).coerceIn(95f, 220f)
            }
        }
        val levelScale = 255f / (hi - lo).coerceAtLeast(40f)
        val x0 = IntArray(w)
        val fx = FloatArray(w)
        if (useBg) {
            for (x in 0 until w) {
                val gx = ((x + 0.5f) * sw / w - 0.5f).coerceIn(0f, sw - 1.001f)
                x0[x] = gx.toInt()
                fx[x] = gx - x0[x]
            }
        }
        val sharp = when (mode) {
            FilterMode.BW -> 0.9f
            FilterMode.ENHANCE -> 0.6f
            else -> 0.5f
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val rows = 32
        val inBuf = IntArray(w * (rows + 2))
        val tm = IntArray(w * (rows + 2))
        val outBuf = IntArray(w * rows)
        var y = 0
        while (y < h) {
            val n = min(rows, h - y)
            // rows y-1 .. y+n (one extra row above and below for the sharpening kernel)
            for (j in 0 until n + 2) {
                val ry = (y - 1 + j).coerceIn(0, h - 1)
                src.getPixels(inBuf, j * w, w, 0, ry, w, 1)
            }
            for (j in 0 until n + 2) {
                val ry = (y - 1 + j).coerceIn(0, h - 1)
                var y0 = 0
                var fy = 0f
                if (useBg) {
                    val gy = ((ry + 0.5f) * sh / h - 0.5f).coerceIn(0f, sh - 1.001f)
                    y0 = gy.toInt()
                    fy = gy - y0
                }
                val rowA = y0 * sw
                val rowB = (y0 + 1) * sw
                for (x in 0 until w) {
                    val i = j * w + x
                    val p = inBuf[i]
                    val r = (p shr 16) and 255
                    val g = (p shr 8) and 255
                    val b = p and 255
                    var k = 1f
                    if (useBg) {
                        val xi = x0[x]
                        val fxx = fx[x]
                        val top = bg[rowA + xi] * (1 - fxx) + bg[rowA + xi + 1] * fxx
                        val bot = bg[rowB + xi] * (1 - fxx) + bg[rowB + xi + 1] * fxx
                        k = 255f / (top * (1 - fy) + bot * fy).coerceAtLeast(50f)
                    }
                    var rr: Int
                    var gg: Int
                    var bb: Int
                    when (mode) {
                        FilterMode.NO_SHADOW -> {
                            rr = WHITEN_LUT[(r * k).toInt().coerceIn(0, 255)]
                            gg = WHITEN_LUT[(g * k).toInt().coerceIn(0, 255)]
                            bb = WHITEN_LUT[(b * k).toInt().coerceIn(0, 255)]
                        }
                        FilterMode.BW -> {
                            val gv = ((r * 0.299f + g * 0.587f + b * 0.114f) * k).toInt().coerceIn(0, 255)
                            rr = gv; gg = gv; bb = gv
                        }
                        FilterMode.ENHANCE -> {
                            rr = ((r - lo) * levelScale).toInt().coerceIn(0, 255)
                            gg = ((g - lo) * levelScale).toInt().coerceIn(0, 255)
                            bb = ((b - lo) * levelScale).toInt().coerceIn(0, 255)
                        }
                        else -> { rr = r; gg = g; bb = b }
                    }
                    tm[i] = (rr shl 16) or (gg shl 8) or bb
                }
            }
            for (j in 1..n) {
                for (x in 0 until w) {
                    val c = tm[j * w + x]
                    val up = tm[(j - 1) * w + x]
                    val dn = tm[(j + 1) * w + x]
                    val lf = tm[j * w + max(x - 1, 0)]
                    val rt = tm[j * w + min(x + 1, w - 1)]
                    val r2 = sharpenCh(c, up, dn, lf, rt, 16, sharp)
                    val o = (j - 1) * w + x
                    if (mode == FilterMode.BW) {
                        // Soft transition around an image-adaptive threshold preserves thin
                        // handwriting better than a fixed global cutoff.
                        val t = ((r2 - (bwThreshold - 36f)) / 72f).coerceIn(0f, 1f)
                        val v = (255f * t * t * (3f - 2f * t)).toInt()
                        outBuf[o] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                    } else {
                        val g2 = sharpenCh(c, up, dn, lf, rt, 8, sharp)
                        val b2 = sharpenCh(c, up, dn, lf, rt, 0, sharp)
                        outBuf[o] = (0xFF shl 24) or (r2 shl 16) or (g2 shl 8) or b2
                    }
                }
            }
            out.setPixels(outBuf, 0, w, 0, y, w, n)
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
