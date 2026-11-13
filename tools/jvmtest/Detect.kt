import android.graphics.*
import com.campdf.app.*
import javax.imageio.ImageIO
import java.io.File
import java.awt.BasicStroke
fun main(args: Array<String>) {
    System.getProperty("model")?.let { println("pagenet " + PageNet.load(File(it).readBytes())) }
    System.getProperty("fs")?.let { DocDetect.FUSE_S = it.toDouble() }; System.getProperty("fi")?.let { DocDetect.FUSE_IOU = it.toDouble() }
    System.getProperty("nonet")?.let { DocDetect.usePageNet = false }
    val outDir = args[0]
    for (path in args.drop(1)) {
        val t0 = System.currentTimeMillis()
        val c = Imaging.detectCornersOrNull(path)
        val dt = System.currentTimeMillis() - t0
        val corners = c ?: floatArrayOf(0f,0f,0f,0f,0f,0f,0f,0f)
        val src = Imaging.loadOriented(path, 800)!!
        val bi = src.toImage(); val g = bi.createGraphics(); g.color = if (c == null) java.awt.Color.ORANGE else java.awt.Color.RED; g.stroke = BasicStroke(4f)
        val xs = IntArray(4) { (corners[2*it] * src.width).toInt() }; val ys = IntArray(4) { (corners[2*it+1] * src.height).toInt() }
        g.drawPolygon(xs, ys, 4); g.dispose()
        val name = File(path).nameWithoutExtension
        ImageIO.write(bi, "jpg", File("$outDir/${name}_quad.jpg"))
        println("$name score=${"%.3f".format(DocDetect.lastScore)} ${dt}ms ${c == null} C ${corners.joinToString(",")}")
    }
}
