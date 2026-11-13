package com.campdf.app

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * v3.0 page segmentation network - our own tiny U-Net, run with plain Kotlin (no ML library, no network).
 *
 * Trained offline (tools/ml/train.py) on synthetic document photos: pages on cloth, wood, walls, books, dark
 * tables, with hands, shadows, folds and blur. Input: the photo squeezed to 256x256 RGB. Output: 64x64
 * probability that each cell belongs to the page. The network answers "where is the page" robustly; the
 * straight-edge finder (LineQuad) then places the exact corners on the real page borders inside that area.
 *
 * Weights: assets/pagenet.bin, ~0.5 MB float16 (format in train.py: export()).
 * Layers (all conv 3x3, pad 1, + bias, ReLU except the head):
 *   e1: 3->16 s2, 16->16      e2: ->24 s2, 24->24     e3: ->32 s2, 32->32     e4: ->48 s2, 48->48
 *   e5: ->64 s2, 64->64, 64->64
 *   d4: up(e5)++e4 -> 48      d3: up(d4)++e3 -> 32    d2: up(d3)++e2 -> 24    head: 24 -> 1 (logit)
 */
object PageNet {

    const val IN = 256
    const val OUT = 64

    private class Layer(val cout: Int, val cin: Int, val stride: Int, val relu: Boolean, val w: FloatArray, val b: FloatArray)

    @Volatile private var layers: List<Layer>? = null

    val ready: Boolean get() = layers != null

    private fun half(h: Int): Float {
        val s = (h ushr 15) and 1
        val e = (h ushr 10) and 0x1f
        val m = h and 0x3ff
        val v = when (e) {
            0 -> m / 1024f * 6.1035156e-5f
            31 -> if (m == 0) Float.POSITIVE_INFINITY else Float.NaN
            else -> (1f + m / 1024f) * Math.scalb(1f, e - 15)
        }
        return if (s == 1) -v else v
    }

    /** Loads pagenet.bin; returns false (and stays disabled) when the data is missing or malformed. */
    fun load(bytes: ByteArray): Boolean = runCatching {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(4)
        bb.get(magic)
        require(String(magic, Charsets.US_ASCII) == "PGN2")
        val n = bb.int
        require(n in 1..64)
        val list = ArrayList<Layer>(n)
        repeat(n) {
            val cout = bb.int
            val cin = bb.int
            val st = bb.int
            val relu = bb.int != 0
            require(cout in 1..512 && cin in 1..512 && st in 1..2)
            val w = FloatArray(cout * cin * 9) { half(bb.short.toInt() and 0xffff) }
            val b = FloatArray(cout) { half(bb.short.toInt() and 0xffff) }
            list.add(Layer(cout, cin, st, relu, w, b))
        }
        require(list.size == 15)
        layers = list
        true
    }.getOrDefault(false)

    fun load(input: InputStream): Boolean = input.use { load(it.readBytes()) }

    private val pool = Executors.newFixedThreadPool(max(1, min(4, Runtime.getRuntime().availableProcessors()))) { r ->
        Thread(r, "PageNet").apply { isDaemon = true }
    }

    /** conv3x3, padding 1, given stride; x = [cin][h][w]; returns [cout][oh][ow]. Output channels run in parallel. */
    private fun conv(x: FloatArray, h: Int, w: Int, L: Layer): Triple<FloatArray, Int, Int> {
        val s = L.stride
        val oh = (h + s - 1) / s
        val ow = (w + s - 1) / s
        val out = FloatArray(L.cout * oh * ow)
        val plane = oh * ow
        val tasks = ArrayList<Callable<Unit>>()
        val nT = 4
        for (t in 0 until nT) {
            tasks.add(Callable {
                var co = t
                while (co < L.cout) {
                    val ob = co * plane
                    java.util.Arrays.fill(out, ob, ob + plane, L.b[co])
                    for (ci in 0 until L.cin) {
                        val ib = ci * h * w
                        val wb = (co * L.cin + ci) * 9
                        for (ky in 0 until 3) {
                            for (kx in 0 until 3) {
                                val k = L.w[wb + ky * 3 + kx]
                                if (k == 0f) continue
                                for (oy in 0 until oh) {
                                    val iy = oy * s + ky - 1
                                    if (iy < 0 || iy >= h) continue
                                    val irow = ib + iy * w
                                    val orow = ob + oy * ow
                                    var ox = if (kx == 0) 1 else 0 // ix = ox*s + kx - 1 must be >= 0
                                    if (kx == 0 && s == 2) ox = 1
                                    while (ox < ow) {
                                        val ix = ox * s + kx - 1
                                        if (ix >= w) break
                                        out[orow + ox] += k * x[irow + ix]
                                        ox++
                                    }
                                }
                            }
                        }
                    }
                    if (L.relu) for (i in ob until ob + plane) if (out[i] < 0f) out[i] = 0f
                    co += nT
                }
            })
        }
        pool.invokeAll(tasks).forEach { it.get() }
        return Triple(out, oh, ow)
    }

    /** nearest-neighbour x2 upsample of a, then channel concat with skip (same spatial size). */
    private fun upCat(a: FloatArray, ca: Int, h: Int, w: Int, skip: FloatArray, cs: Int): FloatArray {
        val H = h * 2
        val W = w * 2
        val out = FloatArray((ca + cs) * H * W)
        for (c in 0 until ca) {
            val ib = c * h * w
            val ob = c * H * W
            for (y in 0 until H) {
                val ir = ib + (y / 2) * w
                val orow = ob + y * W
                for (x in 0 until W) out[orow + x] = a[ir + x / 2]
            }
        }
        System.arraycopy(skip, 0, out, ca * H * W, cs * H * W)
        return out
    }

    /**
     * rgb: IN*IN packed ARGB pixels (the photo resized to 256x256, aspect ignored).
     * Returns OUT*OUT page probabilities (row-major), or null when the model is not loaded.
     */
    fun run(rgb: IntArray): FloatArray? {
        val L = layers ?: return null
        require(rgb.size == IN * IN)
        val n = IN * IN
        var x = FloatArray(3 * n)
        for (i in 0 until n) {
            val p = rgb[i]
            x[i] = ((p shr 16) and 255) / 255f
            x[n + i] = ((p shr 8) and 255) / 255f
            x[2 * n + i] = (p and 255) / 255f
        }
        var h = IN
        var w = IN
        fun step(li: Int) {
            val r = conv(x, h, w, L[li])
            x = r.first; h = r.second; w = r.third
        }
        step(0); step(1); val x1h = h
        step(2); step(3); val x2 = x; val x2h = h
        step(4); step(5); val x3 = x; val x3h = h
        step(6); step(7); val x4 = x; val x4h = h
        step(8); step(9); step(10)
        x = upCat(x, L[10].cout, h, w, x4, L[7].cout); h = x4h; w = x4h
        step(11)
        x = upCat(x, L[11].cout, h, w, x3, L[5].cout); h = x3h; w = x3h
        step(12)
        x = upCat(x, L[12].cout, h, w, x2, L[3].cout); h = x2h; w = x2h
        step(13)
        step(14)
        check(h == OUT && w == OUT && x1h == IN / 2)
        return FloatArray(OUT * OUT) { 1f / (1f + exp(-x[it])) }
    }
}
