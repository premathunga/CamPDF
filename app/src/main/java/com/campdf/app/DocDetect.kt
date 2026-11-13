package com.campdf.app

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * v2: document edge detection.
 *  Channels : brightness (Otsu, full frame), border-colour distance (NEW),
 *             chroma, edge+flood, and bright∩border intersection (NEW).
 *  Geometry : largest component -> boundary points -> convex hull -> Visvalingam
 *             -> max-area quad -> order TL,TR,BR,BL -> angle sanity (45..135 deg)
 *             -> fill >= 0.70 -> aspect prior (Zhang-He) -> score.
 *  v2.1   : skin-tone exclusion (fingers), ink-density channel (white-on-white), edge snapping
 *           of the final quad, and detectOrNull() so the UI can warn when nothing was found.
 * Output: normalized TL, TR, BR, BL (x, y) = 8 floats.
 */
object DocDetect {

    private class Pt(val x: Float, val y: Float)
    private class Cand(val pts: FloatArray, val score: Float, val quad: List<Pt>)

    @Volatile var lastScore = 0f
    @Volatile var usePageNet = true
    @Volatile var lastProb: FloatArray? = null
    @Volatile var lastW = 0
    @Volatile var lastH = 0
    @Volatile var FUSE_S = 0.35
    @Volatile var FUSE_IOU = 5.0

    /** PageNet page probability, resampled to the w x h analysis image (null when the model is unavailable). */
    private fun pageProb(px: IntArray, w: Int, h: Int): FloatArray? {
        val n = PageNet.IN
        val inp = IntArray(n * n)
        // area-average resize to 256 x 256 (aspect ratio is not kept - the network was trained that way)
        for (y in 0 until n) {
            val y0 = y * h / n
            val y1 = max(y0 + 1, (y + 1) * h / n)
            for (x in 0 until n) {
                val x0 = x * w / n
                val x1 = max(x0 + 1, (x + 1) * w / n)
                var r = 0; var g = 0; var b = 0; var c = 0
                for (yy in y0 until min(h, y1)) for (xx in x0 until min(w, x1)) {
                    val p = px[yy * w + xx]
                    r += (p shr 16) and 255; g += (p shr 8) and 255; b += p and 255; c++
                }
                c = max(1, c)
                inp[y * n + x] = (0xFF shl 24) or ((r / c) shl 16) or ((g / c) shl 8) or (b / c)
            }
        }
        val o = PageNet.run(inp) ?: return null
        val m = PageNet.OUT
        return FloatArray(w * h) {
            val gx = ((it % w) + 0.5f) * m / w - 0.5f
            val gy = ((it / w) + 0.5f) * m / h - 0.5f
            val x0 = gx.toInt().coerceIn(0, m - 1); val y0 = gy.toInt().coerceIn(0, m - 1)
            val x1 = min(m - 1, x0 + 1); val y1 = min(m - 1, y0 + 1)
            val fx = (gx - x0).coerceIn(0f, 1f); val fy = (gy - y0).coerceIn(0f, 1f)
            (o[y0 * m + x0] * (1 - fx) + o[y0 * m + x1] * fx) * (1 - fy) + (o[y1 * m + x0] * (1 - fx) + o[y1 * m + x1] * fx) * fy
        }
    }

    /** IoU between a quad and the soft page probability, on a 64 x 64 grid. */
    private fun softIou(q: List<Pt>, prob: FloatArray, w: Int, h: Int): Float {
        var inter = 0f
        var uni = 0f
        val g = 64
        for (gy in 0 until g) for (gx in 0 until g) {
            val x = (gx + 0.5f) * w / g
            val y = (gy + 0.5f) * h / g
            var inside = true
            var sgn = 0
            for (i in 0 until 4) {
                val a = q[i]; val b = q[(i + 1) % 4]
                val c = (b.x - a.x) * (y - a.y) - (b.y - a.y) * (x - a.x)
                val s = if (c >= 0) 1 else -1
                if (sgn == 0) sgn = s else if (s != sgn) { inside = false; break }
            }
            val p = prob[min(h - 1, y.toInt()) * w + min(w - 1, x.toInt())]
            val qv = if (inside) 1f else 0f
            inter += min(qv, p)
            uni += max(qv, p)
        }
        return if (uni <= 0f) 0f else inter / uni
    }
    private const val MIN_SCORE = 0.22f

    private val FALLBACK = floatArrayOf(0.08f, 0.08f, 0.92f, 0.08f, 0.92f, 0.92f, 0.08f, 0.92f)

    /** Always returns a quad (inset fallback when detection fails). */
    fun detect(path: String): FloatArray = detectOrNull(path) ?: FALLBACK.copyOf()

