import android.graphics.*
import com.campdf.app.*
import java.io.File
fun main(a: Array<String>) {
    val src = BitmapFactory.decodeFile(a[0])!!
    for (mode in listOf(FilterMode.MAGIC, FilterMode.BW)) for (t in listOf(1, 2)) {
        Imaging.threads = t
        val times = (0 until 4).map { val c = src.copy(Bitmap.Config.ARGB_8888, true); val t0 = System.nanoTime(); Imaging.applyFilter(c, mode); (System.nanoTime() - t0) / 1000000 }
        println("$mode threads=$t ms=$times")
    }
    System.exit(0)
}
