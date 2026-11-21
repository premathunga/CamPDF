package com.campdf.app

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * v3.10 black-and-white pages stored as 1 bit per pixel (PDF: /DeviceGray, 1 bit, /FlateDecode), instead of a
 * colour JPEG. Measured on real B&W scans: about 12x smaller files (e.g. 281 KB -> 23 KB per page) and sharper
 * letters (no JPEG ringing around thin Sinhala strokes). Compression is zlib from the Java platform
 * (java.util.zip) - no library. Bit 1 = white, 0 = black (the PDF and PNG convention).
 * Plain Kotlin: tested on the PC (tools/jvmtest/PdfTests.kt).
 */
object Bitonal {

    /** Bytes per packed row. */
    fun rowBytes(w: Int) = (w + 7) / 8

    /** [argb] (w*h pixels) -> zlib-compressed packed rows; luminance >= [threshold] is white. */
    fun encode(argb: IntArray, w: Int, h: Int, threshold: Int = 128): ByteArray {
        val rb = rowBytes(w)
        val def = Deflater(Deflater.BEST_COMPRESSION)
        val out = ByteArrayOutputStream(w * h / 40 + 1024)
        val buf = ByteArray(64 * 1024)
        val row = ByteArray(rb)
        val t = threshold * 1000
        for (y in 0 until h) {
            java.util.Arrays.fill(row, 0)
            val base = y * w
            for (x in 0 until w) {
                val p = argb[base + x]
                val l = ((p shr 16) and 255) * 299 + ((p shr 8) and 255) * 587 + (p and 255) * 114
                if (l >= t) row[x ushr 3] = (row[x ushr 3].toInt() or (0x80 ushr (x and 7))).toByte()
            }
            def.setInput(row)
            while (!def.needsInput()) {
                val n = def.deflate(buf)
                if (n > 0) out.write(buf, 0, n)
            }
        }
        def.finish()
        while (!def.finished()) {
            val n = def.deflate(buf)
            if (n > 0) out.write(buf, 0, n)
        }
        def.end()
        return out.toByteArray()
    }

    /** zlib data -> packed rows (rowBytes(w) * h bytes). */
    fun unpackRows(data: ByteArray, w: Int, h: Int): ByteArray {
        val raw = ByteArray(rowBytes(w) * h)
        val inf = Inflater()
        inf.setInput(data)
        var off = 0
        while (off < raw.size && !inf.finished()) {
            val n = inf.inflate(raw, off, raw.size - off)
            if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
            off += n
        }
        inf.end()
        return raw
    }

    /**
     * Decodes to ARGB pixels, reduced by an integer [step] (each output pixel = the average of step x step input
     * pixels, so thin lines stay visible as grey in thumbnails). Returns (pixels, outW, outH).
     */
    fun decode(data: ByteArray, w: Int, h: Int, step: Int = 1): Triple<IntArray, Int, Int> {
        val s = step.coerceAtLeast(1)
        val raw = unpackRows(data, w, h)
        val rb = rowBytes(w)
        val ow = (w + s - 1) / s
        val oh = (h + s - 1) / s
        val sum = IntArray(ow)
        val cnt = IntArray(ow)
        val out = IntArray(ow * oh)
        for (oy in 0 until oh) {
            java.util.Arrays.fill(sum, 0)
            java.util.Arrays.fill(cnt, 0)
            for (y in oy * s until minOf(h, oy * s + s)) {
                val rbase = y * rb
                for (x in 0 until w) {
                    val bit = (raw[rbase + (x ushr 3)].toInt() ushr (7 - (x and 7))) and 1
                    val ox = x / s
                    sum[ox] += bit
                    cnt[ox]++
                }
            }
            for (ox in 0 until ow) {
                val v = if (cnt[ox] == 0) 255 else sum[ox] * 255 / cnt[ox]
                out[oy * ow + ox] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
        return Triple(out, ow, oh)
    }

    /** A 1-bit grayscale PNG of the page (for "PDF to images" and editing): small and lossless. */
    fun toPng(data: ByteArray, w: Int, h: Int): ByteArray {
        val raw = unpackRows(data, w, h)
        val rb = rowBytes(w)
        // PNG scanlines: a filter byte (0 = none) before each packed row
        val def = Deflater(Deflater.BEST_COMPRESSION)
        val idat = ByteArrayOutputStream(data.size + 1024)
        val buf = ByteArray(64 * 1024)
        val line = ByteArray(rb + 1)
        for (y in 0 until h) {
            line[0] = 0
            System.arraycopy(raw, y * rb, line, 1, rb)
            def.setInput(line)
            while (!def.needsInput()) { val n = def.deflate(buf); if (n > 0) idat.write(buf, 0, n) }
        }
        def.finish()
        while (!def.finished()) { val n = def.deflate(buf); if (n > 0) idat.write(buf, 0, n) }
        def.end()
        val png = ByteArrayOutputStream(idat.size() + 64)
        png.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        fun chunk(type: String, body: ByteArray) {
            fun int(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
            val t = type.toByteArray(Charsets.US_ASCII)
            png.write(int(body.size)); png.write(t); png.write(body)
            val crc = CRC32(); crc.update(t); crc.update(body)
            png.write(int(crc.value.toInt()))
        }
        val ihdr = ByteArrayOutputStream()
        fun i32(v: Int) { ihdr.write(v ushr 24); ihdr.write(v ushr 16); ihdr.write(v ushr 8); ihdr.write(v) }
        i32(w); i32(h)
        ihdr.write(1)  // bit depth 1
        ihdr.write(0)  // greyscale
        ihdr.write(0); ihdr.write(0); ihdr.write(0) // deflate, adaptive filtering, no interlace
        chunk("IHDR", ihdr.toByteArray())
        chunk("IDAT", idat.toByteArray())
        chunk("IEND", ByteArray(0))
        return png.toByteArray()
    }
}
