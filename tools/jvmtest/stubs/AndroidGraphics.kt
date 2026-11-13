package android.graphics

import java.awt.image.BufferedImage
import java.io.File
import java.io.OutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageOutputStream
import kotlin.math.*

object Color { const val WHITE = -1; const val BLACK = -16777216 }

open class ColorFilter
class ColorMatrix(val a: FloatArray)
class ColorMatrixColorFilter(val cm: ColorMatrix) : ColorFilter()
class RectF(val l: Float, val t: Float, val r: Float, val b: Float)

class Paint(val flags: Int = 0) {
    constructor() : this(0)
    var colorFilter: ColorFilter? = null
    companion object { const val ANTI_ALIAS_FLAG = 1; const val FILTER_BITMAP_FLAG = 2 }
}

class Matrix {
    var m = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
    private fun mul(a: DoubleArray, b: DoubleArray): DoubleArray {
        val r = DoubleArray(9)
        for (i in 0..2) for (j in 0..2) { var s = 0.0; for (k in 0..2) s += a[i * 3 + k] * b[k * 3 + j]; r[i * 3 + j] = s }
        return r
    }
    fun postRotate(deg: Float) { val r = Math.toRadians(deg.toDouble()); val c = cos(r); val s = sin(r)
        m = mul(doubleArrayOf(c, -s, 0.0, s, c, 0.0, 0.0, 0.0, 1.0), m) }
    fun postScale(sx: Float, sy: Float) { m = mul(doubleArrayOf(sx.toDouble(), 0.0, 0.0, 0.0, sy.toDouble(), 0.0, 0.0, 0.0, 1.0), m) }
    fun postTranslate(dx: Float, dy: Float) { m = mul(doubleArrayOf(1.0, 0.0, dx.toDouble(), 0.0, 1.0, dy.toDouble(), 0.0, 0.0, 1.0), m) }
    fun setPolyToPoly(src: FloatArray, si: Int, dst: FloatArray, di: Int, n: Int): Boolean {
        if (n != 4) return false
        val A = Array(8) { DoubleArray(9) }
        for (i in 0 until 4) {
            val x = src[si + 2 * i].toDouble(); val y = src[si + 2 * i + 1].toDouble()
            val u = dst[di + 2 * i].toDouble(); val v = dst[di + 2 * i + 1].toDouble()
            A[2 * i] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y, u)
            A[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y, v)
        }
        for (c in 0 until 8) {
            var p = c; for (r in c + 1 until 8) if (abs(A[r][c]) > abs(A[p][c])) p = r
            if (abs(A[p][c]) < 1e-12) return false
            val t = A[c]; A[c] = A[p]; A[p] = t
            for (r in 0 until 8) if (r != c) { val f = A[r][c] / A[c][c]; for (k in c..8) A[r][k] -= f * A[c][k] }
        }
        val h = DoubleArray(9) { if (it < 8) A[it][8] / A[it][it] else 1.0 }
        m = h; return true
    }
    fun invert(): DoubleArray? {
        val a = m
        val det = a[0]*(a[4]*a[8]-a[5]*a[7]) - a[1]*(a[3]*a[8]-a[5]*a[6]) + a[2]*(a[3]*a[7]-a[4]*a[6])
        if (abs(det) < 1e-18) return null
        return doubleArrayOf(
            (a[4]*a[8]-a[5]*a[7])/det, (a[2]*a[7]-a[1]*a[8])/det, (a[1]*a[5]-a[2]*a[4])/det,
            (a[5]*a[6]-a[3]*a[8])/det, (a[0]*a[8]-a[2]*a[6])/det, (a[2]*a[3]-a[0]*a[5])/det,
            (a[3]*a[7]-a[4]*a[6])/det, (a[1]*a[6]-a[0]*a[7])/det, (a[0]*a[4]-a[1]*a[3])/det)
    }
    fun map(x: Double, y: Double): DoubleArray { val w = m[6]*x + m[7]*y + m[8]; return doubleArrayOf((m[0]*x+m[1]*y+m[2])/w, (m[3]*x+m[4]*y+m[5])/w) }
}