    /** Returns null when no page could be found with enough confidence. */
    fun detectOrNull(path: String): FloatArray? {
        val bm = Imaging.loadOriented(path, 400) ?: return null
        val w = bm.width
        val h = bm.height
        val total = w * h
        val px = IntArray(total)
        bm.getPixels(px, 0, w, 0, 0, w, h)
        bm.recycle()
        val gray = FloatArray(total) {
            val p = px[it]
            ((p shr 16) and 255) * 0.299f + ((p shr 8) and 255) * 0.587f + (p and 255) * 0.114f
        }
        val g = gaussBlur(gray, w, h)
        val mag = sobel(g, w, h)
        val skin = skinMask(px, w, h)

        val cands = ArrayList<Cand>()
        fun tryMask(m: BooleanArray?, expand: Float, mult: Float = 1f) {
            if (m == null) return
            val c = evaluate(m, w, h, mag, expand) ?: return
            cands.add(if (mult == 1f) c else Cand(c.pts, c.score * mult, c.quad))
        }

        val bright = withoutSkin(brightMask(g, w, h), skin)
        val border = withoutSkin(borderDistMask(px, w, h, skin), skin)
        tryMask(bright, 0f)
        tryMask(border, 0f)
        if (bright != null && border != null) {
            val both = BooleanArray(total) { bright[it] && border[it] }
            tryMask(both, 0f)
        }
        tryMask(withoutSkin(chromaMask(px, g, w, h), skin), 0f)
        tryMask(edgeMask(mag, w, h), 2.5f)
        // white page on a white wall: printed/typed text density marks the page area
        tryMask(inkMask(g, w, h, skin), 0.02f * min(w, h), 0.85f)

        // v2.9.0: every candidate (mask-based ones + the straight-line finder) is judged by the same
        // edge-support score, so the quad whose four sides really sit on page edges wins.
        val lq = LineQuad(px, w, h)
        var bestQ: List<Pt>? = null
        var bestS = 0f
        for (c in cands) {
            val s = lq.score(LineQuad.Q(FloatArray(4) { c.quad[it].x }, FloatArray(4) { c.quad[it].y }))
            if (s > bestS) { bestS = s; bestQ = c.quad }
        }
        val ln = lq.detect()
        if (ln != null && ln.second > bestS) {
            bestS = ln.second
            bestQ = List(4) { Pt(ln.first.x[it], ln.first.y[it]) }
        }
        // v3.0: the page network says WHERE the page is; among the quads whose sides sit on real edges, the one
        // that matches the network's page area wins. When no edge quad matches, the network's own outline is used.
        val prob = if (PageNet.ready && usePageNet) pageProb(px, w, h) else null
        lastProb = prob; lastW = w; lastH = h
        // Four strongly supported sides that also agree with the network's page area are trusted as they are
        // (the network can be too generous on white cloth around a white page).
        val edgeTrusted = prob != null && bestQ != null && bestS >= 0.90f && softIou(bestQ, prob, w, h) >= 0.60f
        if (prob != null && !edgeTrusted) {
            val all = ArrayList<Pair<List<Pt>, Float>>()
            for (c in cands) all.add(Pair(c.quad, lq.score(LineQuad.Q(FloatArray(4) { c.quad[it].x }, FloatArray(4) { c.quad[it].y }))))
            for ((q, s) in lq.lastAll) all.add(Pair(List(4) { Pt(q.x[it], q.y[it]) }, s))
            // straight-sided quads fitted to the network's page outline (the same line finder, run on the
            // probability map); their sides are snapped onto the real image edges afterwards
            val pimg = IntArray(total) {
                val v = (prob[it] * 255f).toInt().coerceIn(0, 255)
                (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
            val lqm = LineQuad(pimg, w, h)
            lqm.detect()
            for ((q, _) in lqm.lastAll.take(6)) {
                all.add(Pair(List(4) { Pt(q.x[it], q.y[it]) }, max(0.30f, lq.score(q))))
            }
            val netMask = BooleanArray(total) { prob[it] > 0.5f }
            val netQuad = evaluate(netMask, w, h, mag, 0f)?.quad
            if (netQuad != null) all.add(Pair(netQuad, max(0.35f, lq.score(LineQuad.Q(FloatArray(4) { netQuad[it].x }, FloatArray(4) { netQuad[it].y })))))
            var bq: List<Pt>? = null
            var bs = 0f
            var bIou = 0f
            for ((q, s) in all) {
                val iou = softIou(q, prob, w, h)
                val f = Math.pow(s.toDouble().coerceAtLeast(0.05), FUSE_S).toFloat() * Math.pow(iou.toDouble(), FUSE_IOU).toFloat()
                if (f > bs) { bs = f; bq = q; bIou = iou }
            }
            if (bq != null && bIou > 0.5f) {
                bestQ = bq
                bestS = max(bIou, MIN_SCORE)
            }
        }
        lastScore = bestS
        val q = bestQ ?: return null
        if (bestS < MIN_SCORE) return null
        val snapped = refineQuad(q, mag, w, h)
        val norm = toNorm(snapped, w, h)
        return refineHiRes(path, norm) ?: norm
    }

    /**
     * v2.9.0: sub-pixel accurate sides. The quad above is found on a 400 px copy, where one pixel is ~8-10 px of a
     * camera photo. Here every side is re-fitted on a 1600 px copy: along the side the colour step is located
     * (+-14 px across the side), a robust least-squares line is fitted through those points (outliers - text, hand,
     * shadow - are dropped in 3 rounds) and the corners are the intersections of the fitted lines.
     * Returns null when the result is not trustworthy (then the coarse quad is kept).
     */
    private fun refineHiRes(path: String, c: FloatArray): FloatArray? {
        val bm = Imaging.loadOriented(path, 1600) ?: return null
        val w = bm.width
        val h = bm.height
        val px = IntArray(w * h)
        bm.getPixels(px, 0, w, 0, 0, w, h)
        bm.recycle()
        val ch = Array(3) { FloatArray(w * h) }
        for (i in px.indices) {
            val p = px[i]
            val r = ((p shr 16) and 255).toFloat()
            val g = ((p shr 8) and 255).toFloat()
            val b = (p and 255).toFloat()
            ch[0][i] = 0.299f * r + 0.587f * g + 0.114f * b
            ch[1][i] = r - g
            ch[2][i] = (r + g) * 0.5f - b
        }
        for (k in 0 until 3) ch[k] = gaussBlur(ch[k], w, h)
        fun at(k: Int, x: Float, y: Float): Float {
            val xx = x.roundToInt().coerceIn(0, w - 1)
            val yy = y.roundToInt().coerceIn(0, h - 1)
            return ch[k][yy * w + xx]
        }
        val X = FloatArray(4) { c[2 * it] * w }
        val Y = FloatArray(4) { c[2 * it + 1] * h }
        val ox = FloatArray(4)
        val oy = FloatArray(4)
        val dxs = FloatArray(4)
        val dys = FloatArray(4)
        val range = max(6, (14 * max(w, h) / 1600f).roundToInt())
        for (s in 0 until 4) {
            val x0 = X[s]; val y0 = Y[s]; val x1 = X[(s + 1) % 4]; val y1 = Y[(s + 1) % 4]
            val dx = x1 - x0
            val dy = y1 - y0
            val len = hypot(dx, dy)
            if (len < 20f) return null
            val nx = -dy / len
            val ny = dx / len
            val onBorder = (x0 < 3 && x1 < 3) || (y0 < 3 && y1 < 3) || (x0 > w - 4 && x1 > w - 4) || (y0 > h - 4 && y1 > h - 4)
            if (onBorder) {
                ox[s] = x0; oy[s] = y0; dxs[s] = dx; dys[s] = dy
                continue
            }
            val ptsX = ArrayList<Float>()
            val ptsY = ArrayList<Float>()
            val wts = ArrayList<Float>()
            val nS = 80
            for (i in 0 until nS) {
                val t = 0.06f + 0.88f * i / (nS - 1)
                val bx = x0 + dx * t
                val by = y0 + dy * t
                var bestO = 0
                var bestV = 0f
                for (o in -range..range) {
                    var v = 0f
                    for (k in 0 until 3) {
                        val a = at(k, bx + nx * (o + 2), by + ny * (o + 2)) - at(k, bx + nx * (o - 2), by + ny * (o - 2))
                        v += a * a
                    }
                    if (v > bestV) { bestV = v; bestO = o }
                }
                if (bestV < 100f) continue // < ~10 levels: no edge here
                ptsX.add(bx + nx * bestO)
                ptsY.add(by + ny * bestO)
                wts.add(sqrt(bestV))
            }
            if (ptsX.size < 12) {
                ox[s] = x0; oy[s] = y0; dxs[s] = dx; dys[s] = dy
                continue
            }
            // robust total-least-squares line fit
            var keep = ptsX.indices.toMutableList()
            var lx = 0f; var ly = 0f; var ldx = dx / len; var ldy = dy / len
            for (iter in 0 until 3) {
                var sw = 0.0; var mx = 0.0; var my = 0.0
                for (i in keep) { val wt = wts[i].toDouble(); sw += wt; mx += wt * ptsX[i]; my += wt * ptsY[i] }
                mx /= sw; my /= sw
                var sxx = 0.0; var syy = 0.0; var sxy = 0.0
                for (i in keep) {
                    val wt = wts[i].toDouble()
                    val ax = ptsX[i] - mx; val ay = ptsY[i] - my
                    sxx += wt * ax * ax; syy += wt * ay * ay; sxy += wt * ax * ay
                }
                val ang = 0.5 * kotlin.math.atan2(2 * sxy, sxx - syy)
                lx = mx.toFloat(); ly = my.toFloat()
                ldx = kotlin.math.cos(ang).toFloat(); ldy = kotlin.math.sin(ang).toFloat()
                val res = keep.map { abs((ptsX[it] - lx) * -ldy + (ptsY[it] - ly) * ldx) }
                val sorted = res.sorted()
                val lim = max(1.5f, 2.5f * sorted[sorted.size / 2])
                val nk = keep.filterIndexed { j, _ -> res[j] <= lim }
                if (nk.size < 10) break
                keep = nk.toMutableList()
            }
            // the fitted direction must stay close to the coarse side
            if (abs(ldx * (-dy / len) + ldy * (dx / len)) > 0.08f) { ox[s] = x0; oy[s] = y0; dxs[s] = dx; dys[s] = dy; continue }
            ox[s] = lx; oy[s] = ly; dxs[s] = ldx; dys[s] = ldy
        }
        val out = FloatArray(8)
        for (i in 0 until 4) {
            val j = (i + 3) % 4
            val cr = dxs[j] * dys[i] - dys[j] * dxs[i]
            if (abs(cr) < 1e-4f) return null
            val t = ((ox[i] - ox[j]) * dys[i] - (oy[i] - oy[j]) * dxs[i]) / cr
            val px2 = ox[j] + dxs[j] * t
            val py2 = oy[j] + dys[j] * t
            if (hypot(px2 - X[i], py2 - Y[i]) > 0.03f * max(w, h)) return null
            out[2 * i] = (px2 / w).coerceIn(0f, 1f)
            out[2 * i + 1] = (py2 / h).coerceIn(0f, 1f)
        }
        return out
    }

    private fun toNorm(q: List<Pt>, w: Int, h: Int): FloatArray {
        val out = FloatArray(8)
        for (i in 0 until 4) {
            out[2 * i] = ((q[i].x + 0.5f) / w).coerceIn(0f, 1f)
            out[2 * i + 1] = ((q[i].y + 0.5f) / h).coerceIn(0f, 1f)
        }
        return out
    }

    // ---------- skin (fingers) ----------

    /**
     * Heuristic skin detector (any skin tone: warm hue, clearly saturated, not yellowed paper).
     * Returns a slightly dilated mask, or null when there is little skin or so much of it that it
     * is probably a brown table rather than a finger.
     */
    private fun skinMask(px: IntArray, w: Int, h: Int): BooleanArray? {
        val total = w * h
        var n = 0
        val m = BooleanArray(total) {
            val p = px[it]
            val r = (p shr 16) and 255
            val gr = (p shr 8) and 255
            val b = p and 255
            val mx = max(r, max(gr, b))
            val mn = min(r, min(gr, b))
            val sat = if (mx > 0) (mx - mn).toFloat() / mx else 0f
            val skin = r > gr && gr.toFloat() >= b * 0.85f && r - b >= 30 &&
                sat in 0.25f..0.75f && mx in 40..250
            if (skin) n++
            skin
        }
        val frac = n.toFloat() / total
        if (frac < 0.004f || frac > 0.35f) return null
        return dilate(m, w, h, 2)
    }

    private fun withoutSkin(m: BooleanArray?, skin: BooleanArray?): BooleanArray? {
        if (m == null) return null
        if (skin == null) return m
        return BooleanArray(m.size) { m[it] && !skin[it] }
    }

    // ---------- ink density (text printed on the page) ----------

    private fun inkMask(g: FloatArray, w: Int, h: Int, skin: BooleanArray?): BooleanArray? {
        val total = w * h
        val local = boxBlurF(g, w, h, 8)
        var n = 0
        val ink = FloatArray(total) {
            val dark = local[it] - g[it] > 14f && g[it] > 25f && (skin == null || !skin[it])
            if (dark) { n++; 1f } else 0f
        }
        if (n < total * 0.004f) return null
        val dens = boxBlurF(ink, w, h, 6)
        val m = BooleanArray(total) { dens[it] > 0.03f }
        val closed = dilate(m, w, h, 8)
        val frac = closed.count { it }.toFloat() / total
        return if (frac in 0.08f..0.90f) closed else null
    }

    // ---------- edge snapping ----------

    /**
     * Slides each side of the quad up to +-8 px along its normal onto the strongest gradient ridge
     * and re-intersects neighbouring sides. Falls back to the input if the result is not sane.
     */
    private fun refineQuad(q: List<Pt>, mag: FloatArray, w: Int, h: Int): List<Pt> {
        val ox = FloatArray(4)
        val oy = FloatArray(4)
        val dxs = FloatArray(4)
        val dys = FloatArray(4)
        for (i in 0 until 4) {
            val a = q[i]
            val b = q[(i + 1) % 4]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val len = max(1e-3f, hypot(dx, dy))
            val nx = -dy / len
            val ny = dx / len
            var bestOff = 0
            var bestVal = -Float.MAX_VALUE
            for (off in -14..14) {
                var sum = 0f
                var cnt = 0
                for (k in 1..40) {
                    val t = k / 41f
                    val x = (a.x + dx * t + nx * off).roundToInt()
                    val y = (a.y + dy * t + ny * off).roundToInt()
                    if (x in 0 until w && y in 0 until h) { sum += mag[y * w + x]; cnt++ }
                }
                if (cnt < 12) continue
                val v = sum / cnt - 0.85f * abs(off)
                if (v > bestVal) { bestVal = v; bestOff = off }
            }
            ox[i] = a.x + nx * bestOff
            oy[i] = a.y + ny * bestOff
            dxs[i] = dx
            dys[i] = dy
        }
        val out = intersectSides(ox, oy, dxs, dys, q, 14f) ?: return q
        return if (anglesOk(out)) out else q
    }

    // ---------- image operators ----------

    private fun gaussBlur(a: FloatArray, w: Int, h: Int): FloatArray {
        val k = floatArrayOf(1f, 4f, 6f, 4f, 1f)
        val tmp = FloatArray(a.size)
        val out = FloatArray(a.size)
        for (y in 0 until h) {
            val r = y * w
            for (x in 0 until w) {
                var s = 0f
                for (i in -2..2) s += a[r + (x + i).coerceIn(0, w - 1)] * k[i + 2]
                tmp[r + x] = s / 16f
            }
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                var s = 0f
                for (i in -2..2) s += tmp[(y + i).coerceIn(0, h - 1) * w + x] * k[i + 2]
                out[y * w + x] = s / 16f
            }
        }
        return out
    }

    private fun sobel(g: FloatArray, w: Int, h: Int): FloatArray {
        val m = FloatArray(w * h)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val gx = -g[i - w - 1] + g[i - w + 1] - 2f * g[i - 1] + 2f * g[i + 1] - g[i + w - 1] + g[i + w + 1]
                val gy = -g[i - w - 1] - 2f * g[i - w] - g[i - w + 1] + g[i + w - 1] + 2f * g[i + w] + g[i + w + 1]
                m[i] = sqrt(gx * gx + gy * gy)
            }
        }
        return m
    }

    private fun boxBlurF(a: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(a.size)
        val out = FloatArray(a.size)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val x0 = max(0, x - r)
                val x1 = min(w - 1, x + r)
                var s = 0f
                var c = 0
                for (xx in x0..x1) { s += a[row + xx]; c++ }
                tmp[row + x] = s / c
            }
        }
        for (x in 0 until w) {
            for (y in 0 until h) {
                val y0 = max(0, y - r)
                val y1 = min(h - 1, y + r)
                var s = 0f
                var c = 0
                for (yy in y0..y1) { s += tmp[yy * w + x]; c++ }
                out[y * w + x] = s / c
            }
        }
        return out
    }

    private fun dilate(a: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        val t = BooleanArray(a.size)
        val o = BooleanArray(a.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var v = false
                var xx = max(0, x - r)
                val xe = min(w - 1, x + r)
                while (xx <= xe) { if (a[y * w + xx]) { v = true; break }; xx++ }
                t[y * w + x] = v
            }
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                var v = false
                var yy = max(0, y - r)
                val ye = min(h - 1, y + r)
                while (yy <= ye) { if (t[yy * w + x]) { v = true; break }; yy++ }
                o[y * w + x] = v
            }
        }
        return o
    }

    private fun otsu(hist: IntArray, total: Int): Int {
        var sum = 0L
        for (i in hist.indices) sum += i.toLong() * hist[i]
        var sumB = 0L
        var wB = 0
        var best = -1.0
        var thr = 128
        for (t in hist.indices) {
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
        return thr
    }

    // ---------- NEW: border-colour distance channel ----------

    /** The page usually differs from the table/cloth colour that frames the image. */
    private fun borderDistMask(px: IntArray, w: Int, h: Int, skin: BooleanArray?): BooleanArray? {
        val total = w * h
        val m = max(2, (min(w, h) * 0.04f).toInt())
        val rs = ArrayList<Int>(4096)
        val gs = ArrayList<Int>(4096)
        val bs = ArrayList<Int>(4096)
        // sample the frame ring of width m (stride 2 keeps it cheap)
        for (y in 0 until h step 2) {
            val inBandY = y < m || y >= h - m
            var x = 0
            while (x < w) {
                if (inBandY || x < m || x >= w - m) {
                    if (skin == null || !skin[y * w + x]) {
                        val p = px[y * w + x]
                        rs.add((p shr 16) and 255)
                        gs.add((p shr 8) and 255)
                        bs.add(p and 255)
                    }
                    x += 2
                } else {
                    // jump straight to the right-hand band
                    x = max(x + 1, w - m)
                }
            }
        }
        if (rs.size < 16) return null
        fun med(s: ArrayList<Int>): Float {
            val a = s.toIntArray()
            a.sort()
            return a[a.size / 2].toFloat()
        }
        val mr = med(rs)
        val mg = med(gs)
        val mb = med(bs)
        val d = FloatArray(total) {
            val p = px[it]
            abs(((p shr 16) and 255) - mr) + abs(((p shr 8) and 255) - mg) + abs((p and 255) - mb)
        }
        val db = boxBlurF(d, w, h, 3)
        val hist = IntArray(256)
        for (v in db) hist[(v / 3f).toInt().coerceIn(0, 255)]++
        val thr = otsu(hist, total) * 3f
        var inSum = 0.0
        var inN = 0
        var outSum = 0.0
        var outN = 0
        val mask = BooleanArray(total) {
            val v = db[it] > thr
            if (v) { inSum += db[it]; inN++ } else { outSum += db[it]; outN++ }
            v
        }
        if (inN == 0 || outN == 0) return null
        // reject when the "difference" is just sensor noise
        if (inSum / inN - outSum / outN < 18.0) return null
        val frac = inN.toFloat() / total
        return if (frac in 0.08f..0.96f) mask else null
    }

    // ---------- segmentation (fixed bright + chroma + edge) ----------

    /** Otsu over the FULL frame (R2 fix): the page may touch the borders. */
    private fun brightMask(g: FloatArray, w: Int, h: Int): BooleanArray? {
        val total = w * h
        val hist = IntArray(256)
        for (v in g) hist[v.toInt().coerceIn(0, 255)]++
        val thr = otsu(hist, total).coerceIn(80, 205)
        val mask = BooleanArray(total) { g[it] > thr }
        val frac = mask.count { it }.toFloat() / total
        return if (frac in 0.08f..0.96f) mask else null
    }

    private fun chromaMask(px: IntArray, g: FloatArray, w: Int, h: Int): BooleanArray? {
        val total = w * h
        var count = 0
        val mask = BooleanArray(total) { i ->
            val p = px[i]
            val r = (p shr 16) and 255
            val gr = (p shr 8) and 255
            val b = p and 255
            val maxC = max(r, max(gr, b))
            val minC = min(r, min(gr, b))
            val sat = if (maxC > 0) (maxC - minC).toFloat() / maxC else 0f
            val isPaper = sat < 0.38f && g[i] > 48f
            if (isPaper) count++
            isPaper
        }
        val frac = count.toFloat() / total
        return if (frac in 0.08f..0.97f) mask else null
    }

    private fun edgeMask(mag: FloatArray, w: Int, h: Int): BooleanArray? {
        val total = w * h
        val hist = IntArray(1024)
        for (m in mag) hist[m.toInt().coerceIn(0, 1023)]++
        val target = (total * 0.85f).toInt()
        var acc = 0
        var p85 = 0
        for (i in 0 until 1024) {
            acc += hist[i]
            if (acc >= target) { p85 = i; break }
        }
        val thr = p85.toFloat().coerceIn(25f, 140f)
        val e = BooleanArray(total) { mag[it] > thr }
        val ed = dilate(e, w, h, 2)

        val outside = BooleanArray(total)
        val queue = IntArray(total)
        var head = 0
        var tail = 0
        fun seed(i: Int) {
            if (!ed[i] && !outside[i]) {
                outside[i] = true
                queue[tail++] = i
            }
        }
        for (x in 0 until w) { seed(x); seed((h - 1) * w + x) }
        for (y in 0 until h) { seed(y * w); seed(y * w + w - 1) }
        while (head < tail) {
            val i = queue[head++]
            val x = i % w
            val y = i / w
            if (x > 0) seed(i - 1)
            if (x < w - 1) seed(i + 1)
            if (y > 0) seed(i - w)
            if (y < h - 1) seed(i + w)
        }
        val mask = BooleanArray(total) { !ed[it] && !outside[it] }
        val frac = mask.count { it }.toFloat() / total
        return if (frac in 0.08f..0.96f) mask else null
    }

    private fun largestComponent(mask: BooleanArray, w: Int, h: Int): IntArray? {
        val total = w * h
        val visited = BooleanArray(total)
        val queue = IntArray(total)
        var best: IntArray? = null
        var bestSize = 0
        for (s in 0 until total) {
            if (!mask[s] || visited[s]) continue
            var head = 0
            var tail = 0
            queue[tail++] = s
            visited[s] = true
            while (head < tail) {
                val i = queue[head++]
                val x = i % w
                val y = i / w
                if (x > 0) { val n = i - 1; if (mask[n] && !visited[n]) { visited[n] = true; queue[tail++] = n } }
                if (x < w - 1) { val n = i + 1; if (mask[n] && !visited[n]) { visited[n] = true; queue[tail++] = n } }
                if (y > 0) { val n = i - w; if (mask[n] && !visited[n]) { visited[n] = true; queue[tail++] = n } }
                if (y < h - 1) { val n = i + w; if (mask[n] && !visited[n]) { visited[n] = true; queue[tail++] = n } }
            }
            if (tail > bestSize) {
                bestSize = tail
                best = queue.copyOf(tail)
            }
        }
        return best
    }

    // ---------- geometry ----------

    private fun cross(a: Pt, b: Pt, c: Pt): Float =
        (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)

    private fun convexHull(points: List<Pt>): List<Pt> {
        val s = points.sortedWith(compareBy({ it.x }, { it.y }))
        val lower = ArrayList<Pt>()
        for (p in s) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], p) <= 0f) lower.removeAt(lower.size - 1)
            lower.add(p)
        }
        val upper = ArrayList<Pt>()
        for (p in s.asReversed()) {
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], p) <= 0f) upper.removeAt(upper.size - 1)
            upper.add(p)
        }
        if (lower.isNotEmpty()) lower.removeAt(lower.size - 1)
        if (upper.isNotEmpty()) upper.removeAt(upper.size - 1)
        return lower + upper
    }

    private fun polyArea(p: List<Pt>): Float {
        var a = 0f
        for (i in p.indices) {
            val q = p[i]
            val r = p[(i + 1) % p.size]
            a += q.x * r.y - r.x * q.y
        }
        return abs(a) / 2f
    }

    private fun simplify(hull: List<Pt>, maxN: Int): List<Pt> {
        val p = ArrayList(hull)
        while (p.size > maxN) {
            var bi = 0
            var ba = Float.MAX_VALUE
            for (i in p.indices) {
                val a = p[(i + p.size - 1) % p.size]
                val b = p[i]
                val c = p[(i + 1) % p.size]
                val ar = abs(cross(a, b, c))
                if (ar < ba) { ba = ar; bi = i }
            }
            p.removeAt(bi)
        }
        return p
    }

    private fun quadArea(a: Pt, b: Pt, c: Pt, d: Pt): Float =
        0.5f * abs((a.x * b.y - b.x * a.y) + (b.x * c.y - c.x * b.y) +
            (c.x * d.y - d.x * c.y) + (d.x * a.y - a.x * d.y))

    private fun bestQuad(poly: List<Pt>): List<Pt>? {
        val n = poly.size
        if (n < 4) return null
        if (n == 4) return poly
        var best = -1f
        var ia = 0
        var ib = 1
        var ic = 2
        var id = 3
        for (a in 0 until n - 3) {
            for (b in a + 1 until n - 2) {
                for (c in b + 1 until n - 1) {
                    for (d in c + 1 until n) {
                        val ar = quadArea(poly[a], poly[b], poly[c], poly[d])
                        if (ar > best) { best = ar; ia = a; ib = b; ic = c; id = d }
                    }
                }
            }
        }
        return listOf(poly[ia], poly[ib], poly[ic], poly[id])
    }

    /** TL, TR, BR, BL */
    private fun orderCorners(q: List<Pt>): List<Pt> {
        var cx = 0f
        var cy = 0f
        for (p in q) { cx += p.x; cy += p.y }
        cx /= q.size
        cy /= q.size
        val sorted = q.sortedBy { atan2(it.y - cy, it.x - cx) }
        var start = 0
        var bs = Float.MAX_VALUE
        for (i in sorted.indices) {
            val s = sorted[i].x + sorted[i].y
            if (s < bs) { bs = s; start = i }
        }
        return List(4) { sorted[(start + it) % 4] }
    }

    /** R6 fix: reject quadrilaterals with impossible interior angles (45..135 deg). */
    private fun anglesOk(c: List<Pt>): Boolean {
        for (i in 0 until 4) {
            val a = c[(i + 3) % 4]
            val b = c[i]
            val d = c[(i + 1) % 4]
            val v1x = (a.x - b.x).toDouble()
            val v1y = (a.y - b.y).toDouble()
            val v2x = (d.x - b.x).toDouble()
            val v2y = (d.y - b.y).toDouble()
            val dot = v1x * v2x + v1y * v2y
            val crs = abs(v1x * v2y - v1y * v2x)
            // angle between the two edges meeting at b == interior angle of the convex quad
            val interior = Math.toDegrees(atan2(crs, dot))
            if (interior < 45.0 || interior > 135.0) return false
        }
        return true
    }

    /** Physical aspect ratio (w/h) of the rectangle behind the quad (Zhang-He). */
    private fun estAspect(p: List<Pt>, imgW: Int, imgH: Int): Double? {
        fun d3(a: Pt) = doubleArrayOf(a.x.toDouble(), a.y.toDouble(), 1.0)
        fun cr(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
            a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0]
        )
        fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
        val m1 = d3(p[0])
        val m2 = d3(p[1])
        val m3 = d3(p[3])
        val m4 = d3(p[2])
        val k2d = dot(cr(m2, m4), m3)
        val k3d = dot(cr(m3, m4), m2)
        if (abs(k2d) < 1e-9 || abs(k3d) < 1e-9) return null
        val c14 = cr(m1, m4)
        val k2 = dot(c14, m3) / k2d
        val k3 = dot(c14, m2) / k3d
        val n2 = doubleArrayOf(k2 * m2[0] - m1[0], k2 * m2[1] - m1[1], k2 - 1.0)
        val n3 = doubleArrayOf(k3 * m3[0] - m1[0], k3 * m3[1] - m1[1], k3 - 1.0)
        val u0 = imgW / 2.0
        val v0 = imgH / 2.0
        if (abs(n2[2] * n3[2]) < 1e-6) return null
        val f2 = -1.0 / (n2[2] * n3[2]) *
            ((n2[0] * n3[0] - (n2[0] * n3[2] + n2[2] * n3[0]) * u0 + n2[2] * n3[2] * u0 * u0) +
                (n2[1] * n3[1] - (n2[1] * n3[2] + n2[2] * n3[1]) * v0 + n2[2] * n3[2] * v0 * v0))
        if (f2.isNaN() || f2 <= 0.0) return null
        val f = sqrt(f2)
        fun norm2(n: DoubleArray): Double {
            val a = (n[0] - u0 * n[2]) / f
            val b = (n[1] - v0 * n[2]) / f
            return a * a + b * b + n[2] * n[2]
        }
        val d3v = norm2(n3)
        if (d3v <= 0.0) return null
        val r = sqrt(norm2(n2) / d3v)
        return if (r.isFinite() && r in 0.25..4.0) r else null
    }

    private fun edgeScore(c: List<Pt>, mag: FloatArray, w: Int, h: Int): Float {
        var sum = 0f
        var cnt = 0
        for (s in 0 until 4) {
            val a = c[s]
            val b = c[(s + 1) % 4]
            for (k in 1..24) {
                val t = k / 25f
                val x = (a.x + (b.x - a.x) * t).roundToInt()
                val y = (a.y + (b.y - a.y) * t).roundToInt()
                var m = 0f
                for (dy in -2..2) {
                    for (dx in -2..2) {
                        val xx = x + dx
                        val yy = y + dy
                        if (xx in 0 until w && yy in 0 until h) {
                            val v = mag[yy * w + xx]
                            if (v > m) m = v
                        }
                    }
                }
                sum += m
                cnt++
            }
        }
        return min(1f, (sum / cnt) / 120f)
    }

    /** Corner i = intersection of side i-1 and side i (each given as point + direction). */
    private fun intersectSides(
        ox: FloatArray, oy: FloatArray, dxs: FloatArray, dys: FloatArray,
        orig: List<Pt>, maxMove: Float
    ): List<Pt>? {
        val out = ArrayList<Pt>(4)
        for (i in 0 until 4) {
            val j = (i + 3) % 4
            val cr = dxs[j] * dys[i] - dys[j] * dxs[i]
            if (abs(cr) < 1e-3f) return null
            val t = ((ox[i] - ox[j]) * dys[i] - (oy[i] - oy[j]) * dxs[i]) / cr
            val p = Pt(ox[j] + dxs[j] * t, oy[j] + dys[j] * t)
            if (hypot(p.x - orig[i].x, p.y - orig[i].y) > maxMove) return null
            out.add(p)
        }
        return out
    }

    /**
     * Pushes every side outward until it touches the convex hull of the page region (at most `cap` px),
     * so a page whose edge bulges/curls between the corners is not cut off.
     */
    private fun circumscribe(q: List<Pt>, hull: List<Pt>, cap: Float): List<Pt> {
        var cx = 0f
        var cy = 0f
        for (p in q) { cx += p.x; cy += p.y }
        cx /= 4f
        cy /= 4f
        val ox = FloatArray(4)
        val oy = FloatArray(4)
        val dxs = FloatArray(4)
        val dys = FloatArray(4)
        for (i in 0 until 4) {
            val a = q[i]
            val b = q[(i + 1) % 4]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val len = max(1e-3f, hypot(dx, dy))
            var nx = -dy / len
            var ny = dx / len
            if (nx * ((a.x + b.x) / 2f - cx) + ny * ((a.y + b.y) / 2f - cy) < 0f) { nx = -nx; ny = -ny }
            var far = 0f
            for (p in hull) {
                val d = (p.x - a.x) * nx + (p.y - a.y) * ny
                if (d > far) far = d
            }
            val push = min(far, cap)
            ox[i] = a.x + nx * push
            oy[i] = a.y + ny * push
            dxs[i] = dx
            dys[i] = dy
        }
        return intersectSides(ox, oy, dxs, dys, q, cap * 3f) ?: q
    }

    private fun evaluate(mask: BooleanArray, w: Int, h: Int, mag: FloatArray, expand: Float): Cand? {
        val comp = largestComponent(mask, w, h) ?: return null
        val total = w * h
        val areaFrac = comp.size.toFloat() / total
        if (areaFrac < 0.08f || areaFrac > 0.985f) return null

        // boundary points via erosion ring (R5 fix: robust against row-spanning slivers)
        val compMask = BooleanArray(total)
        for (i in comp) compMask[i] = true
        val er = BooleanArray(total)
        for (i in comp) {
            val x = i % w
            val y = i / w
            if (x > 0 && compMask[i - 1] && x < w - 1 && compMask[i + 1] &&
                y > 0 && compMask[i - w] && y < h - 1 && compMask[i + w]) continue
            er[i] = true
        }
        val pts = ArrayList<Pt>(comp.size / 4 + 16)
        var cxAcc = 0f
        var cyAcc = 0f
        for (i in comp) {
            cxAcc += (i % w)
            cyAcc += (i / w)
            if (er[i]) pts.add(Pt((i % w).toFloat(), (i / w).toFloat()))
        }
        if (pts.size < 8) return null

        val centroidX = cxAcc / comp.size
        val centroidY = cyAcc / comp.size
        val centerBias = 1f - min(
            1f,
            (abs(centroidX - w / 2f) / (w * 0.35f)) + (abs(centroidY - h / 2f) / (h * 0.35f))
        )

        val hull = convexHull(pts)
        if (hull.size < 4) return null
        val hullArea = polyArea(hull)
        if (hullArea < 1f) return null
        val fill = min(1f, comp.size / hullArea)
        if (fill < 0.70f) return null

        val quad = bestQuad(simplify(hull, 28)) ?: return null
        val ordered = circumscribe(orderCorners(quad), hull, 0.03f * min(w, h))
        if (!anglesOk(ordered)) return null

        var cx = 0f
        var cy = 0f
        for (p in ordered) { cx += p.x; cy += p.y }
        cx /= 4f
        cy /= 4f
        val ex = ordered.map { p ->
            val dx = p.x - cx
            val dy = p.y - cy
            val len = max(1f, sqrt(dx * dx + dy * dy))
            Pt(p.x + dx / len * expand, p.y + dy / len * expand)
        }
        val minSide = 0.10f * min(w, h)
        for (i in 0 until 4) {
            val a = ex[i]
            val b = ex[(i + 1) % 4]
            if (hypot(a.x - b.x, a.y - b.y) < minSide) return null
        }

        val es = edgeScore(ex, mag, w, h)
        val wTop = hypot(ex[1].x - ex[0].x, ex[1].y - ex[0].y)
        val wBot = hypot(ex[2].x - ex[3].x, ex[2].y - ex[3].y)
        val hLeft = hypot(ex[3].x - ex[0].x, ex[3].y - ex[0].y)
        val hRight = hypot(ex[2].x - ex[1].x, ex[2].y - ex[1].y)
        val wRatio = min(wTop, wBot) / max(wTop, wBot).coerceAtLeast(1e-4f)
        val hRatio = min(hLeft, hRight) / max(hLeft, hRight).coerceAtLeast(1e-4f)
        val opposingRatio = min(wRatio, hRatio)
        val symmetryScore = when {
            opposingRatio >= 0.70f -> 1f
            opposingRatio < 0.50f -> 0.1f
            else -> (opposingRatio - 0.50f) / 0.20f
        }
        val qw = (wTop + wBot) / 2f
        val qh = (hLeft + hRight) / 2f
        val ratio = qw / qh.coerceAtLeast(1f)
        val aspectScore = when {
            ratio in 0.35f..2.8f -> 1f
            ratio < 0.35f -> (ratio / 0.35f).coerceIn(0f, 1f)
            else -> (2.8f / ratio).coerceIn(0f, 1f)
        }
        val shapeScore = aspectScore * symmetryScore

        // physical page prior: books/A4/receipts are ~0.55..1.65 w/h
        val phys = estAspect(ex, w, h)
        val aspectPrior = when {
            phys == null -> 0.5f
            phys in 0.55..1.65 -> 1f
            phys in 0.35..2.4 -> 0.4f
            else -> 0.1f
        }

        // fill and size dominate; edge strength is a tie-breaker, not a gate (R4 fix)
        var score = 0.30f * fill + 0.24f * min(1f, areaFrac / 0.55f) +
            0.15f * es + 0.13f * centerBias + 0.12f * aspectPrior + 0.06f * shapeScore

        var onFrame = 0
        for (p in ex) {
            if (p.x <= 0.015f * w || p.y <= 0.015f * h || p.x >= 0.985f * w || p.y >= 0.985f * h) onFrame++
        }
        score *= 0.85f.pow(onFrame)

        return Cand(toNorm(ex, w, h), score, ex)
    }
}
