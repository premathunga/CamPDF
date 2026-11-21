package com.campdf.app

import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * v3.10 PDF/A-1b (ISO 19005-1, archival PDF) parts for [PdfWriter]: an sRGB colour profile (output intent) and
 * XMP metadata that matches the document information. Government offices file scans for many years; PDF/A
 * makes sure the file will still open and look the same. Images-only pages, no fonts, no transparency, no
 * encryption (a password-locked copy is a separate, non-archival file).
 * Plain Kotlin: the profile is checked with LittleCMS and the files with qpdf / poppler on the PC.
 */
object PdfA {

    /** A compact ICC v2 sRGB display profile (D50 PCS, gamma 2.2 curves), built here - no profile file shipped. */
    val srgbIcc: ByteArray by lazy { buildIcc() }

    private fun buildIcc(): ByteArray {
        fun s15(v: Double): ByteArray { val i = Math.round(v * 65536.0).toInt(); return int32(i) }
        fun xyz(x: Double, y: Double, z: Double): ByteArray =
            "XYZ ".toByteArray(Charsets.US_ASCII) + ByteArray(4) + s15(x) + s15(y) + s15(z)
        fun curv(): ByteArray = "curv".toByteArray(Charsets.US_ASCII) + ByteArray(4) + int32(1) + byteArrayOf(0x02, 0x33) // gamma 2.2
        fun desc(text: String): ByteArray {
            val a = text.toByteArray(Charsets.US_ASCII) + 0
            return "desc".toByteArray(Charsets.US_ASCII) + ByteArray(4) + int32(a.size) + a +
                int32(0) + int32(0) +            // unicode language code + count
                byteArrayOf(0, 0) + byteArrayOf(0) + ByteArray(67) // scriptcode code, count, 67 bytes
        }
        fun text(t: String): ByteArray = "text".toByteArray(Charsets.US_ASCII) + ByteArray(4) + t.toByteArray(Charsets.US_ASCII) + 0
        val tags = listOf(
            "desc" to desc("sRGB (CamPDF)"),
            "cprt" to text("No copyright, use freely"),
            "wtpt" to xyz(0.9642, 1.0, 0.8249),
            "rXYZ" to xyz(0.4361, 0.2225, 0.0139),
            "gXYZ" to xyz(0.3851, 0.7169, 0.0971),
            "bXYZ" to xyz(0.1431, 0.0606, 0.7141),
            "rTRC" to curv(), "gTRC" to curv(), "bTRC" to curv()
        )
        val tableSize = 4 + 12 * tags.size
        val body = ByteArrayOutputStream()
        val entries = ByteArrayOutputStream()
        var off = 128 + tableSize
        for ((sig, data) in tags) {
            val padded = data + ByteArray((4 - data.size % 4) % 4)
            entries.write(sig.toByteArray(Charsets.US_ASCII)); entries.write(int32(off)); entries.write(int32(data.size))
            body.write(padded)
            off += padded.size
        }
        val size = 128 + tableSize + body.size()
        val h = ByteArrayOutputStream()
        h.write(int32(size))
        h.write(ByteArray(4))                                  // preferred CMM
        h.write(byteArrayOf(0x02, 0x10, 0, 0))                 // version 2.1
        h.write("mntrRGB XYZ ".toByteArray(Charsets.US_ASCII)) // display class, RGB data, XYZ PCS
        h.write(byteArrayOf(0x07, 0xEA.toByte(), 0, 11, 0, 20, 0, 0, 0, 0, 0, 0)) // 2026-11-20 00:00:00
        h.write("acsp".toByteArray(Charsets.US_ASCII))
        h.write(ByteArray(4 + 4 + 4 + 4 + 8 + 4))             // platform, flags, manufacturer, model, attributes, intent
        h.write(s15(0.9642)); h.write(s15(1.0)); h.write(s15(0.8249)) // PCS illuminant D50
        h.write(ByteArray(4 + 16 + 28))                        // creator, profile ID, reserved
        check(h.size() == 128)
        h.write(int32(tags.size))
        h.write(entries.toByteArray())
        h.write(body.toByteArray())
        return h.toByteArray()
    }

    private fun int32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    /** The two date forms that must agree: PDF "D:20261120103000+05'30'" and XMP "2026-11-20T10:30:00+05:30". */
    fun dates(now: Date = Date(), tz: TimeZone = TimeZone.getDefault()): Pair<String, String> {
        val f = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = tz }
        val x = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = tz }
        val offMin = tz.getOffset(now.time) / 60000
        val sign = if (offMin < 0) "-" else "+"
        val hh = String.format(Locale.US, "%02d", Math.abs(offMin) / 60)
        val mm = String.format(Locale.US, "%02d", Math.abs(offMin) % 60)
        return ("D:" + f.format(now) + sign + hh + "'" + mm + "'") to (x.format(now) + sign + hh + ":" + mm)
    }

    /** XMP packet for PDF/A-1b; [xmpDate] as from [dates]. Producer / creator match the Info dictionary. */
    fun xmp(xmpDate: String): ByteArray = ("""<?xpacket begin="${'\uFEFF'}" id="W5M0MpCehiHzreSzNTczkc9d"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/">
 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <rdf:Description rdf:about="" xmlns:pdfaid="http://www.aiim.org/pdfa/ns/id/">
   <pdfaid:part>1</pdfaid:part>
   <pdfaid:conformance>B</pdfaid:conformance>
  </rdf:Description>
  <rdf:Description rdf:about="" xmlns:pdf="http://ns.adobe.com/pdf/1.3/">
   <pdf:Producer>CamPDF</pdf:Producer>
  </rdf:Description>
  <rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/">
   <xmp:CreatorTool>CamPDF</xmp:CreatorTool>
   <xmp:CreateDate>$xmpDate</xmp:CreateDate>
   <xmp:ModifyDate>$xmpDate</xmp:ModifyDate>
  </rdf:Description>
 </rdf:RDF>
</x:xmpmeta>
<?xpacket end="w"?>""").toByteArray(Charsets.UTF_8)
}
