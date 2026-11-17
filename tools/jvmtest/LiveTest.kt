import android.graphics.*
import com.campdf.app.*
import java.io.File
fun check(c: Boolean, m: String) { println((if (c) "PASS " else "FAIL ") + m) }
// Phone held still over a page: real detectLive on frames with camera noise + 0.5% shake, fed to the tracker at
// 250 ms per frame (a realistic phone rate). Measures how long auto-capture takes.
fun main(a: Array<String>) {
    // unit checks
    val tr = LiveTracker()
    val q = floatArrayOf(0.2f, 0.15f, 0.8f, 0.17f, 0.82f, 0.85f, 0.18f, 0.86f)
    var st = tr.update(q, 0); check(st.quad != null && st.progress == 0f, "first frame shows outline")
    for (t in listOf(250L, 500L, 750L)) st = tr.update(FloatArray(8) { q[it] + (if (it % 3 == 0) 0.02f else -0.015f) }, t)
    check(st.progress in 0.7f..0.8f, "2% wobble keeps counting (${st.progress})")
    st = tr.update(q, 1000); check(st.ready, "steady 1 s -> capture")
    tr.reset(); tr.update(q, 0); tr.update(q, 250)
    st = tr.update(FloatArray(8) { q[it] + 0.12f }, 500); check(st.quad!![0] < 0.25f, "single far-off frame ignored")
    st = tr.update(q, 750); check(st.progress > 0.5f, "...and steadiness kept (${st.progress})")
    st = tr.update(FloatArray(8) { q[it] + 0.12f }, 1000); st = tr.update(FloatArray(8) { q[it] + 0.12f }, 1250)
    check(st.quad!![0] > 0.3f && st.progress < 0.3f, "two agreeing frames = real move, restart")
    tr.update(null, 1500); st = tr.update(null, 1750); check(st.quad != null, "2 misses keep the outline")
    st = tr.update(null, 2000); check(st.quad == null, "3 misses hide it")
    val tiny = floatArrayOf(0.45f, 0.45f, 0.6f, 0.45f, 0.6f, 0.6f, 0.45f, 0.6f)
    var t2 = 3000L; repeat(10) { st = tr.update(tiny, t2); t2 += 250 }; check(!st.ready, "small far page never captures")

    PageNet.load(File(a[0]).readBytes())
    val rnd = java.util.Random(7)
    val times = ArrayList<Long>()
    var never = 0
    for (f in a.drop(1)) {
        val b = BitmapFactory.decodeFile(f)!!
        val t = LiveTracker()
        var shot = -1L
        for (k in 0 until 16) {
            val cw = (b.width * 0.98).toInt(); val ch = (b.height * 0.98).toInt()
            val x0 = ((b.width * 0.01) + rnd.nextGaussian() * 0.005 * b.width).toInt().coerceIn(0, b.width - cw)
            val y0 = ((b.height * 0.01) + rnd.nextGaussian() * 0.005 * b.height).toInt().coerceIn(0, b.height - ch)
            val fr = Bitmap.createScaledBitmap(Bitmap.createBitmap(b, x0, y0, cw, ch), 300, 400, true)
            for (i in fr.data.indices) { val p = fr.data[i]; val n = (rnd.nextGaussian() * 4).toInt()
                fun c(v: Int) = (v + n).coerceIn(0, 255)
                fr.data[i] = (0xFF shl 24) or (c((p shr 16) and 255) shl 16) or (c((p shr 8) and 255) shl 8) or c(p and 255) }
            val s = t.update(DocDetect.detectLive(fr), k * 250L)
            if (s.ready) { shot = k * 250L; break }
        }
        if (shot < 0) { never++; println("  no capture in 4 s: ${File(f).name}") } else times.add(shot)
    }
    times.sort()
    println("still phone, ${a.size - 1} pages: captured ${times.size}, median ${times[times.size / 2]} ms, slowest ${times.last()} ms, never $never")
    System.exit(0)
}
