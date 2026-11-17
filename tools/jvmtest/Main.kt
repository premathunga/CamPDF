import android.graphics.*
import com.campdf.app.*
import javax.imageio.ImageIO
import java.io.File
import java.awt.BasicStroke

// usage: in.jpg outPrefix [modes comma] 
fun main(args: Array<String>) {
    fun pf(n: String, f: (Float) -> Unit) { System.getProperty(n)?.let { f(it.toFloat()) } }
    pf("thr") { Imaging.threads = it.toInt() }
    pf("mb") { Imaging.MAGIC_BLACK = it }; pf("mw") { Imaging.MAGIC_WHITE = it }; pf("mc") { Imaging.MAGIC_CHROMA = it }
    pf("cd") { Imaging.MAGIC_CDARK = it }
    pf("mr") { Imaging.MAGIC_RED = it }
    pf("mg") { Imaging.MAGIC_GAMMA = it }; pf("ms") { Imaging.MAGIC_SHARP = it }; pf("gt") { Imaging.GAIN_TARGET = it }
    System.getProperty("model")?.let { println("pagenet " + PageNet.load(java.io.File(it).readBytes())) }
    System.getProperty("bgDim")?.let { Imaging.bgDim = it.toInt() }; System.getProperty("bgDil")?.let { Imaging.bgDil = it.toInt() }
    System.getProperty("bgMode")?.let { Imaging.bgMode = it.toInt() }; System.getProperty("bgMed")?.let { Imaging.bgMed = it.toInt() }
    System.getProperty("igm")?.let { Imaging.inkGainMax = it.toFloat() }
    System.getProperty("glo")?.let { Imaging.gainLo = it.toFloat() }; System.getProperty("gsp")?.let { Imaging.gainSpan = it.toFloat() }
    System.getProperty("bwK")?.let { Imaging.bwK = it.toFloat() }; System.getProperty("bwWin")?.let { Imaging.bwWin = it.toFloat() }
        val path = args[0]; val pre = args[1]
    val modes = (args.getOrNull(2) ?: "MAGIC,BW,ENHANCE,NO_SHADOW").split(",").map { FilterMode.valueOf(it) }
    val t0 = System.currentTimeMillis()
    val c = Imaging.detectCornersOrNull(path)
    val td = System.currentTimeMillis() - t0
    val corners = System.getProperty("quad")?.split(",")?.map { it.toFloat() }?.toFloatArray() ?: c ?: Imaging.detectCorners(path)
    val src = Imaging.loadOriented(path, 3600)!!
    println("corners ${c?.joinToString { "%.3f".format(it) }} detect=${td}ms src=${src.width}x${src.height}")
    // overlay
    val bi = src.toImage(); val g = bi.createGraphics(); g.color = java.awt.Color.RED; g.stroke = BasicStroke(max(4f, src.width / 300f))
    val xs = IntArray(4) { (corners[2*it] * src.width).toInt() }; val ys = IntArray(4) { (corners[2*it+1] * src.height).toInt() }
    g.drawPolygon(xs, ys, 4); g.dispose()
    val small = java.awt.image.BufferedImage(bi.width / 4, bi.height / 4, java.awt.image.BufferedImage.TYPE_INT_RGB)
    small.createGraphics().drawImage(bi.getScaledInstance(bi.width/4, bi.height/4, java.awt.Image.SCALE_SMOOTH), 0, 0, null)
    ImageIO.write(small, "jpg", File("${pre}_quad.jpg"))
    val t1 = System.currentTimeMillis()
    val warped = Imaging.warp(src, corners, 3508)
    println("warp ${warped.width}x${warped.height} ${System.currentTimeMillis()-t1}ms")
    val wcopy = warped.copy(Bitmap.Config.ARGB_8888, true)
    for (m in modes) {
        val t2 = System.currentTimeMillis()
        val out = Imaging.applyFilter(wcopy.copy(Bitmap.Config.ARGB_8888, true), m)
        println("$m ${System.currentTimeMillis()-t2}ms")
        ImageIO.write(out.toImage(), "png", File("${pre}_$m.png"))
    }
}
fun max(a: Float, b: Float) = if (a > b) a else b
