import android.graphics.*
import com.campdf.app.*
import java.io.File
fun check(c: Boolean, m: String) { println((if (c) "PASS " else "FAIL ") + m); if (!c) System.exit(1) }
fun main(a: Array<String>) {
    // --- LiveTracker logic ---
    val tr = LiveTracker()
    val q = floatArrayOf(0.2f, 0.15f, 0.8f, 0.17f, 0.82f, 0.85f, 0.18f, 0.86f)
    var st = tr.update(q, 0); check(st.progress == 0f && st.quad != null, "first frame shows outline, no progress")
    for (t in listOf(300L, 600L, 900L)) st = tr.update(FloatArray(8) { q[it] + (if (it % 3 == 0) 0.008f else -0.006f) }, t)
    check(st.progress in 0.7f..0.8f, "small jitter keeps counting (${st.progress})")
    st = tr.update(q, 1250); check(st.ready, "steady 1.25 s -> capture")
    st = tr.update(FloatArray(8) { q[it] + 0.05f }, 1400); check(st.progress == 0f, "moving restarts")
    st = tr.update(null, 1500); check(st.quad != null && st.progress == 0f, "one missed frame keeps the outline")
    tr.update(null, 1600); st = tr.update(null, 1700); check(st.quad == null, "3 missed frames hide the outline")
    val tiny = floatArrayOf(0.45f, 0.45f, 0.6f, 0.45f, 0.6f, 0.6f, 0.45f, 0.6f)
    var t2 = 2000L; repeat(10) { st = tr.update(tiny, t2); t2 += 300 }
    check(!st.ready, "small page far away never auto-captures")
    check(!LiveTracker.plausible(floatArrayOf(0f,0f,1f,1f,1f,0f,0f,1f)), "crossed quad rejected")
    // --- live detection on preview-sized frames ---
    a.getOrNull(0)?.let { PageNet.load(File(it).readBytes()) }
    val files = a.drop(1)
    val times = ArrayList<Long>()
    var found = 0
    for (f in files) {
        val full = BitmapFactory.decodeFile(f)!!
        val frame = Bitmap.createScaledBitmap(full, if (full.width < full.height) 480 else 640, if (full.width < full.height) 640 else 480, true)
        for (rep in 0 until 2) {
            val t0 = System.nanoTime(); val r = DocDetect.detectBitmap(frame); val dt = (System.nanoTime() - t0) / 1000000
            if (rep == 1) { times.add(dt); if (r != null) found++ }
        }
    }
    times.sort()
    println("live frames: ${files.size}, page found in $found, median ${times[times.size / 2]} ms, max ${times.last()} ms")
    System.exit(0)
}
