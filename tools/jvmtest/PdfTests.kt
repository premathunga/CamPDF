// v3.9 self-contained PC tests (run by GitHub Actions, see .github/workflows/build.yml):
// PdfWriter / PdfFile / PdfDoc (lossless merge, reorder, extract, exact A4), PdfCrypt (AES-256 lock) and
// LiveTracker (auto-capture timing). Test pictures are generated here - no private photos needed.
//   kotlinc PdfTests.kt <app>/PdfWriter.kt <app>/PdfFile.kt <app>/PdfDoc.kt <app>/PdfCrypt.kt <app>/LiveTracker.kt \
//       -include-runtime -d t.jar && java -jar t.jar out/
// Afterwards the workflow checks out/locked.pdf with qpdf and compares renders with poppler.
import com.campdf.app.ConcatSource
import com.campdf.app.LiveTracker
import com.campdf.app.PdfCrypt
import com.campdf.app.PdfDoc
import com.campdf.app.PdfPage
import com.campdf.app.PdfWriter
import com.campdf.app.PickSource
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.system.exitProcess

var failed = 0
fun check(ok: Boolean, what: String) { println((if (ok) "PASS " else "FAIL ") + what); if (!ok) failed++ }

fun jpeg(w: Int, h: Int, seed: Int): ByteArray {
    val im = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    val g = im.createGraphics()
    g.color = Color.WHITE; g.fillRect(0, 0, w, h)
    g.color = Color(seed * 40 % 255, 60, 200 - seed * 30 % 200)
    for (k in 0 until 12) g.fillRect(w / 10, h / 20 + k * h / 14, w * (5 + (k + seed) % 4) / 10, h / 60 + 2)
    g.drawString("page $seed", w / 10, h - h / 10)
    g.dispose()
    val o = ByteArrayOutputStream()
    ImageIO.write(im, "jpg", o)
    return o.toByteArray()
}

fun main(a: Array<String>) {
    val out = File(a.getOrElse(0) { "out" }).apply { mkdirs() }
    val p1 = PdfPage(jpeg(1240, 1754, 1), 1240, 1754)                  // A4 scan (snapped + margin)
    val p2 = PdfPage(jpeg(800, 1900, 2), 800, 1900)                    // receipt
    val p3 = PdfPage(jpeg(2067, 2923, 3), 2067, 2923, exactA4 = true)  // ID copy sheet
    val a = File(out, "a.pdf"); val b = File(out, "b.pdf")
    PdfWriter.write(a, 2) { listOf(p1, p2)[it] }
    PdfWriter.write(b, 1) { p3 }

    val sa = PdfDoc.open(a)!!; val sb = PdfDoc.open(b)!!
    check(sa.count == 2 && sb.count == 1, "open: page counts")
    check(sa.page(0).jpeg.contentEquals(p1.jpeg) && sb.page(0).jpeg.contentEquals(p3.jpeg), "open: JPEG bytes unchanged")
    check(!PdfDoc.isFullA4(sa.box(0)) && PdfDoc.isFullA4(sb.box(0)), "exact A4 sheet recognised (ID copy), margin page not")
    PdfDoc.write(File(out, "merged.pdf"), ConcatSource(listOf(PdfDoc.open(a)!!, PdfDoc.open(b)!!)))
    PdfDoc.write(File(out, "reordered.pdf"), PickSource(sa, listOf(1, 0)))
    PdfDoc.write(File(out, "extract.pdf"), PickSource(sa, listOf(1)))
    val m = PdfDoc.open(File(out, "merged.pdf"))!!
    check(m.count == 3 && m.box(2)!!.contentEquals(sb.box(0)!!) && m.page(1).jpeg.contentEquals(p2.jpeg), "merge keeps pages and geometry")
    m.close(); sa.close(); sb.close()
    check(PdfDoc.open(File(out, "nope.pdf").apply { writeText("%PDF-1.4 hello") }) == null, "other PDFs -> null")

    PdfCrypt.lock(File(out, "merged.pdf"), File(out, "locked.pdf"), "සිංහල pass 123")
    check(PdfDoc.open(File(out, "locked.pdf")) == null, "locked file is not opened without the password")

    // LiveTracker (auto-capture)
    val tr = LiveTracker()
    val q = floatArrayOf(0.2f, 0.15f, 0.8f, 0.17f, 0.82f, 0.85f, 0.18f, 0.86f)
    var st = tr.update(q, 0)
    for (t in listOf(250L, 500L, 750L)) st = tr.update(FloatArray(8) { q[it] + (if (it % 3 == 0) 0.02f else -0.015f) }, t)
    st = tr.update(q, 1000); check(st.ready, "tracker: steady 1 s -> capture")
    tr.reset(); var t3 = 0L; repeat(12) { st = tr.update(q, t3, false); t3 += 250 }
    check(st.quad != null && !st.ready, "tracker: not page-like -> never captured")

    println(if (failed == 0) "ALL PASSED" else "$failed FAILED")
    exitProcess(if (failed == 0) 0 else 1)
}
