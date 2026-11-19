package com.campdf.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * v3.7 ID copies: what kind of document is copied - its real size, how many sides are scanned and how many
 * scans go on one A4 sheet. (No bank cards on purpose: a copy of a bank card is a fraud risk.)
 */
enum class IdKind(val wMm: Float, val hMm: Float, val sides: Int, val perSheet: Int, val prefix: String) {
    /** National identity card (ISO ID-1), front + back on one sheet. */
    NIC(85.6f, 53.98f, 2, 2, "ID_"),
    /** Sri Lankan driving licence (smart card, ID-1), front + back. */
    LICENSE(85.6f, 53.98f, 2, 2, "Licence_"),
    /** Passport photo page (ID-3, 125 x 88 mm), one per sheet. */
    PASSPORT(125f, 88f, 1, 1, "Passport_"),
    /** Certificate or any A4 document: the whole page, fitted to A4 (its own size is unknown). */
    CERTIFICATE(0f, 0f, 1, 1, "Certificate_");

    val fullPage: Boolean get() = wMm == 0f
}

/**
 * Places scanned ID sides on A4 sheets at their real size, like an office photocopy (print at 100% / actual size),
 * with an optional "for ... only" watermark across each copy so it cannot be reused for another purpose.
 */
object IdCard {
    const val A4_W_MM = 210f
    const val A4_H_MM = 297f
    private const val TOP_MM = 30f   // first copy starts 3 cm below the top edge
    private const val GAP_MM = 15f   // space between front and back
    private const val PAGE_MARGIN_MM = 12f

    /**
     * Where each scan goes on the sheet, in mm from the top-left corner. [aspects] = width / height of each scan
     * (a portrait card stays portrait). Shared by the PDF and the preview on the ID screen.
     */
    fun layout(kind: IdKind, aspects: List<Float>): List<RectF> {
        val out = ArrayList<RectF>()
        if (kind.fullPage) {
            val a = aspects.firstOrNull() ?: (A4_W_MM / A4_H_MM)
            val maxW = A4_W_MM - 2 * PAGE_MARGIN_MM
            val maxH = A4_H_MM - 2 * PAGE_MARGIN_MM
            var w = maxW
            var h = w / a
            if (h > maxH) { h = maxH; w = h * a }
            val l = (A4_W_MM - w) / 2f
            val t = (A4_H_MM - h) / 2f
            out.add(RectF(l, t, l + w, t + h))
            return out
        }
        var top = TOP_MM
        for (a in aspects.take(kind.perSheet)) {
            val portrait = a < 1f
            val w = if (portrait) kind.hMm else kind.wMm
            val h = if (portrait) kind.wMm else kind.hMm
            val l = (A4_W_MM - w) / 2f
            out.add(RectF(l, top, l + w, top + h))
            top += h + GAP_MM
        }
        return out
    }

    /** One A4 sheet (white) with the scans of [cards] at [dpi]; [watermark] = text across each copy, or null. */
    fun sheet(cards: List<PageItem>, dpi: Int, kind: IdKind = IdKind.NIC, watermark: String? = null): Bitmap? {
        val pxPerMm = dpi / 25.4f
        val sw = (A4_W_MM * pxPerMm).roundToInt()
        val sh = (A4_H_MM * pxPerMm).roundToInt()
        // load each scan a little larger than it is drawn, so the down-scaling keeps text sharp
        val longMm = if (kind.fullPage) A4_H_MM else kind.wMm
        val loadPx = (longMm * pxPerMm * 1.3f).roundToInt().coerceAtLeast(300)
        val bms = cards.take(kind.perSheet).mapNotNull { Imaging.pageBitmap(it, loadPx) }
        if (bms.isEmpty()) return null
        val out = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        out.eraseColor(android.graphics.Color.WHITE)
        val canvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        try {
            val rects = layout(kind, bms.map { it.width.toFloat() / it.height })
            for ((bm, r) in bms.zip(rects)) {
                val px = RectF(r.left * pxPerMm, r.top * pxPerMm, r.right * pxPerMm, r.bottom * pxPerMm)
                canvas.drawBitmap(bm, null, px, paint)
                if (!watermark.isNullOrBlank()) drawWatermark(canvas, px, watermark, pxPerMm)
            }
        } finally {
            bms.forEach { it.recycle() }
        }
        return out
    }

    /**
     * Repeats [text] diagonally across [r] (clipped to it), dark grey at ~40% so the copy stays readable but the
     * text cannot be cropped or cleaned away. Android's text engine shapes Sinhala / Tamil correctly.
     */
    fun drawWatermark(canvas: Canvas, r: RectF, text: String, pxPerMm: Float) {
        val hMm = r.height() / pxPerMm
        val sizeMm = (hMm / 13f).coerceIn(3.2f, 7f)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(105, 60, 60, 60)
            textSize = sizeMm * pxPerMm
            typeface = Typeface.DEFAULT_BOLD
        }
        val step = p.measureText(text) + sizeMm * 3f * pxPerMm
        val lineGap = sizeMm * 3.4f * pxPerMm
        canvas.save()
        canvas.clipRect(r)
        canvas.rotate(-25f, r.centerX(), r.centerY())
        val half = hypot(r.width(), r.height()) / 2f + step
        var y = r.centerY() - half
        var row = 0
        while (y < r.centerY() + half) {
            var x = r.centerX() - half - (row % 2) * step / 2f
            while (x < r.centerX() + half) {
                canvas.drawText(text, x, y, p)
                x += step
            }
            y += lineGap
            row++
        }
        canvas.restore()
    }
}
