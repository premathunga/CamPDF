package com.campdf.app

import java.io.Closeable
import java.io.File

/**
 * v3.9 pages of a document, read one at a time (only the page being written / shown is in memory).
 * [box] = page width, height and image rectangle x, y, w, h in points, without loading the image.
 */
interface PageSource : Closeable {
    val count: Int
    fun box(i: Int): FloatArray?
    fun page(i: Int): PdfPage
}

/** Several sources one after the other (merge, print several documents). */
class ConcatSource(private val parts: List<PageSource>) : PageSource {
    override val count: Int = parts.sumOf { it.count }
    private fun locate(i: Int): Pair<PageSource, Int> {
        var k = i
        for (p in parts) {
            if (k < p.count) return p to k
            k -= p.count
        }
        throw IndexOutOfBoundsException("page $i")
    }
    override fun box(i: Int) = locate(i).let { (s, k) -> s.box(k) }
    override fun page(i: Int) = locate(i).let { (s, k) -> s.page(k) }
    override fun close() = parts.forEach { runCatching { it.close() } }
}

/** Chosen pages of a source, in the given order (extract, reorder). Does not close the source. */
class PickSource(private val src: PageSource, private val order: List<Int>) : PageSource {
    override val count: Int = order.size
    override fun box(i: Int) = src.box(order[i])
    override fun page(i: Int) = src.page(order[i])
    override fun close() = Unit
}

/**
 * v3.6 / v3.9 page-level access to CamPDF's own PDFs (written by [PdfWriter]): every page is one JPEG image placed
 * on the page. Reading gives back the original JPEG bytes and the exact page geometry, so merge / extract /
 * reorder rewrite the file without re-compressing anything (no quality loss) and without a PDF library.
 * Other PDFs (from other apps) return null; the app renders those with Android's PdfRenderer instead.
 * Plain Kotlin: tested on the PC (tools/jvmtest/PdfTests.kt).
 */
object PdfDoc {

    private class Info(val img: PdfFile.Obj, val w: Int, val h: Int, val box: FloatArray)

    private class CamPdfSource(private val pdf: PdfFile, private val infos: List<Info>) : PageSource {
        override val count: Int get() = infos.size
        override fun box(i: Int) = infos[i].box
        override fun page(i: Int): PdfPage {
            val inf = infos[i]
            return PdfPage(pdf.stream(inf.img), inf.w, inf.h, box = inf.box)
        }
        override fun close() = pdf.close()
    }

    private fun nums(s: String, key: String): List<Float>? =
        Regex("""/$key\s*\[([^\]]*)]""").find(s)?.groupValues?.get(1)?.trim()?.split(Regex("\\s+"))?.map { it.toFloat() }

    /** Opens a CamPDF document page by page, or null when the file is not one (other producer, encrypted, damaged). */
    fun open(file: File): PageSource? {
        val pdf = runCatching { PdfFile(file) }.getOrNull() ?: return null
        return runCatching {
            require(!pdf.trailer.contains("/Encrypt"))
            val root = pdf.obj(pdf.ref(pdf.trailer, "Root")!!)
            val pagesNode = pdf.obj(pdf.ref(root.dict, "Pages")!!)
            val kids = Regex("""/Kids\s*\[([^\]]*)]""").find(pagesNode.dict)!!.groupValues[1]
            val pageNums = Regex("""(\d+)\s+\d+\s+R""").findAll(kids).map { it.groupValues[1].toInt() }.toList()
            require(pageNums.isNotEmpty())
            val infos = pageNums.map { pn ->
                val page = pdf.obj(pn)
                require(Regex("""/Type\s*/Page\b""").containsMatchIn(page.dict))
                val mb = nums(page.dict, "MediaBox")!!
                val imgNum = Regex("""/XObject\s*<<\s*/\w+\s+(\d+)\s+\d+\s+R""").find(page.dict)!!.groupValues[1].toInt()
                val img = pdf.obj(imgNum)
                require(img.dict.contains("/DCTDecode") && img.isStream) { "not a JPEG page" }
                val w = Regex("""/Width\s+(\d+)""").find(img.dict)!!.groupValues[1].toInt()
                val h = Regex("""/Height\s+(\d+)""").find(img.dict)!!.groupValues[1].toInt()
                val cont = pdf.obj(pdf.ref(page.dict, "Contents")!!)
                val cs = String(pdf.stream(cont), Charsets.ISO_8859_1)
                val cm = Regex("""([-\d.]+)\s+[-\d.]+\s+[-\d.]+\s+([-\d.]+)\s+([-\d.]+)\s+([-\d.]+)\s+cm""").find(cs)!!.groupValues
                Info(img, w, h, floatArrayOf(mb[2] - mb[0], mb[3] - mb[1], cm[3].toFloat(), cm[4].toFloat(), cm[1].toFloat(), cm[2].toFloat()))
            }
            CamPdfSource(pdf, infos) as PageSource
        }.getOrElse {
            pdf.close()
            null
        }
    }

    /** All pages at once (small documents, tests). */
    fun readPages(file: File): List<PdfPage>? = open(file)?.use { s -> List(s.count) { s.page(it) } }

    /** True when the page fills a whole A4 sheet edge to edge (an ID copy sheet, or a rendered A4 page). */
    fun isFullA4(box: FloatArray?): Boolean {
        if (box == null) return false
        val (pw, ph) = box[0] to box[1]
        val a4 = (kotlin.math.abs(pw - 595.28f) < 2f && kotlin.math.abs(ph - 841.89f) < 2f) ||
            (kotlin.math.abs(ph - 595.28f) < 2f && kotlin.math.abs(pw - 841.89f) < 2f)
        return a4 && kotlin.math.abs(box[2]) < 1f && kotlin.math.abs(box[3]) < 1f &&
            kotlin.math.abs(box[4] - pw) < 2f && kotlin.math.abs(box[5] - ph) < 2f
    }

    /** Writes [src] to [target] page by page (one page in memory at a time). */
    fun write(target: File, src: PageSource) {
        require(src.count > 0)
        PdfWriter.write(target, src.count) { src.page(it) }
    }
}
