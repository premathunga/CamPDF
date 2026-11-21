package com.campdf.app

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.Locale

/**
 * exactA4 (v3.5): the image already is a full A4 sheet at true scale (ID card copy) - no snapping margin.
 * box (v3.6): exact page geometry read back from an existing CamPDF file (PdfDoc) - page width, page height and the
 * image rectangle x, y, w, h in points - so merged / extracted / reordered pages keep their exact layout.
 */
class PdfPage(
    /** The image data: JPEG bytes, or for [bitonal] pages zlib-compressed 1-bit rows (see Bitonal). */
    val data: ByteArray,
    val width: Int,
    val height: Int,
    val exactA4: Boolean = false,
    val box: FloatArray? = null,
    /** v3.10: a black-and-white page stored with 1 bit per pixel (about 12x smaller than JPEG). */
    val bitonal: Boolean = false,
    /** v3.10 MRC: sharp black text layer (zlib 1-bit rows, 0 = ink) drawn over the image, [maskW] x [maskH]. */
    val mask: ByteArray? = null,
    val maskW: Int = 0,
    val maskH: Int = 0
)

/**
 * Minimal PDF 1.4 writer: one image per page - a JPEG (DCTDecode) or, for black-and-white pages, 1-bit FlateDecode -
 * so files stay small
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
        // v3.10: object numbers are given out while writing (a page has 3 objects, or 4 with an MRC text layer);
        // the page tree (object 2) is written at the end, when all page numbers are known
        val offsets = HashMap<Int, Long>()
        var nextObj = 3
        val pageObjs = ArrayList<Int>()
        val (pdfDate, xmpDate) = PdfA.dates()
        Counting(BufferedOutputStream(FileOutputStream(file), 64 * 1024)).use { out ->
            fun w(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))

            w("%PDF-1.4\n")
            out.write(byteArrayOf('%'.code.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))


            val a4W = 595.28f
            val a4H = 841.89f
            for (i in 0 until n) {
                val p = next(i)
                val pw: Float
                val ph: Float
                val isPortrait = p.height >= p.width
                val ratio = if (isPortrait) p.height.toFloat() / p.width.coerceAtLeast(1)
                            else p.width.toFloat() / p.height.coerceAtLeast(1)
                // Snap pages within standard A4 aspect tolerance (~6% of 1.414) to standard ISO A4 points.
                // Arbitrary documents (receipts, cards) keep their proportional bounds.
                if (p.box != null) {
                    pw = p.box[0]
                    ph = p.box[1]
                } else if (p.exactA4 || ratio in 1.28f..1.56f) {
                    if (isPortrait) {
                        pw = a4W
                        ph = a4H
                    } else {
                        pw = a4H
                        ph = a4W
                    }
                } else {
                    if (isPortrait) {
                        pw = a4W
                        ph = a4W * p.height / p.width.coerceAtLeast(1)
                    } else {
                        ph = a4W
                        pw = a4W * p.width / p.height.coerceAtLeast(1)
                    }
                }
                val img = nextObj++
                val maskObj = if (p.mask != null) nextObj++ else -1
                val cont = nextObj++
                val page = nextObj++
                pageObjs.add(page)

                offsets[img] = out.count
                w("$img 0 obj\n<< /Type /XObject /Subtype /Image /Width ${p.width} /Height ${p.height} " +
                    (if (p.bitonal) "/ColorSpace /DeviceGray /BitsPerComponent 1 /Filter /FlateDecode"
                    else "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode") +
                    " /Length ${p.data.size} >>\nstream\n")
                out.write(p.data)
                w("\nendstream\nendobj\n")
                if (p.mask != null) {
                    // MRC text layer: a stencil mask, painted in black where the sample is 0 (ink)
                    offsets[maskObj] = out.count
                    w("$maskObj 0 obj\n<< /Type /XObject /Subtype /Image /Width ${p.maskW} /Height ${p.maskH} " +
                        "/ImageMask true /BitsPerComponent 1 /Filter /FlateDecode /Length ${p.mask.size} >>\nstream\n")
                    out.write(p.mask)
                    w("\nendstream\nendobj\n")
                }

                // Fit the image inside the page without distortion (the A4 snap can differ from the
                // scan's real proportions by a few percent -> letterbox instead of stretching).
                // A subtle safe margin (~12 pt / 4mm) on standard A4 pages prevents physical printers
                // from cutting off document header/footer text.
                val margin = if (p.box == null && !p.exactA4 && ratio in 1.28f..1.56f) 12f else 0f
                val targetW = pw - margin * 2f
                val targetH = ph - margin * 2f
                val imgAspect = p.width.toFloat() / p.height.coerceAtLeast(1)
                var dw = targetW
                var dh = targetW / imgAspect
                if (dh > targetH) {
                    dh = targetH
                    dw = targetH * imgAspect
                }
                var ox = (pw - dw) / 2f
                var oy = (ph - dh) / 2f
                if (p.box != null) {
                    ox = p.box[2]; oy = p.box[3]; dw = p.box[4]; dh = p.box[5]
                }
                val cm = "${f(dw)} 0 0 ${f(dh)} ${f(ox)} ${f(oy)} cm"
                val cs = "q $cm /Im0 Do Q\n" + (if (p.mask != null) "q 0 g $cm /Mk0 Do Q\n" else "")
                offsets[cont] = out.count
                w("$cont 0 obj\n<< /Length ${cs.length} >>\nstream\n$cs" + "endstream\nendobj\n")

                offsets[page] = out.count
                w("$page 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${f(pw)} ${f(ph)}] " +
                    "/Resources << /XObject << /Im0 $img 0 R" + (if (p.mask != null) " /Mk0 $maskObj 0 R" else "") +
                    " >> >> /Contents $cont 0 R >>\nendobj\n")
            }

            offsets[2] = out.count
            w("2 0 obj\n<< /Type /Pages /Count $n /Kids [ ${pageObjs.joinToString(" ") { "$it 0 R" }} ] >>\nendobj\n")
            // v3.10 PDF/A-1b: document info, XMP metadata, the sRGB output intent (colour profile), then the catalog
            val infoObj = nextObj++
            val metaObj = nextObj++
            val iccObj = nextObj++

            offsets[infoObj] = out.count
            w("$infoObj 0 obj\n<< /Producer (CamPDF) /Creator (CamPDF) /CreationDate ($pdfDate) /ModDate ($pdfDate) >>\nendobj\n")

            val xmp = PdfA.xmp(xmpDate)
            offsets[metaObj] = out.count
            w("$metaObj 0 obj\n<< /Type /Metadata /Subtype /XML /Length ${xmp.size} >>\nstream\n")
            out.write(xmp)
            w("\nendstream\nendobj\n")

            val icc = PdfA.srgbIcc
            offsets[iccObj] = out.count
            w("$iccObj 0 obj\n<< /N 3 /Length ${icc.size} >>\nstream\n")
            out.write(icc)
            w("\nendstream\nendobj\n")

            val id = java.util.UUID.randomUUID().toString().replace("-", "").uppercase(Locale.US)
            offsets[1] = out.count
            w("1 0 obj\n<< /Type /Catalog /Pages 2 0 R /Metadata $metaObj 0 R /OutputIntents [ << /Type /OutputIntent " +
                "/S /GTS_PDFA1 /OutputConditionIdentifier (sRGB IEC61966-2.1) /Info (sRGB) /DestOutputProfile $iccObj 0 R >> ] >>\nendobj\n")

            val last = iccObj
            val xrefPos = out.count
            w("xref\n0 ${last + 1}\n")
            w("0000000000 65535 f \n")
            for (i in 1..last) {
                val o = offsets[i]
                w(String.format(Locale.US, "%010d 00000 n \n", o ?: error("object $i not written")))
            }
            w("trailer\n<< /Size ${last + 1} /Root 1 0 R /Info $infoObj 0 R /ID [ <$id> <$id> ] >>\nstartxref\n$xrefPos\n%%EOF\n")
        }
    }
}
