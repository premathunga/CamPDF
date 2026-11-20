package com.campdf.app

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * v3.5 PDF password lock - our own code, only the platform's AES / SHA-2 (javax.crypto, java.security).
 *
 * Makes a locked copy of a PDF written by [PdfWriter] with the standard PDF 2.0 / ISO 32000-2 security handler
 * "AES-256" (/V 5 /R 6, AESV3): every stream and string is encrypted with a random 256-bit file key, and the key
 * is wrapped with the password (hash algorithm 2.B: SHA-256/384/512 + AES rounds). Adobe Reader, Chrome, Edge,
 * Firefox, Android / iOS PDF viewers and WhatsApp's preview all ask for the password and open it.
 *
 * The password is never stored. Plain Kotlin, no Android types: tested on the PC with qpdf (tools/jvmtest).
 */
object PdfCrypt {

    private val rnd = SecureRandom()

    private fun random(n: Int) = ByteArray(n).also { rnd.nextBytes(it) }

    // ---------- crypto primitives ----------

    private fun aesCbcNoPad(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/CBC/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return c.doFinal(data)
    }

    private fun aesEcbNoPad(key: ByteArray, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/ECB/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return c.doFinal(data)
    }

    /** Stream / string encryption for AESV3: 16 random IV bytes + AES-256-CBC with PKCS#5 padding. */
    private fun encryptData(fileKey: ByteArray, data: ByteArray): ByteArray {
        val iv = random(16)
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(fileKey, "AES"), IvParameterSpec(iv))
        return iv + c.doFinal(data)
    }

    /** ISO 32000-2 algorithm 2.B (revision 6 hash). */
    internal fun hash2B(pw: ByteArray, salt: ByteArray, udata: ByteArray): ByteArray {
        var k = MessageDigest.getInstance("SHA-256").digest(pw + salt + udata)
        var round = 0
        var e: ByteArray
        while (true) {
            val one = pw + k + udata
            val k1 = ByteArray(one.size * 64)
            for (i in 0 until 64) System.arraycopy(one, 0, k1, i * one.size, one.size)
            e = aesCbcNoPad(k.copyOfRange(0, 16), k.copyOfRange(16, 32), k1)
            var sum = 0
            for (i in 0 until 16) sum += e[i].toInt() and 0xff
            val alg = when (sum % 3) { 0 -> "SHA-256"; 1 -> "SHA-384"; else -> "SHA-512" }
            k = MessageDigest.getInstance(alg).digest(e)
            round++
            if (round >= 64 && (e[e.size - 1].toInt() and 0xff) <= round - 32) break
        }
        return k.copyOfRange(0, 32)
    }

    /** UTF-8, at most 127 bytes (the PDF limit for revision 6 passwords). */
    private fun pwBytes(pw: String): ByteArray {
        val b = pw.toByteArray(Charsets.UTF_8)
        return if (b.size > 127) b.copyOfRange(0, 127) else b
    }

    private fun hex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2 + 2).append('<')
        for (x in b) sb.append(String.format(Locale.US, "%02X", x.toInt() and 0xff))
        return sb.append('>').toString()
    }

    /** Builds the /Encrypt dictionary; returns (dictionary text, file key). */
    private fun securityHandler(userPw: String, ownerPw: ByteArray): Pair<String, ByteArray> {
        val fileKey = random(32)
        val up = pwBytes(userPw)
        val uvs = random(8)
        val uks = random(8)
        val u = hash2B(up, uvs, ByteArray(0)) + uvs + uks
        val ue = aesCbcNoPad(hash2B(up, uks, ByteArray(0)), ByteArray(16), fileKey)
        val ovs = random(8)
        val oks = random(8)
        val o = hash2B(ownerPw, ovs, u) + ovs + oks
        val oe = aesCbcNoPad(hash2B(ownerPw, oks, u), ByteArray(16), fileKey)
        val p = -4 // all permissions (print, copy...): the password only protects opening the file
        val perms = ByteArray(16)
        for (i in 0 until 4) perms[i] = (p shr (8 * i)).toByte()
        for (i in 4 until 8) perms[i] = 0xff.toByte()
        perms[8] = 'T'.code.toByte(); perms[9] = 'a'.code.toByte(); perms[10] = 'd'.code.toByte(); perms[11] = 'b'.code.toByte()
        System.arraycopy(random(4), 0, perms, 12, 4)
        val permsEnc = aesEcbNoPad(fileKey, perms)
        val dict = "<< /Filter /Standard /V 5 /R 6 /Length 256 " +
            "/CF << /StdCF << /AuthEvent /DocOpen /CFM /AESV3 /Length 32 >> >> /StmF /StdCF /StrF /StdCF " +
            "/O ${hex(o)} /U ${hex(u)} /OE ${hex(oe)} /UE ${hex(ue)} /P $p /Perms ${hex(permsEnc)} /EncryptMetadata true >>"
        return dict to fileKey
    }

    /** Replaces every literal string (...) in an object by its encrypted hex form. */
    private fun encryptStrings(s: String, key: ByteArray): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '<' && i + 1 < s.length && s[i + 1] != '<') {
                // hex string: encrypt its bytes too
                val end = s.indexOf('>', i)
                val h = s.substring(i + 1, end).filter { !it.isWhitespace() }.let { if (it.length % 2 == 1) it + "0" else it }
                val raw = ByteArray(h.length / 2) { h.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
                out.append(hex(encryptData(key, raw)))
                i = end + 1
            } else if (ch == '<' || ch == '>') {
                out.append(ch); i++
                if (i < s.length && s[i] == ch) { out.append(ch); i++ }
            } else if (ch == '(') {
                val raw = ByteArrayOutputStream()
                var depth = 1
                i++
                while (i < s.length && depth > 0) {
                    val c = s[i]
                    when {
                        c == '\\' && i + 1 < s.length -> {
                            val n = s[i + 1]
                            when (n) {
                                'n' -> raw.write(10); 'r' -> raw.write(13); 't' -> raw.write(9)
                                'b' -> raw.write(8); 'f' -> raw.write(12)
                                in '0'..'7' -> {
                                    var j = i + 1
                                    var v = 0
                                    while (j < s.length && j < i + 4 && s[j] in '0'..'7') { v = v * 8 + (s[j] - '0'); j++ }
                                    raw.write(v and 0xff); i = j - 2
                                }
                                '\n' -> Unit
                                else -> raw.write(n.code and 0xff)
                            }
                            i += 2
                        }
                        c == '(' -> { depth++; raw.write(c.code); i++ }
                        c == ')' -> { depth--; if (depth > 0) raw.write(c.code); i++ }
                        else -> { raw.write(c.code and 0xff); i++ }
                    }
                }
                out.append(hex(encryptData(key, raw.toByteArray())))
            } else {
                out.append(ch); i++
            }
        }
        return out.toString()
    }

    /**
     * Writes [dst] = [src] locked with [password] (AES-256). [src] must be a CamPDF document (PdfWriter output).
     * v3.9: objects are read one at a time from the file and written straight to [dst] (no whole-file copy in
     * memory), so long documents can be locked too. Throws when the source cannot be read.
     */
    fun lock(src: File, dst: File, password: String) {
        require(password.isNotEmpty())
        val tmp = File(dst.parentFile, "." + dst.name + ".tmp")
        PdfFile(src).use { pdf ->
            require(!pdf.trailer.contains("/Encrypt")) { "already encrypted" }
            val root = Regex("""/Root\s+(\d+\s+\d+\s+R)""").find(pdf.trailer)!!.groupValues[1]
            val info = Regex("""/Info\s+(\d+\s+\d+\s+R)""").find(pdf.trailer)?.groupValues?.get(1)
            // owner password: random, so nobody can "unlock" restrictions without the user password
            val (encDict, key) = securityHandler(password, random(32))
            val nums = pdf.objectNumbers
            val encNum = nums.max() + 1
            val offsets = LongArray(encNum + 1)
            Counting(BufferedOutputStream(FileOutputStream(tmp), 64 * 1024)).use { out ->
                fun w(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))
                w("%PDF-1.7\n")
                out.write(byteArrayOf('%'.code.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))
                for (n in nums) {
                    val o = pdf.obj(n)
                    offsets[n] = out.count
                    w("$n 0 obj\n")
                    if (o.isStream) {
                        val enc = encryptData(key, pdf.stream(o))
                        val d = encryptStrings(o.dict, key).replace(Regex("""/Length\s+\d+"""), "/Length ${enc.size}")
                        w("$d\nstream\n")
                        out.write(enc)
                        w("\nendstream\nendobj\n")
                    } else {
                        var d = encryptStrings(o.dict, key)
                        if (Regex("""/Type\s*/Catalog""").containsMatchIn(d) && !d.contains("/Extensions")) {
                            // marks the AES-256 (revision 6) extension for older readers
                            val end = d.lastIndexOf(">>")
                            d = d.substring(0, end) + "/Extensions << /ADBE << /BaseVersion /1.7 /ExtensionLevel 8 >> >> " + d.substring(end)
                        }
                        w("$d\nendobj\n")
                    }
                }
                offsets[encNum] = out.count
                w("$encNum 0 obj\n$encDict\nendobj\n")
                val xref = out.count
                w("xref\n0 ${encNum + 1}\n")
                w("0000000000 65535 f \n")
                for (n in 1..encNum) {
                    if (offsets[n] > 0) w(String.format(Locale.US, "%010d 00000 n \n", offsets[n]))
                    else w("0000000000 65535 f \n")
                }
                val id = hex(random(16))
                w("trailer\n<< /Size ${encNum + 1} /Root $root " + (if (info != null) "/Info $info " else "") +
                    "/Encrypt $encNum 0 R /ID [ $id $id ] >>\nstartxref\n$xref\n%%EOF\n")
            }
        }
        if (dst.exists()) dst.delete()
        if (!tmp.renameTo(dst)) { tmp.copyTo(dst, overwrite = true); tmp.delete() }
    }

    private class Counting(private val o: OutputStream) : OutputStream() {
        var count = 0L
        override fun write(b: Int) { o.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { o.write(b, off, len); count += len }
        override fun flush() = o.flush()
        override fun close() = o.close()
    }
}
