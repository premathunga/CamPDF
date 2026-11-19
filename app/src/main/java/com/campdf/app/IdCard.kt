package com.campdf.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.roundToInt

/**
 * v3.5 ID card copy: the front and back of an ID card (NIC, driving licence, bank card - ISO ID-1 format,
 * 85.6 x 54 mm) are placed on one A4 sheet at their real size, like an office photocopy. Printing the PDF at
 * "actual size / 100%" gives a card-sized copy. Two scanned pages make one sheet; more pages make more sheets.
 */
object IdCard {
    const val CARD_W_MM = 85.6f
    const val CARD_H_MM = 53.98f
    private const val A4_W_MM = 210f
    private const val A4_H_MM = 297f
    private const val TOP_MM = 30f   // first card starts 3 cm below the top edge
    private const val GAP_MM = 15f   // space between front and back

    /** One A4 sheet (white) with up to two cards, at [dpi] dots per inch. */
    fun sheet(cards: List<PageItem>, dpi: Int): Bitmap? {
        val pxPerMm = dpi / 25.4f
        val sw = (A4_W_MM * pxPerMm).roundToInt()
        val sh = (A4_H_MM * pxPerMm).roundToInt()
        val out = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        out.eraseColor(android.graphics.Color.WHITE)
        val canvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        // load each card a little larger than it is drawn, so the down-scaling keeps text sharp
        val loadPx = ((CARD_W_MM * pxPerMm) * 1.3f).roundToInt().coerceAtLeast(300)
        var top = TOP_MM * pxPerMm
        var drawn = 0
        for (p in cards.take(2)) {
            val bm = Imaging.pageBitmap(p, loadPx) ?: continue
            try {
                // landscape card (usual) or a portrait one (some licences / staff IDs): keep its orientation
                val portrait = bm.height > bm.width
                val wMm = if (portrait) CARD_H_MM else CARD_W_MM
                val hMm = if (portrait) CARD_W_MM else CARD_H_MM
                val w = wMm * pxPerMm
                val h = hMm * pxPerMm
                val left = (sw - w) / 2f
                canvas.drawBitmap(bm, null, RectF(left, top, left + w, top + h), paint)
                top += h + GAP_MM * pxPerMm
                drawn++
            } finally {
                bm.recycle()
            }
        }
        if (drawn == 0) { out.recycle(); return null }
        return out
    }
}
