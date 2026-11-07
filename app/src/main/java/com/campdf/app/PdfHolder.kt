package com.campdf.app

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import kotlin.math.roundToInt

/** Thin wrapper over the platform PdfRenderer (offline, no library). One page can be open at a time, hence the lock. */
class PdfHolder(file: File) {
    private val pfd: ParcelFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer: PdfRenderer = PdfRenderer(pfd)
    private val lock = Any()
    val pageCount: Int = renderer.pageCount

    fun render(index: Int, widthPx: Int): Bitmap? = synchronized(lock) {
        runCatching {
            renderer.openPage(index).use { page ->
                val h = (widthPx.toFloat() * page.height / page.width).roundToInt().coerceAtLeast(1)
                val bm = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888)
                bm.eraseColor(Color.WHITE)
                page.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bm
            }
        }.getOrNull()
    }

    fun close() {
        synchronized(lock) {
            runCatching { renderer.close() }
            runCatching { pfd.close() }
        }
    }
}
