package com.campdf.app

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/**
 * v3.9 the one PDF reader shared by [PdfDoc] (page access) and [PdfCrypt] (password lock).
 *
 * Reads the classic cross-reference table that PdfWriter writes and gives objects on demand straight from the
 * file (RandomAccessFile): only the dictionaries are kept, stream data (the page JPEGs) is read one stream at a
 * time when asked. So a 200-page document no longer has to fit into memory to be merged, split or locked.
 * Plain Kotlin, no Android types (tested on the PC: tools/jvmtest/PdfTests.kt).
 */
class PdfFile(file: File) : Closeable {

    class Obj(val num: Int, val dict: String, val streamOffset: Long, val streamLength: Int) {
        val isStream: Boolean get() = streamOffset >= 0
    }

    private val raf = RandomAccessFile(file, "r")
    private val size = raf.length()
    private val offsets = HashMap<Int, Long>()
    private val cache = HashMap<Int, Obj>()

    /** The trailer dictionary text ("<< /Size .. /Root .. >>"). */
    val trailer: String

    /** Object numbers present in the file, ascending. */
    val objectNumbers: List<Int> get() = offsets.keys.sorted()

    init {
        try {
            val tailLen = minOf(size, 2048L).toInt()
            val tail = latin(read(size - tailLen, tailLen))
            val sx = tail.lastIndexOf("startxref")
            require(sx >= 0) { "no startxref" }
            val xrefPos = Regex("""startxref\s+(\d+)""").find(tail.substring(sx))!!.groupValues[1].toLong()
            // xref table + trailer: read forward until "startxref"
            val sb = StringBuilder()
            var p = xrefPos
            while (p < size) {
                val n = minOf(65536L, size - p).toInt()
                sb.append(latin(read(p, n)))
                p += n
                if (sb.indexOf("startxref") >= 0) break
            }
            val text = sb.toString()
            val tr = text.indexOf("trailer")
            require(tr >= 0) { "no trailer" }
            val lines = text.substring(0, tr).lines().map { it.trim() }.filter { it.isNotEmpty() }
            require(lines.isNotEmpty() && lines[0] == "xref") { "no classic xref table" }
            var i = 1
            while (i < lines.size) {
                val hdr = lines[i].split(Regex("\\s+"))
                val start = hdr[0].toInt()
                val count = hdr[1].toInt()
                for (k in 0 until count) {
                    val e = lines[i + 1 + k].split(Regex("\\s+"))
                    if (e[2] == "n") offsets[start + k] = e[0].toLong()
                }
                i += 1 + count
            }
            trailer = text.substring(tr, text.indexOf("startxref", tr).let { if (it < 0) text.length else it })
        } catch (e: Throwable) {
            raf.close()
            throw e
        }
    }

    @Synchronized
    fun read(offset: Long, length: Int): ByteArray {
        val b = ByteArray(length)
        raf.seek(offset)
        raf.readFully(b)
        return b
    }

    fun ref(dict: String, key: String): Int? =
        Regex("""/$key\s+(\d+)\s+\d+\s+R""").find(dict)?.groupValues?.get(1)?.toInt()

    /** Object [num]: its dictionary text and, for a stream, where the data is (read it with [stream]). */
    @Synchronized
    fun obj(num: Int): Obj {
        cache[num]?.let { return it }
        val at = offsets[num] ?: error("missing object $num")
        // read the object head in growing chunks until its dictionary ends ("stream" or "endobj")
        var len = 1024
        var text: String
        var st: Int
        var end: Int
        while (true) {
            val n = minOf(len.toLong(), size - at).toInt()
            text = latin(read(at, n))
            st = text.indexOf("stream")
            end = text.indexOf("endobj")
            if (st >= 0 || end >= 0 || n.toLong() == size - at) break
            len *= 4
        }
        val head = Regex("""^\s*(\d+)\s+(\d+)\s+obj""").find(text) ?: error("bad object $num")
        require(head.groupValues[1].toInt() == num) { "xref mismatch" }
        val bodyStart = head.range.last + 1
        val o = if (st >= 0 && (end < 0 || st < end)) {
            val dict = text.substring(bodyStart, st).trim()
            val length = Regex("""/Length\s+(\d+)(?!\s+\d+\s+R)""").find(dict)!!.groupValues[1].toInt()
            var ds = st + 6
            if (ds < text.length && text[ds] == '\r') ds++
            if (ds < text.length && text[ds] == '\n') ds++
            Obj(num, dict, at + ds, length)
        } else {
            require(end >= 0) { "object $num too long" }
            Obj(num, text.substring(bodyStart, end).trim(), -1L, 0)
        }
        cache[num] = o
        return o
    }

    /** The raw (still encoded) data of a stream object. */
    fun stream(o: Obj): ByteArray {
        require(o.isStream)
        return read(o.streamOffset, o.streamLength)
    }

    override fun close() = raf.close()

    private fun latin(b: ByteArray) = String(b, Charsets.ISO_8859_1)
}
