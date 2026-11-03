package com.campdf.app

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Documents live in the app's private storage: filesDir/docs/<name>.pdf (+ .pages and .jpg sidecars). */
class DocRepo(private val ctx: Context) {
    val dir: File = File(ctx.filesDir, "docs").apply { mkdirs() }
    val signatureFile = File(ctx.filesDir, "signature.png")

    fun list(): List<DocItem> {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".pdf") } ?: return emptyList()
        return files.map { f ->
            val pages = File(dir, f.name + ".pages").takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: 0
            val thumb = File(dir, f.name + ".jpg").takeIf { it.exists() }
            DocItem(f, f.nameWithoutExtension, pages, f.length(), f.lastModified(), thumb)
        }.sortedByDescending { it.modified }
    }

    fun sanitize(name: String): String {
        val s = name.replace(Regex("[\\\\/:*?\"<>|]"), "").trim().take(80)
        return if (s.isEmpty()) "Scan" else s
    }

    fun defaultName(): String = "Scan_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())

    fun newFile(name: String): File {
        val base = sanitize(name)
        var f = File(dir, "$base.pdf")
        var i = 2
        while (f.exists()) {
            f = File(dir, "$base ($i).pdf")
            i++
        }
        return f
    }

    fun writeMeta(file: File, pages: Int, thumb: Bitmap?) {
        File(dir, file.name + ".pages").writeText(pages.toString())
        if (thumb != null) {
            Imaging.saveJpeg(thumb, File(dir, file.name + ".jpg"), 80)
            thumb.recycle()
        }
    }

    fun rename(file: File, newName: String): File {
        val s = sanitize(newName)
        val target = File(dir, "$s.pdf")
        if (target.absolutePath == file.absolutePath) return file
        val dest = if (target.exists()) newFile(s) else target
        if (!file.renameTo(dest)) return file
        File(dir, file.name + ".pages").renameTo(File(dir, dest.name + ".pages"))
        File(dir, file.name + ".jpg").renameTo(File(dir, dest.name + ".jpg"))
        return dest
    }

    fun delete(file: File) {
        file.delete()
        File(dir, file.name + ".pages").delete()
        File(dir, file.name + ".jpg").delete()
    }

    fun uri(file: File): Uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", file)
}
