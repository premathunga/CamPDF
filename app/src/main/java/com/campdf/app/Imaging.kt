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

/** Pure-Kotlin image pipeline: no network, no external SDKs.
 * v2.6.6: CamScanner-style clean blue/red ink.
 * - Stronger colour-halo suppression (grey under blue pen eliminated)
 * - Wider neighbour-aware ultra-low sharpen on coloured ink
 * - Looser blue/red detection + higher sat boost for vivid pens
 * - Paper forced closer to pure white, black text deeper
 * - Faint Sinhala/Tamil headings still protected
 */
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

    /**
     * Estimates whether the document image is sideways (rotated 90 or 270 degrees)
     * based on text stroke directionality and gradient variance.
     * Returns the recommended rotation in quarter turns (0 or 1).
     */
    /**
     * v2.6.3 AUTO-ROTATE: tries all 4 quarter-turns and picks the one whose
     * text lines are horizontal.
     * Score = variance of the row ink-density profile: horizontal text lines
     * give sharp peaks/valleys (high variance), vertical text gives a flat
     * profile (low variance). Vertical table borders do NOT fool this,
     * unlike the old hGrad/vGrad test (which is why v2.6.1/2.6.2 left this
     * form sideways).
     * 0 vs 180 tie-break: the side with more ink in its top 20% wins
     * (forms have a heading / header at the top, footer is sparser).
     * Returns quarter-turns clockwise to apply: 0, 1, 2 or 3.
     */
    /**
     * v2.6.5 AUTO-ROTATE: tries all 4 quarter-turns, picks the one whose
     * text lines are horizontal. 0-vs-180 is decided by 3 votes, NOT by
     * top-20% ink alone (v2.6.4 bug: this form's bottom 20% has the heavy
     * blue signature + date, so flipped page scored MORE top ink and came
     * out upside-down).
     * Votes for 0 vs 180 (measured on the unrotated image):
     *  1. RED ink: red number/stamp is at the TOP of this form (y ~3%).
     *     If red centroid y > 50%, page is flipped. Weight 3.
     *  2. THICKEST horizontal line: the thick separator above the
     *     signature block sits at y ~78% upright. Row with max ink
     *     count in bottom half -> upright. Weight 2.
     *  3. Top-20% ink share: weak tie-break only. Weight 1.
     * Same logic transposed for 90 vs 270. Returns quarter-turns CW: 0..3.
     */
    fun detectOrientationQuarterTurns(bm: Bitmap): Int {
        val sc = 320f / max(bm.width, bm.height)
        val sw = (bm.width * sc).roundToInt().coerceAtLeast(32)
        val sh = (bm.height * sc).roundToInt().coerceAtLeast(32)
        val small = Bitmap.createScaledBitmap(bm, sw, sh, true)
        val px = IntArray(sw * sh)
        small.getPixels(px, 0, sw, 0, 0, sw, sh)
        if (small !== bm) small.recycle()
        val ink = BooleanArray(sw * sh) { i ->
            val p = px[i]
            val lum = ((p shr 16) and 255) * 0.299f + ((p shr 8) and 255) * 0.587f + (p and 255) * 0.114f
            lum < 180f
        }
        fun isRedPx(p: Int): Boolean {
            val r = (p shr 16) and 255; val g = (p shr 8) and 255; val b = p and 255
            return (r - g > 10) && (r - b > 10) && r > 90
        }
        // Row / column ink counts on unrotated image
        val rowCnt = FloatArray(sh); val colCnt = FloatArray(sw)
        var redSumY = 0f; var redCnt = 0f; var redSumX = 0f
        for (y in 0 until sh) for (x in 0 until sw) {
            if (ink[y * sw + x]) { rowCnt[y]++; colCnt[x]++ }
            if (isRedPx(px[y * sw + x])) { redSumY += y; redSumX += x; redCnt++ }
        }
        fun variance(counts: FloatArray): Float {
            val total = counts.sum(); if (total < 50f) return 0f
            val mean = total / counts.size; var v = 0f
            for (c in counts) v += (c - mean) * (c - mean)
            return v / counts.size
        }
        // --- 0 vs 180 vote (horizontal text) ---
        var vote0 = 0f; var vote180 = 0f
        if (redCnt > 20f) {
            val redY = redSumY / redCnt / sh
            if (redY < 0.5f) vote0 += 3f else vote180 += 3f
        }
        var maxRow = 0; var maxRowVal = -1f
        for (y in 0 until sh) if (rowCnt[y] > maxRowVal) { maxRowVal = rowCnt[y]; maxRow = y }
        if (maxRowVal > 0f) { if (maxRow.toFloat() / sh > 0.5f) vote0 += 2f else vote180 += 2f }
        var top20 = 0f; var bot20 = 0f
        for (y in 0 until (sh * 0.20f).toInt()) top20 += rowCnt[y]
        for (y in (sh * 0.80f).toInt() until sh) bot20 += rowCnt[y]
        if (top20 > bot20) vote0 += 1f else vote180 += 1f
        val horizRot = if (vote0 >= vote180) 0 else 2
        val horizVar = variance(rowCnt)
        // --- 90 vs 270 vote (vertical text) — same logic transposed ---
        var vote90 = 0f; var vote270 = 0f
        if (redCnt > 20f) {
            val redX = redSumX / redCnt / sw
            if (redX < 0.5f) vote90 += 3f else vote270 += 3f
        }
        var maxCol = 0; var maxColVal = -1f
        for (x in 0 until sw) if (colCnt[x] > maxColVal) { maxColVal = colCnt[x]; maxCol = x }
        if (maxColVal > 0f) { if (maxCol.toFloat() / sw > 0.5f) vote90 += 2f else vote270 += 2f }
        var left20 = 0f; var right20 = 0f
        for (x in 0 until (sw * 0.20f).toInt()) left20 += colCnt[x]
        for (x in (sw * 0.80f).toInt() until sw) right20 += colCnt[x]
        if (left20 > right20) vote90 += 1f else vote270 += 1f
        val vertRot = if (vote90 >= vote270) 1 else 3
        val vertVar = variance(colCnt)
        // Horizontal must win clearly; vertical borders inflate vertical score slightly
        return if (horizVar >= vertVar * 0.85f) horizRot else vertRot
    }

    /** Back-compat wrapper: 1 = sideways (90 or 270), 0 = upright / upside-down. */
    fun detectSidewaysOrientation(bm: Bitmap): Int {
        val q = detectOrientationQuarterTurns(bm)
        return if (q == 1 || q == 3) 1 else 0
    }

    /** Rotate a bitmap by quarter-turns clockwise (0..3). No-op for 0. */
    fun autoRotate(bm: Bitmap): Bitmap {
        val q = detectOrientationQuarterTurns(bm)
        return rotate(bm, q)
    }

    // ---------- detection / perspective ----------

    fun detectCorners(path: String): FloatArray = DocDetect.detect(path)

    /** null when no page could be found with confidence (UI then shows a hint). */
    fun detectCornersOrNull(path: String): FloatArray? = DocDetect.detectOrNull(path)

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
        return trimBorders(straighten(out))
    }

    /**
     * v2.7.0: post-warp straightening. A corner quad is never exact, so text lines come out slightly rotated,
     * and by a different amount at the top and the bottom of the page (keystone). The slope of the ink rows
     * is measured in horizontal bands (projection-profile search on a small copy, no per-pixel rotation), a
     * line is fitted through the band angles and one homography levels the lines over the whole page.
     * When the measurement is unsure (too little text, angles at the search limit) the bitmap is returned unchanged.
     */
    private fun straighten(bm: Bitmap): Bitmap {
        val w = bm.width
        val h = bm.height
        if (w < 300 || h < 300) return bm
        val f = max(1, max(w, h) / 500)
        val sw = w / f
        val sh = h / f
        val small = Bitmap.createScaledBitmap(bm, sw, sh, true)
        val px = IntArray(sw * sh)
        small.getPixels(px, 0, sw, 0, 0, sw, sh)
        if (small !== bm) small.recycle()
        val lum = FloatArray(px.size) { luma(px[it]) }
        val rr = max(4, sw / 25)
        val bgm = boxBlurSeparable(boxBlurSeparable(lum, sw, sh, rr), sw, sh, rr)
        val nb = 6
        val bh = sh / nb
        val xa = (sw * 0.1f).toInt()
        val xb = (sw * 0.9f).toInt()
        val ys = ArrayList<Float>()
        val angs = ArrayList<Float>()
        val wts = ArrayList<Float>()
        val bins = IntArray(sh + 2 * sw + 8)
        for (i in 0 until nb) {
            val y0 = i * bh
            val y1 = min(sh, y0 + bh + bh / 2)
            if (y1 <= y0 || xb <= xa) continue
            val cap = (y1 - y0) * (xb - xa)
            val ix = IntArray(cap)
            val iy = IntArray(cap)
            var n = 0
            for (y in y0 until y1) {
                val row = y * sw
                for (x in xa until xb) {
                    val b = bgm[row + x]
                    if (b > 60f && lum[row + x] < b * 0.78f) {
                        ix[n] = x - sw / 2
                        iy[n] = y
                        n++
                    }
                }
            }
            if (n < 200) continue
            // sharpness of the row histogram when the ink is projected along a line of slope `deg`
            fun score(deg: Float): Double {
                val rad = Math.toRadians(deg.toDouble())
                val cs = kotlin.math.cos(rad).toFloat()
                val sn = kotlin.math.sin(rad).toFloat()
                java.util.Arrays.fill(bins, 0)
                for (k in 0 until n) {
                    val r = iy[k] * cs - ix[k] * sn
                    bins[(r + sw).toInt()]++
                }
                var s = 0.0
                for (k in 1 until bins.size) {
                    val d = (bins[k] - bins[k - 1]).toDouble()
                    s += d * d
                }
                return s
            }
            var best = 0f
            var bestS = -1.0
            var a = -6f
            while (a <= 6.001f) {
                val sc = score(a)
                if (sc > bestS) { bestS = sc; best = a }
                a += 0.25f
            }
            if (abs(best) > 5.8f) continue // at the search limit: not a real text-line direction
            val c0 = best
            a = c0 - 0.25f
            while (a <= c0 + 0.25f) {
                val sc = score(a)
                if (sc > bestS) { bestS = sc; best = a }
                a += 0.05f
            }
            ys.add((y0 + y1) / 2f * f)
            angs.add(best)
            wts.add(n.toFloat())
        }
        if (ys.size < 2) return bm
        // robust weighted line fit: angle(y) = a0 + a1 * y
        var idx = ys.indices.toList()
        var a0 = 0f
        var a1 = 0f
        for (iter in 0 until 3) {
            var sumW = 0.0
            var sx = 0.0
            var sy = 0.0
            var sxx = 0.0
            var sxy = 0.0
            for (k in idx) {
                val wt = wts[k].toDouble()
                sumW += wt
                sx += wt * ys[k]
                sy += wt * angs[k]
                sxx += wt * ys[k] * ys[k]
                sxy += wt * ys[k] * angs[k]
            }
            val den = sumW * sxx - sx * sx
            if (abs(den) < 1e-6) {
                a1 = 0f
                a0 = (sy / sumW).toFloat()
            } else {
                a1 = ((sumW * sxy - sx * sy) / den).toFloat()
                a0 = ((sy - a1 * sx) / sumW).toFloat()
            }
            val res = idx.map { abs(angs[it] - (a0 + a1 * ys[it])) }.sorted()
            val lim = max(0.6f, 2f * res[res.size / 2])
            val keep = idx.filter { abs(angs[it] - (a0 + a1 * ys[it])) < lim }
            if (keep.size < 2 || keep.size == idx.size) break
            idx = keep
        }
        val at = a0
        val ab = a0 + a1 * h
        if (abs(at) < 0.15f && abs(ab) < 0.15f) return bm
        if (abs(at) > 7f || abs(ab) > 7f) return bm
        val tt = Math.tan(Math.toRadians(at.toDouble())).toFloat()
        val tb = Math.tan(Math.toRadians(ab.toDouble())).toFloat()
        // the page as it is: top line has slope tt, bottom line slope tb, sides stay vertical
        val sy4 = floatArrayOf(0f, w * tt, h + w * tb, h.toFloat())
        val shift = (sy4[0] + sy4[1] + sy4[2] + sy4[3]) / 4f - h / 2f
        val src = floatArrayOf(
            0f, sy4[0] - shift, w.toFloat(), sy4[1] - shift,
            w.toFloat(), sy4[2] - shift, 0f, sy4[3] - shift
        )
        val dst = floatArrayOf(0f, 0f, w.toFloat(), 0f, w.toFloat(), h.toFloat(), 0f, h.toFloat())
        val m = Matrix()
        if (!m.setPolyToPoly(src, 0, dst, 0, 4)) return bm
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.eraseColor(android.graphics.Color.WHITE)
        Canvas(out).drawBitmap(bm, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        bm.recycle()
        return out
    }

    /**
     * Removes thin strips of background (table / cloth) that remain along the borders after the crop,
     * e.g. from torn or folded page edges. Trims at most 8% per side and only when the page itself is light.
     */
    private fun trimBorders(bm: Bitmap): Bitmap {
        val w = bm.width
        val h = bm.height
        if (w < 300 || h < 300) return bm
        val f = max(1, max(w, h) / 600)
        val sw = w / f
        val sh = h / f
        val small = Bitmap.createScaledBitmap(bm, sw, sh, true)
        val px = IntArray(sw * sh)
        small.getPixels(px, 0, sw, 0, 0, sw, sh)
        if (small !== bm) small.recycle()
        val lum = IntArray(px.size) { luma(px[it]).toInt() }
        val hist = IntArray(256)
        var cnt = 0
        for (y in sh / 4 until sh * 3 / 4) {
            for (x in sw / 4 until sw * 3 / 4) {
                hist[lum[y * sw + x].coerceIn(0, 255)]++
                cnt++
            }
        }
        var acc = 0
        var ref = 255
        for (i in 0..255) {
            acc += hist[i]
            if (acc >= cnt * 0.7f) { ref = i; break }
        }
        if (ref < 110) return bm
        val thr = ref * 0.62f
        // v2.7.0: a strip is also background when it is clearly coloured compared with the page (pink folder, cloth)
        fun chromaOf(p: Int): Int {
            val r = (p shr 16) and 255
            val g = (p shr 8) and 255
            val b = p and 255
            return max(r, max(g, b)) - min(r, min(g, b))
        }
        var chromaSum = 0L
        var chromaCnt = 0
        for (y in sh / 4 until sh * 3 / 4) {
            for (x in sw / 4 until sw * 3 / 4) {
                chromaSum += chromaOf(px[y * sw + x])
                chromaCnt++
            }
        }
        val chromaLim = (if (chromaCnt > 0) (chromaSum / chromaCnt).toInt() else 0) + 30
        val bad = BooleanArray(px.size) { lum[it] < thr || chromaOf(px[it]) > chromaLim }
        val maxT = max(2, (min(sw, sh) * 0.08f).toInt())
        fun rowFrac(y: Int): Float {
            var n = 0
            for (x in 0 until sw) if (bad[y * sw + x]) n++
            return n.toFloat() / sw
        }
        fun colFrac(x: Int): Float {
            var n = 0
            for (y in 0 until sh) if (bad[y * sw + x]) n++
            return n.toFloat() / sh
        }
        var top = 0
        while (top < maxT && rowFrac(top) > 0.10f) top++
        var bottom = 0
        while (bottom < maxT && rowFrac(sh - 1 - bottom) > 0.10f) bottom++
        var left = 0
        while (left < maxT && colFrac(left) > 0.10f) left++
        var right = 0
        while (right < maxT && colFrac(sw - 1 - right) > 0.10f) right++
        if (top + bottom + left + right == 0) return bm
        if (top > 0) top++
        if (bottom > 0) bottom++
        if (left > 0) left++
        if (right > 0) right++
        val l = left * f
        val t = top * f
        val nw = w - l - right * f
        val nh = h - t - bottom * f
        if (nw < w / 2 || nh < h / 2) return bm
        val out = Bitmap.createBitmap(bm, l, t, nw, nh)
        if (out !== bm) bm.recycle()
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
        for (y in 0 until h) {
            val yMin = max(0, y - r)
            val yMax = min(h - 1, y + r)
            for (x in 0 until w) {
                val xMin = max(0, x - r)
                val xMax = min(w - 1, x + r)
                var m = 0f
                for (yy in yMin..yMax) {
                    val row = yy * w
                    for (xx in xMin..xMax) {
                        val v = a[row + xx]
                        if (v > m) m = v
                    }
                }
                out[y * w + x] = m
            }
        }
        return out
    }

    private fun boxBlurSeparable(a: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(a.size)
        val out = FloatArray(a.size)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val xMin = max(0, x - r)
                val xMax = min(w - 1, x + r)
                var sum = 0f
                var cnt = 0
                for (xx in xMin..xMax) {
                    sum += a[row + xx]
                    cnt++
                }
                tmp[row + x] = sum / cnt
            }
        }
        for (x in 0 until w) {
            for (y in 0 until h) {
                val yMin = max(0, y - r)
                val yMax = min(h - 1, y + r)
                var sum = 0f
                var cnt = 0
                for (yy in yMin..yMax) {
                    sum += tmp[yy * w + x]
                    cnt++
                }
                out[y * w + x] = sum / cnt
            }
        }
        return out
    }

    private class BgMap(val r: FloatArray, val g: FloatArray, val b: FloatArray, val sw: Int, val sh: Int)

    /**
     * Estimates the paper surface color (R, G, B) across the document.
     * Dividing by this 3-channel illumination surface removes phone shadows, color casts,
     * and fold creases cleanly while turning paper into pure neutral white (255, 255, 255).
     */
    private fun backgroundMap(src: Bitmap): BgMap {
        val maxDim = 80
        val longest = max(src.width, src.height)
        val scale = if (longest > maxDim) maxDim.toFloat() / longest else 1f
        val sw = (src.width * scale).roundToInt().coerceAtLeast(16)
        val sh = (src.height * scale).roundToInt().coerceAtLeast(16)
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

        var bgR = FloatArray(sw * sh) { ((px[it] shr 16) and 255).toFloat() }
        var bgG = FloatArray(sw * sh) { ((px[it] shr 8) and 255).toFloat() }
        var bgB = FloatArray(sw * sh) { (px[it] and 255).toFloat() }

        // Morphological closing + box blur works better than a single coarse blur; it preserves
        // the paper tone while suppressing dark text and folds.
        bgR = boxBlurSeparable(dilateF(bgR, sw, sh, 4), sw, sh, 3)
        bgG = boxBlurSeparable(dilateF(bgG, sw, sh, 4), sw, sh, 3)
        bgB = boxBlurSeparable(dilateF(bgB, sw, sh, 4), sw, sh, 3)

        // A second light pass keeps the paper white without making the whole scan too flat.
        bgR = boxBlurSeparable(bgR, sw, sh, 2)
        bgG = boxBlurSeparable(bgG, sw, sh, 2)
        bgB = boxBlurSeparable(bgB, sw, sh, 2)

        return BgMap(bgR, bgG, bgB, sw, sh)
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

    // v2.7.0 MAGIC tone curve / colour (tuned in tools/prototype/v2_7)
    private const val MAGIC_BLACK = 0.28f
    private const val MAGIC_WHITE = 0.90f
    private const val MAGIC_CHROMA = 2.8f

    private val CONTRAST_LUT = FloatArray(256) { i ->
        if (i < 200) (200f * (i / 200f).pow(1.30f)).coerceIn(0f, 255f) else i.toFloat()
    }

    private fun sharpenCh(c: Int, u: Int, d: Int, l: Int, r: Int, shift: Int, amt: Float): Int {
        val cc = (c shr shift) and 255
        val avg = (4 * cc + ((u shr shift) and 255) + ((d shr shift) and 255) +
            ((l shr shift) and 255) + ((r shr shift) and 255)) / 8f
        return (cc + amt * (cc - avg)).toInt().coerceIn(0, 255)
    }

    // ---------- adaptive shadow strength ----------

    private fun smooth01(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    private fun percentileOf(a: FloatArray, f: Float): Float {
        val c = a.copyOf()
        c.sort()
        return c[(c.size * f).toInt().coerceIn(0, c.size - 1)]
    }

    /** Paper colour (R, G, B) = 90th percentile of the illumination map. */
    private fun paperLevel(bg: BgMap): FloatArray =
        floatArrayOf(percentileOf(bg.r, 0.90f), percentileOf(bg.g, 0.90f), percentileOf(bg.b, 0.90f))

    /**
     * 0..1: how deep and how wide the shadows / uneven light on the page are, measured on the
     * illumination map (page interior only). 0 = even light, 1 = strong shadow.
     */
    private fun shadowStrength(bg: BgMap): Float {
        val n = bg.sw * bg.sh
        val lum = FloatArray(n) { bg.r[it] * 0.299f + bg.g[it] * 0.587f + bg.b[it] * 0.114f }
        val ref = percentileOf(lum, 0.90f).coerceAtLeast(30f)
        val mx = (bg.sw * 0.06f).toInt()
        val my = (bg.sh * 0.06f).toInt()
        val vals = ArrayList<Float>()
        for (yy in my until bg.sh - my) {
            for (xx in mx until bg.sw - mx) vals.add(lum[yy * bg.sw + xx] / ref)
        }
        if (vals.size < 20) return 0f
        val arr = vals.toFloatArray()
        arr.sort()
        val p10 = arr[(arr.size * 0.10f).toInt()]
        val p1 = arr[(arr.size * 0.01f).toInt()]
        val sev = 1f - (0.6f * p10 + 0.4f * p1)
        return smooth01((sev - 0.05f) / 0.20f)
    }

    // ---------- finger removal ----------

    /** Settings switch: whiten fingers that hold the page. */
    @Volatile
    var removeFingers: Boolean = true

    private class FingerMask(val a: FloatArray, val mw: Int, val mh: Int) {
        /** Bilinear alpha (0..1) at pixel (x, y) of a w x h page. */
        fun alphaAt(x: Int, y: Int, w: Int, h: Int): Float {
            val gx = ((x + 0.5f) * mw / w - 0.5f).coerceIn(0f, mw - 1.001f)
            val gy = ((y + 0.5f) * mh / h - 0.5f).coerceIn(0f, mh - 1.001f)
            val x0 = gx.toInt()
            val y0 = gy.toInt()
            val fx = gx - x0
            val fy = gy - y0
            val i = y0 * mw + x0
            val top = a[i] * (1f - fx) + a[i + 1] * fx
            val bot = a[i + mw] * (1f - fx) + a[i + mw + 1] * fx
            return top * (1f - fy) + bot * fy
        }
    }

    /** Warm hue (red..orange or pink), moderate saturation, not too dark or bright: skin of any tone. */
    private fun isSkin(p: Int): Boolean {
        val r = (p shr 16) and 255
        val g = (p shr 8) and 255
        val b = p and 255
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        if (mx < 64 || mx > 247) return false
        val d = (mx - mn).toFloat()
        val sat = d / mx
        if (sat < 0.18f || sat > 0.72f) return false
        var hue = when (mx) {
            r -> 60f * (((g - b) / d) % 6f)
            g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }
        if (hue < 0f) hue += 360f
        return hue <= 40f || hue >= 335f
    }

    private fun morph3(m: BooleanArray, w: Int, h: Int, erode: Boolean): BooleanArray {
        val out = BooleanArray(m.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var all = true
                var any = false
                for (yy in max(0, y - 1)..min(h - 1, y + 1)) {
                    for (xx in max(0, x - 1)..min(w - 1, x + 1)) {
                        if (m[yy * w + xx]) any = true else all = false
                    }
                }
                out[y * w + x] = if (erode) all else any
            }
        }
        return out
    }

    /**
     * Finds skin-coloured blobs that touch the page border (thumbs / fingers holding the sheet).
     * Thin red or brown ink (removed by the opening), stamps inside the page, and yellowed or brown
     * paper (too much skin-like area overall) are left alone.
     */
    private fun detectFingers(src: Bitmap): FingerMask? {
        val longSide = max(src.width, src.height)
        if (longSide < 64) return null
        val sc = 200f / longSide
        val mw = (src.width * sc).roundToInt().coerceAtLeast(8)
        val mh = (src.height * sc).roundToInt().coerceAtLeast(8)
        var cur = src
        while (cur.width / 2 >= mw * 2 && cur.height / 2 >= mh * 2) {
            val next = Bitmap.createScaledBitmap(cur, cur.width / 2, cur.height / 2, true)
            if (cur !== src) cur.recycle()
            cur = next
        }
        val small = Bitmap.createScaledBitmap(cur, mw, mh, true)
        if (cur !== src && cur !== small) cur.recycle()
        val px = IntArray(mw * mh)
        small.getPixels(px, 0, mw, 0, 0, mw, mh)
        if (small !== src) small.recycle()

        val total = mw * mh
        val skin = BooleanArray(total)
        var cnt = 0
        for (i in 0 until total) {
            val p = px[i]
            val r = (p shr 16) and 255
            val g = (p shr 8) and 255
            val b = p and 255
            val lum = r * 0.299f + g * 0.587f + b * 0.114f
            // Skin tone OR dark edge clamp/thumb obstruction (< 55 brightness)
            if (isSkin(p) || lum < 55f) {
                skin[i] = true
                cnt++
            }
        }
        val frac = cnt.toFloat() / total
        if (frac < 0.002f || frac > 0.30f) return null

        val opened = morph3(morph3(skin, mw, mh, true), mw, mh, false)
        val visited = BooleanArray(total)
        val queue = IntArray(total)
        val keep = BooleanArray(total)
        var any = false
        val band = max(2, (0.03f * min(mw, mh)).toInt())
        for (s0 in 0 until total) {
            if (!opened[s0] || visited[s0]) continue
            var head = 0
            var tail = 0
            queue[tail++] = s0
            visited[s0] = true
            var minX = mw
            var maxX = 0
            var minY = mh
            var maxY = 0
            while (head < tail) {
                val i = queue[head++]
                val x = i % mw
                val y = i / mw
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                if (x > 0) { val n = i - 1; if (opened[n] && !visited[n]) { visited[n] = true; queue[tail++] = n } }
                if (x < mw - 1) { val n = i + 1; if (opened[n] && !visited[n]) { visited[n] = true; queue[tail++] = n } }
                if (y > 0) { val n = i - mw; if (opened[n] && !visited[n]) { visited[n] = true; queue[tail++] = n } }
                if (y < mh - 1) { val n = i + mw; if (opened[n] && !visited[n]) { visited[n] = true; queue[tail++] = n } }
            }
            val areaFrac = tail.toFloat() / total
            if (areaFrac < 0.002f || areaFrac > 0.12f) continue
            val touches = minX <= band || minY <= band || maxX >= mw - 1 - band || maxY >= mh - 1 - band
            if (!touches) continue
            val bw = maxX - minX + 1
            val bh = maxY - minY + 1
            if (tail.toFloat() / (bw * bh) < 0.40f) continue
            for (k in 0 until tail) keep[queue[k]] = true
            any = true
        }
        if (!any) return null
        var grown = keep
        for (n in 0 until 3) grown = morph3(grown, mw, mh, false)
        var alpha = FloatArray(total) { if (grown[it]) 1f else 0f }
        alpha = boxBlurSeparable(alpha, mw, mh, 1)
        alpha = boxBlurSeparable(alpha, mw, mh, 1)
        return FingerMask(alpha, mw, mh)
    }

    /**
     * ENHANCE   : auto-levels (contrast stretch) + light sharpening, keeps colour
     * NO_SHADOW : shadow removal by dividing by the estimated paper brightness, whitens the page, keeps colour
     * MAGIC     : shadow removal + paper -> white, ink -> black (CamScanner "Magic Color")
     * BW        : shadow removal + sharpening + adaptive black/white (crisp text for printing / filing)
     */
    private fun process(src: Bitmap, mode: FilterMode): Bitmap {
        val w = src.width
        val h = src.height
        val useBg = true
        // Illumination map for every mode: it also tells how strong the shadows really are.
        val bg: BgMap = backgroundMap(src)
        val paper = paperLevel(bg)
        val paperL = (paper[0] * 0.299f + paper[1] * 0.587f + paper[2] * 0.114f).coerceAtLeast(30f)
        val strength = shadowStrength(bg)
        val fingers = if (removeFingers) detectFingers(src) else null
        var lo = 0f
        var hi = 255f
        var bwThreshold = 157.5f
        if (mode == FilterMode.ENHANCE || mode == FilterMode.BW) {
            val p = levels(src)
            lo = p.first
            hi = p.second
            if (mode == FilterMode.BW) {
                // binarisation is done per pixel from the local paper level (see BW branch);
                // the post-sharpen pass only re-smooths around mid-grey
                bwThreshold = 128f
            }
        }
        // ENHANCE works on the light-corrected image (paper = 255), so its black level is rescaled.
        val enhLo = (lo * 255f / paperL).coerceIn(0f, 160f)
        val levelScale = if (mode == FilterMode.ENHANCE) {
            255f / (255f - enhLo).coerceAtLeast(40f)
        } else {
            255f / (hi - lo).coerceAtLeast(40f)
        }
        val sw = bg.sw
        val sh = bg.sh
        val x0 = IntArray(w)
        val fx = FloatArray(w)
        if (useBg) {
            for (x in 0 until w) {
                val gx = ((x + 0.5f) * sw / w - 0.5f).coerceIn(0f, sw - 1.001f)
                x0[x] = gx.toInt()
                fx[x] = gx - x0[x]
            }
        }
        val baseSharp = when (mode) {
            FilterMode.BW -> 0.9f
            FilterMode.MAGIC -> 0.9f // v2.7.0: luminance-only sharpen (see post pass), chroma is never sharpened
            FilterMode.ENHANCE -> 0.6f
            else -> 0.5f
        }
        // deeper shadows lose more edge contrast, so sharpen a little more (not for BW)
        val sharp = if (mode == FilterMode.BW) baseSharp else baseSharp * (0.8f + 0.4f * strength)

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val rows = 32
        val inBuf = IntArray(w * (rows + 2))
        val tm = IntArray(w * (rows + 2))
        val outBuf = IntArray(w * rows)
        var y = 0
        while (y < h) {
            val n = min(rows, h - y)
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

                    var normR = r.toFloat()
                    var normG = g.toFloat()
                    var normB = b.toFloat()
                    var lumScale = 1f

                    if (useBg) {
                        val xi = x0[x]
                        val fxx = fx[x]

                        val topR = bg.r[rowA + xi] * (1 - fxx) + bg.r[rowA + xi + 1] * fxx
                        val botR = bg.r[rowB + xi] * (1 - fxx) + bg.r[rowB + xi + 1] * fxx
                        val bValR = (topR * (1 - fy) + botR * fy).coerceAtLeast(30f)

                        val topG = bg.g[rowA + xi] * (1 - fxx) + bg.g[rowA + xi + 1] * fxx
                        val botG = bg.g[rowB + xi] * (1 - fxx) + bg.g[rowB + xi + 1] * fxx
                        val bValG = (topG * (1 - fy) + botG * fy).coerceAtLeast(30f)

                        val topB = bg.b[rowA + xi] * (1 - fxx) + bg.b[rowA + xi + 1] * fxx
                        val botB = bg.b[rowB + xi] * (1 - fxx) + bg.b[rowB + xi + 1] * fxx
                        val bValB = (topB * (1 - fy) + botB * fy).coerceAtLeast(30f)

                        // Auto strength: a lighting variation below ~4% is ignored (the page is divided by one
                        // constant paper level, no halos); from ~12% it is corrected fully; in between it fades in.
                        val bL = bValR * 0.299f + bValG * 0.587f + bValB * 0.114f
                        val wt = smooth01((abs(bL / paperL - 1f) - 0.04f) / 0.08f)
                        val eR = (paper[0] + (bValR - paper[0]) * wt).coerceAtLeast(30f)
                        val eG = (paper[1] + (bValG - paper[1]) * wt).coerceAtLeast(30f)
                        val eB = (paper[2] + (bValB - paper[2]) * wt).coerceAtLeast(30f)
                        normR = min(255f, (r / eR) * 255f)
                        normG = min(255f, (g / eG) * 255f)
                        normB = min(255f, (b / eB) * 255f)
                        lumScale = 255f / (paperL + (bL - paperL) * wt).coerceAtLeast(30f)
                    }

                    val normL = normR * 0.299f + normG * 0.587f + normB * 0.114f

                    var rr: Int
                    var gg: Int
                    var bb: Int
                    when (mode) {
                        FilterMode.NO_SHADOW -> {
                            var whiteGain = 0f
                            if (normL >= 195f) {
                                val t = ((normL - 195f) / 50f).coerceIn(0f, 1f)
                                whiteGain = t * t * (3f - 2f * t)
                            }
                            var finalR = normR + (255f - normR) * whiteGain
                            var finalG = normG + (255f - normG) * whiteGain
                            var finalB = normB + (255f - normB) * whiteGain

                            if (normL < 200f) {
                                val darkL = CONTRAST_LUT[normL.toInt().coerceIn(0, 255)]
                                val gain = darkL / max(normL, 1f)
                                finalR *= gain
                                finalG *= gain
                                finalB *= gain
                            }

                            val softGamma = if (normL < 160f) 1.18f else 1.04f
                            finalR = ((finalR / 255f).pow(softGamma) * 255f).coerceIn(0f, 255f)
                            finalG = ((finalG / 255f).pow(softGamma) * 255f).coerceIn(0f, 255f)
                            finalB = ((finalB / 255f).pow(softGamma) * 255f).coerceIn(0f, 255f)

                            rr = finalR.toInt().coerceIn(0, 255)
                            gg = finalG.toInt().coerceIn(0, 255)
                            bb = finalB.toInt().coerceIn(0, 255)
                        }
                        FilterMode.BW -> {
                            // v2.6.6: looser blue/red so light ballpoint & stamps stay solid black
                            val isBlue = (normB - normR > 3f) && (normB - normG > 1.5f)
                            val isRed  = (normR - normG > 7f) && (normR - normB > 7f)
                            val inkVal = when {
                                isBlue -> min(normL, normR * 0.68f + normL * 0.32f)
                                isRed  -> min(normL, normG * 0.62f + normB * 0.38f)
                                else   -> normL
                            }
                            // Soft sigmoid keeps faint headings & thin lines
                            val tt = ((inkVal - 155f) / 75f).coerceIn(0f, 1f)
                            val v = (255f * tt * tt * (3f - 2f * tt)).toInt()
                            rr = v; gg = v; bb = v
                        }
                        FilterMode.MAGIC -> {
                            // v2.7.0: soft, continuous, colour-preserving. No hard ink / halo / paper branches:
                            // the old branches cut the anti-aliased edge of every pen stroke, which gave
                            // thick, ragged, posterised handwriting.
                            //  - luminance goes through one smooth tone curve (paper -> 1, ink deepened)
                            //  - colour = the pixel's own chroma (value - luminance), boosted, and faded
                            //    to zero towards paper, so the paper is exactly white and the ink keeps its hue
                            val l = normL / 255f
                            val t = ((l - MAGIC_BLACK) / (MAGIC_WHITE - MAGIC_BLACK)).coerceIn(0f, 1f)
                            val lOut = if (l >= MAGIC_WHITE) 1f else t.pow(1.25f)
                            val k = MAGIC_CHROMA * smooth01((0.93f - l) / 0.25f)
                            val cr = (normR - normL) / 255f
                            val cg = (normG - normL) / 255f
                            val cb = (normB - normL) / 255f
                            rr = ((lOut + cr * k) * 255f).toInt().coerceIn(0, 255)
                            gg = ((lOut + cg * k) * 255f).toInt().coerceIn(0, 255)
                            bb = ((lOut + cb * k) * 255f).toInt().coerceIn(0, 255)
                            if (lOut > 0.985f && k < 0.05f) { rr = 255; gg = 255; bb = 255 }
                        }
                        FilterMode.ENHANCE -> {
                            // colour-preserving: all three channels get the same light correction
                            val re = ((min(255f, r * lumScale) - enhLo) * levelScale).coerceIn(0f, 255f)
                            val ge = ((min(255f, g * lumScale) - enhLo) * levelScale).coerceIn(0f, 255f)
                            val be = ((min(255f, b * lumScale) - enhLo) * levelScale).coerceIn(0f, 255f)
                            val le = re * 0.299f + ge * 0.587f + be * 0.114f

                            // stronger text deepening when the capture had stronger shadows
                            val boost = if (le < 130f) 1.18f + 0.14f * strength else if (le > 210f) 0.96f else 1.08f
                            val rrF = ((re / 255f).pow(boost) * 255f).coerceIn(0f, 255f)
                            val ggF = ((ge / 255f).pow(boost) * 255f).coerceIn(0f, 255f)
                            val bbF = ((be / 255f).pow(boost) * 255f).coerceIn(0f, 255f)

                            if (le < 200f) {
                                val darkL = CONTRAST_LUT[le.toInt().coerceIn(0, 255)]
                                val gain = darkL / max(le, 1f)
                                rr = (rrF * gain).toInt().coerceIn(0, 255)
                                gg = (ggF * gain).toInt().coerceIn(0, 255)
                                bb = (bbF * gain).toInt().coerceIn(0, 255)
                            } else {
                                rr = rrF.toInt().coerceIn(0, 255)
                                gg = ggF.toInt().coerceIn(0, 255)
                                bb = bbF.toInt().coerceIn(0, 255)
                            }
                        }
                        else -> { rr = r; gg = g; bb = b }
                    }
                    if (fingers != null) {
                        val fa = fingers.alphaAt(x, ry, w, h)
                        if (fa > 0.01f) {
                            rr = (rr + (255 - rr) * fa).toInt().coerceIn(0, 255)
                            gg = (gg + (255 - gg) * fa).toInt().coerceIn(0, 255)
                            bb = (bb + (255 - bb) * fa).toInt().coerceIn(0, 255)
                        }
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
                    // v2.6.6: ultra-low sharpen on coloured ink + 4-neighbours
                    // to completely kill grey fringe under blue/red pen strokes.
                    val cr = (c shr 16) and 255
                    val cg = (c shr 8) and 255
                    val cb = c and 255
                    val cmax = max(cr, max(cg, cb))
                    val cmin = min(cr, min(cg, cb))
                    val cSat = if (cmax > 8) (cmax - cmin).toFloat() / cmax else 0f

                    fun isColouredPx(p: Int): Boolean {
                        val pr = (p shr 16) and 255
                        val pg = (p shr 8) and 255
                        val pb = p and 255
                        val pmx = max(pr, max(pg, pb))
                        val pmn = min(pr, min(pg, pb))
                        val d = pmx - pmn
                        // Looser so light-blue halo pixels also suppress sharpen
                        return pmx > 8 && d > 18 && (d.toFloat() / pmx > 0.12f)
                    }

                    val isColouredPixel = (cSat > 0.12f && (cmax - cmin) > 18) ||
                        isColouredPx(up) || isColouredPx(dn) ||
                        isColouredPx(lf) || isColouredPx(rt)

                    // 0.10x for coloured / halo neighbourhood (was 0.20)
                    // Black text and clean paper keep full sharpen
                    val amtForPixel = if (isColouredPixel) sharp * 0.10f else sharp

                    val o = (j - 1) * w + x
                    if (mode == FilterMode.MAGIC) {
                        // v2.7.0: sharpen luminance only, the same delta on R, G and B, so colour edges never get
                        // a grey fringe; the result may not overshoot the 4-neighbourhood by more than 4 levels
                        val lc = luma(c)
                        val lu = luma(up)
                        val ld = luma(dn)
                        val ll = luma(lf)
                        val lr = luma(rt)
                        val avgL = (4f * lc + lu + ld + ll + lr) / 8f
                        val hiL = max(max(lu, ld), max(max(ll, lr), lc)) + 4f
                        val loL = min(min(lu, ld), min(min(ll, lr), lc)) - 4f
                        val d = (lc + sharp * (lc - avgL)).coerceIn(loL, hiL) - lc
                        val mr = (cr + d).toInt().coerceIn(0, 255)
                        val mg = (cg + d).toInt().coerceIn(0, 255)
                        val mb = (cb + d).toInt().coerceIn(0, 255)
                        outBuf[o] = (0xFF shl 24) or (mr shl 16) or (mg shl 8) or mb
                        continue
                    }
                    val r2 = sharpenCh(c, up, dn, lf, rt, 16, amtForPixel)
                    if (mode == FilterMode.BW) {
                        val t = ((r2 - (bwThreshold - 36f)) / 72f).coerceIn(0f, 1f)
                        val v = (255f * t * t * (3f - 2f * t)).toInt()
                        outBuf[o] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                    } else {
                        val g2 = sharpenCh(c, up, dn, lf, rt, 8, amtForPixel)
                        val b2 = sharpenCh(c, up, dn, lf, rt, 0, amtForPixel)
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
        // v2.6.3: if the user has not manually rotated, auto-rotate from
        // content (text-line projection). Manual rotation always wins,
        // so pressing Rotate in Review is never undone by auto-rotate.
        bm = if (page.rotation == 0) autoRotate(bm) else rotate(bm, page.rotation)
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