class Bitmap private constructor(val width: Int, val height: Int) {
    val data = IntArray(width * height)
    enum class Config { ARGB_8888 }
    enum class CompressFormat { JPEG, PNG }
    fun getPixels(px: IntArray, off: Int, stride: Int, x: Int, y: Int, w: Int, h: Int) {
        for (j in 0 until h) System.arraycopy(data, (y + j) * width + x, px, off + j * stride, w)
    }
    fun setPixels(px: IntArray, off: Int, stride: Int, x: Int, y: Int, w: Int, h: Int) {
        for (j in 0 until h) System.arraycopy(px, off + j * stride, data, (y + j) * width + x, w)
    }
    fun eraseColor(c: Int) { data.fill(c) }
    fun recycle() {}
    val isMutable: Boolean get() = true
    fun copy(cfg: Config, mutable: Boolean): Bitmap { val b = Bitmap(width, height); data.copyInto(b.data); return b }
    fun compress(f: CompressFormat, q: Int, out: OutputStream): Boolean {
        val bi = toImage()
        val w = ImageIO.getImageWritersByFormatName(if (f == CompressFormat.JPEG) "jpeg" else "png").next()
        val p = w.defaultWriteParam
        if (f == CompressFormat.JPEG) { p.compressionMode = ImageWriteParam.MODE_EXPLICIT; p.compressionQuality = q / 100f }
        val ios = MemoryCacheImageOutputStream(out); w.output = ios
        w.write(null, IIOImage(bi, null, null), p); ios.flush(); ios.close(); w.dispose(); return true
    }
    fun toImage(): BufferedImage { val bi = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB); bi.setRGB(0, 0, width, height, data, 0, width); return bi }
    fun sample(x: Double, y: Double): Int {
        val fx = x.coerceIn(0.0, width - 1.0); val fy = y.coerceIn(0.0, height - 1.0)
        val x0 = fx.toInt(); val y0 = fy.toInt(); val x1 = min(x0 + 1, width - 1); val y1 = min(y0 + 1, height - 1)
        val ax = fx - x0; val ay = fy - y0
        var out = 0xFF shl 24
        for (sh in intArrayOf(16, 8, 0)) {
            val a = (data[y0*width+x0] shr sh) and 255; val b = (data[y0*width+x1] shr sh) and 255
            val c = (data[y1*width+x0] shr sh) and 255; val d = (data[y1*width+x1] shr sh) and 255
            val v = (a*(1-ax)+b*ax)*(1-ay) + (c*(1-ax)+d*ax)*ay
            out = out or (v.roundToInt().coerceIn(0, 255) shl sh)
        }
        return out
    }
    companion object {
        fun createBitmap(w: Int, h: Int, c: Config): Bitmap = Bitmap(w, h)
        fun createBitmap(src: Bitmap, x: Int, y: Int, w: Int, h: Int): Bitmap {
            val b = Bitmap(w, h); for (j in 0 until h) System.arraycopy(src.data, (y + j) * src.width + x, b.data, j * w, w); return b }
        fun createBitmap(src: Bitmap, x: Int, y: Int, w: Int, h: Int, m: Matrix, filter: Boolean): Bitmap {
            var minX = 1e18; var minY = 1e18; var maxX = -1e18; var maxY = -1e18
            for ((cx, cy) in listOf(x to y, x + w to y, x to y + h, x + w to y + h)) { val p = m.map(cx.toDouble(), cy.toDouble()); minX = min(minX, p[0]); maxX = max(maxX, p[0]); minY = min(minY, p[1]); maxY = max(maxY, p[1]) }
            val ow = (maxX - minX).roundToInt(); val oh = (maxY - minY).roundToInt()
            val b = Bitmap(ow, oh); val inv = Matrix().also { it.m = m.invert()!! }
            for (j in 0 until oh) for (i in 0 until ow) { val p = inv.map(i + minX + 0.5, j + minY + 0.5); b.data[j*ow+i] = src.sample(p[0] - 0.5, p[1] - 0.5) }
            return b }
        fun createScaledBitmap(src: Bitmap, w: Int, h: Int, filter: Boolean): Bitmap {
            val b = Bitmap(w, h); val sx = src.width.toDouble() / w; val sy = src.height.toDouble() / h
            for (j in 0 until h) for (i in 0 until w) {
                if (sx > 1.0 || sy > 1.0) {
                    val x0 = (i * sx).toInt(); val x1 = max(x0 + 1, ((i + 1) * sx).toInt()).coerceAtMost(src.width)
                    val y0 = (j * sy).toInt(); val y1 = max(y0 + 1, ((j + 1) * sy).toInt()).coerceAtMost(src.height)
                    var r = 0L; var g = 0L; var bl = 0L; var n = 0
                    for (yy in y0 until y1) for (xx in x0 until x1) { val p = src.data[yy*src.width+xx]; r += (p shr 16) and 255; g += (p shr 8) and 255; bl += p and 255; n++ }
                    b.data[j*w+i] = (0xFF shl 24) or ((r/n).toInt() shl 16) or ((g/n).toInt() shl 8) or (bl/n).toInt()
                } else b.data[j*w+i] = src.sample((i + 0.5) * sx - 0.5, (j + 0.5) * sy - 0.5)
            }
            return b }
        fun fromImage(bi: BufferedImage): Bitmap { val b = Bitmap(bi.width, bi.height); bi.getRGB(0, 0, bi.width, bi.height, b.data, 0, bi.width)
            for (i in b.data.indices) b.data[i] = b.data[i] or (0xFF shl 24); return b }
    }
}

