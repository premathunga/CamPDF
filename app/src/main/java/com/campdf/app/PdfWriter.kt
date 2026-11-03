package com.campdf.app

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PdfPage(val jpeg: ByteArray, val width: Int, val height: Int)

/**
 * Minimal PDF 1.4 writer: one JPEG image per page (DCTDecode), so files stay small
 * and no external library is needed. Pages are written one at a time to keep memory low.
 */
object PdfWriter {

    private class Counting(private val o: OutputStream) : OutputStream() {
        var count = 0L
        override fun write(b: Int) { o.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { o.write(b, off, len); count += len }
        override fun flush() = o.flush()
        override fun close() = o.close()
    }

    private fun f(v: Float): String = String.format(Locale.US, "%.2f", v)

    fun write(file: File, n: Int, next: (Int) -> PdfPage) {
        require(n > 0)
        val infoObj = 3 + 3 * n
        val offsets = LongArray(infoObj + 1)
        Counting(BufferedOutputStream(FileOutputStream(file), 64 * 1024)).use { out ->
            fun w(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))

            w("%PDF-1.4\n")
            out.write(byteArrayOf('%'.code.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))

            offsets[1] = out.count
            w("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")

            val kids = (0 until n).joinToString(" ") { "${5 + 3 * it} 0 R" }
            offsets[2] = out.count
            w("2 0 obj\n<< /Type /Pages /Count $n /Kids [ $kids ] >>\nendobj\n")

            val base = 595.28f
            for (i in 0 until n) {
                val p = next(i)
                val pw: Float
                val ph: Float
                if (p.height >= p.width) {
                    pw = base; ph = base * p.height / p.width
                } else {
                    ph = base; pw = base * p.width / p.height
                }
                val img = 3 + 3 * i
                val cont = 4 + 3 * i
                val page = 5 + 3 * i

                offsets[img] = out.count
                w("$img 0 obj\n<< /Type /XObject /Subtype /Image /Width ${p.width} /Height ${p.height} " +
                    "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${p.jpeg.size} >>\nstream\n")
                out.write(p.jpeg)
                w("\nendstream\nendobj\n")

                val cs = "q ${f(pw)} 0 0 ${f(ph)} 0 0 cm /Im0 Do Q\n"
                offsets[cont] = out.count
                w("$cont 0 obj\n<< /Length ${cs.length} >>\nstream\n$cs" + "endstream\nendobj\n")

                offsets[page] = out.count
                w("$page 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${f(pw)} ${f(ph)}] " +
                    "/Resources << /XObject << /Im0 $img 0 R >> >> /Contents $cont 0 R >>\nendobj\n")
            }

            val date = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date())
            offsets[infoObj] = out.count
            w("$infoObj 0 obj\n<< /Producer (CamPDF) /Creator (CamPDF) /CreationDate (D:$date) >>\nendobj\n")

            val xrefPos = out.count
            w("xref\n0 ${infoObj + 1}\n")
            w("0000000000 65535 f \n")
            for (i in 1..infoObj) w(String.format(Locale.US, "%010d 00000 n \n", offsets[i]))
            w("trailer\n<< /Size ${infoObj + 1} /Root 1 0 R /Info $infoObj 0 R >>\nstartxref\n$xrefPos\n%%EOF\n")
        }
    }
}
