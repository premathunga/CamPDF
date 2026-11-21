// v3.6 PdfDoc test (PC): write CamPDF files, read pages back losslessly, rewrite merged / reordered / extracted,
// then compare renders with poppler:  pdftoppm -r 30 -png <pdf> <prefix>
import com.campdf.app.PdfDoc
import com.campdf.app.PdfPage
import com.campdf.app.PdfWriter
import java.io.File

fun main(a: Array<String>) {
    val jpgs = a.map { File(it) }
    fun page(f: File, exact: Boolean = false): PdfPage {
        val img = javax.imageio.ImageIO.read(f)
        return PdfPage(f.readBytes(), img.width, img.height, exactA4 = exact)
    }
    val p1 = listOf(page(jpgs[0]), page(jpgs[1]))
    val p2 = listOf(page(jpgs[2], exact = true))
    PdfWriter.write(File("a.pdf"), p1.size) { p1[it] }
    PdfWriter.write(File("b.pdf"), p2.size) { p2[it] }
    val ra = PdfDoc.readPages(File("a.pdf"))!!
    val rb = PdfDoc.readPages(File("b.pdf"))!!
    check(ra.size == 2 && rb.size == 1)
    check(ra[0].data.contentEquals(p1[0].data) && rb[0].data.contentEquals(p2[0].data)) { "jpeg bytes kept" }
    val merged = ra + rb
    PdfWriter.write(File("merged.pdf"), merged.size) { merged[it] }
    val rev = listOf(ra[1], ra[0])
    PdfWriter.write(File("reordered.pdf"), rev.size) { rev[it] }
    PdfWriter.write(File("extract.pdf"), 1) { ra[1] }
    val rm = PdfDoc.readPages(File("merged.pdf"))!!
    check(rm.size == 3 && rm[2].box!!.contentEquals(rb[0].box!!)) { "geometry kept through a second round" }
    println("PASS read/merge/reorder/extract; boxes: " + merged.joinToString { it.box!!.joinToString(",") })
    println("not ours -> " + PdfDoc.readPages(File("notours.pdf").apply { writeText("%PDF-1.4 hello") }))
}