class Canvas(private val bm: Bitmap) {
    fun drawBitmap(src: Bitmap, m: Matrix, p: Paint?) {
        val inv = m.invert() ?: return
        val im = Matrix().also { it.m = inv }
        for (j in 0 until bm.height) for (i in 0 until bm.width) {
            val q = im.map(i + 0.5, j + 0.5); val sx = q[0] - 0.5; val sy = q[1] - 0.5
            if (sx < -0.5 || sy < -0.5 || sx > src.width - 0.5 || sy > src.height - 0.5) continue
            bm.data[j * bm.width + i] = src.sample(sx, sy)
        }
    }
    fun drawBitmap(src: Bitmap, left: Float, top: Float, p: Paint?) { src.data.copyInto(bm.data) }
    fun drawBitmap(src: Bitmap, srcRect: Any?, dst: RectF, p: Paint?) {
        val w = dst.r - dst.l; val h = dst.b - dst.t; if (w <= 0f || h <= 0f) return
        for (j in max(0, dst.t.toInt()) until min(bm.height, dst.b.toInt())) for (i in max(0, dst.l.toInt()) until min(bm.width, dst.r.toInt())) {
            val s = src.sample((i + 0.5 - dst.l) * src.width / w - 0.5, (j + 0.5 - dst.t) * src.height / h - 0.5); val a = ((s ushr 24) and 255) / 255.0
            if (a <= 0.0) continue; val d = bm.data[j * bm.width + i]; var o = 0xFF shl 24
            for (sh in intArrayOf(16, 8, 0)) { val v = (((s shr sh) and 255) * a + ((d shr sh) and 255) * (1 - a)).roundToInt(); o = o or (v shl sh) }
            bm.data[j * bm.width + i] = o }
    }
}

object BitmapFactory {
    class Options { var inJustDecodeBounds = false; var outWidth = 0; var outHeight = 0; var inSampleSize = 1 }
    fun decodeFile(path: String, o: Options? = null): Bitmap? {
        val bi = ImageIO.read(File(path)) ?: return null
        if (o != null && o.inJustDecodeBounds) { o.outWidth = bi.width; o.outHeight = bi.height; return null }
        val b = Bitmap.fromImage(bi)
        val s = o?.inSampleSize ?: 1
        return if (s > 1) Bitmap.createScaledBitmap(b, b.width / s, b.height / s, true) else b
    }
}
