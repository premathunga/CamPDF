package com.campdf.app

import java.io.File

/**
 * v3.6 page-level access to CamPDF's own PDFs (written by [PdfWriter]): every page is one JPEG image placed on
 * the page. Reading gives back the original JPEG bytes and the exact page geometry, so merge / extract / reorder
 * rewrite the file without re-compressing anything (no quality loss, fast) and without a PDF library.
 *
 * Other PDFs (from other apps) return null here; the app then renders them page by page with Android's
 * PdfRenderer instead (see AppViewModel.docPages). Plain Kotlin: tested on the PC (tools/jvmtest/PdfDocTest.kt).
 */
object PdfDoc {

    private fun latin(b: ByteArray, from: Int, to: Int) = String(b, from, to - from, Charsets.ISO_8859_1)

    private fun indexOf(b: ByteArray, pat: String, from: Int): Int {
        val p = pat.toByteArray(Charsets.ISO_8859_1)
        var i = maxOf(0, from)
        val last = b.size - p.size
        outer@ while (i <= last) {
            for (j in p.indices) if (b[i + j] != p[j]) { i++; continue@outer }
            return i
        }
        return -1
    }

    private fun lastIndexOf(b: ByteArray, pat: String): Int {
        val p = pat.toByteArray(Charsets.ISO_8859_1)
        var i = b.size - p.size
        outer@ while (i >= 0) {
            for (j in p.indices) if (b[i + j] != p[j]) { i--; continue@outer }
            return i
        }
        return -1
    }

    private class Obj(val dict: String, val stream: ByteArray?)

    private fun ref(s: String, key: String): Int? =
        Regex("""/$key\s+(\d+)\s+\d+\s+R""").find(s)?.groupValues?.get(1)?.toInt()

    private fun nums(s: String, key: String): List<Float>? =
        Regex("""/$key\s*\[([^\]]*)]""").find(s)?.groupValues?.get(1)?.trim()?.split(Regex("\\s+"))?.map { it.toFloat() }

    /** Pages of a CamPDF document, or null when the file is not one (other producer, encrypted, damaged). */
    fun readPages(file: File): List<PdfPage>? = runCatching { readPages(file.readBytes()) }.getOrNull()

    fun readPages(b: ByteArray): List<PdfPage>? {
        val sx = lastIndexOf(b, "startxref")
        if (sx < 0) return null
        val xrefPos = Regex("""startxref\s+(\d+)""").find(latin(b, sx, b.size))?.groupValues?.get(1)?.toInt() ?: return null
        val tr = indexOf(b, "trailer", xrefPos)
        if (tr < 0) return null
        val trailer = latin(b, tr, sx)
        if (trailer.contains("/Encrypt")) return null
        val lines = latin(b, xrefPos, tr).lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty() || lines[0] != "xref") return null
        val offsets = HashMap<Int, Int>()
        var i = 1
        while (i < lines.size) {
            val hdr = lines[i].split(Regex("\\s+"))
            val start = hdr[0].toInt()
            val count = hdr[1].toInt()
            for (k in 0 until count) {
                val e = lines[i + 1 + k].split(Regex("\\s+"))
                if (e[2] == "n") offsets[start + k] = e[0].toInt()
            }
            i += 1 + count
        }
        val cache = HashMap<Int, Obj>()
        fun obj(num: Int): Obj {
            cache[num]?.let { return it }
            val at = offsets[num] ?: error("missing object $num")
            val head = Regex("""^\s*(\d+)\s+(\d+)\s+obj""").find(latin(b, at, minOf(b.size, at + 40))) ?: error("bad object")
            require(head.groupValues[1].toInt() == num)
            val bodyStart = at + head.range.last + 1
            val endobj = indexOf(b, "endobj", bodyStart)
            val st = indexOf(b, "stream", bodyStart)
            val o = if (st in 0 until endobj) {
                val dict = latin(b, bodyStart, st).trim()
                val len = Regex("""/Length\s+(\d+)(?!\s+\d+\s+R)""").find(dict)!!.groupValues[1].toInt()
                var ds = st + 6
                if (b[ds] == '\r'.code.toByte()) ds++
                if (b[ds] == '\n'.code.toByte()) ds++
                Obj(dict, b.copyOfRange(ds, ds + len))
            } else Obj(latin(b, bodyStart, endobj).trim(), null)
            cache[num] = o
            return o
        }
        val root = obj(ref(trailer, "Root") ?: return null)
        val pagesNode = obj(ref(root.dict, "Pages") ?: return null)
        val kids = Regex("""/Kids\s*\[([^\]]*)]""").find(pagesNode.dict)?.groupValues?.get(1) ?: return null
        val pageNums = Regex("""(\d+)\s+\d+\s+R""").findAll(kids).map { it.groupValues[1].toInt() }.toList()
        if (pageNums.isEmpty()) return null
        return pageNums.map { pn ->
            val page = obj(pn)
            require(page.dict.contains("/Type /Page") || page.dict.contains("/Type/Page"))
            val mb = nums(page.dict, "MediaBox") ?: error("no MediaBox")
            val imgNum = Regex("""/XObject\s*<<\s*/\w+\s+(\d+)\s+\d+\s+R""").find(page.dict)?.groupValues?.get(1)?.toInt()
                ?: error("no image")
            val img = obj(imgNum)
            require(img.dict.contains("/DCTDecode") && img.stream != null) { "not a JPEG page" }
            val w = Regex("""/Width\s+(\d+)""").find(img.dict)!!.groupValues[1].toInt()
            val h = Regex("""/Height\s+(\d+)""").find(img.dict)!!.groupValues[1].toInt()
            val cont = obj(ref(page.dict, "Contents") ?: error("no contents"))
            val cs = String(cont.stream!!, Charsets.ISO_8859_1)
            val cm = Regex("""([-\d.]+)\s+[-\d.]+\s+[-\d.]+\s+([-\d.]+)\s+([-\d.]+)\s+([-\d.]+)\s+cm""").find(cs)!!.groupValues
            val box = floatArrayOf(mb[2] - mb[0], mb[3] - mb[1], cm[3].toFloat(), cm[4].toFloat(), cm[1].toFloat(), cm[2].toFloat())
            PdfPage(img.stream!!, w, h, box = box)
        }
    }

    /** Page count of a CamPDF document without loading the images (null when not ours). */
    fun pageCount(file: File): Int? = readPages(file)?.size
}
