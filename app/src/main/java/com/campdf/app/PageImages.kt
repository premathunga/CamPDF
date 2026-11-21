package com.campdf.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/** v3.10 Android side of PdfPage images: JPEG pages and 1-bit (black-and-white) pages. */
object PageImages {

    /** The page as a bitmap no larger than about [maxDim] on its long side. */
    fun decode(p: PdfPage, maxDim: Int): Bitmap? {
        if (p.mask != null) return decodeMrc(p, maxDim)
        if (p.bitonal) {
            var step = 1
            while (maxOf(p.width, p.height) / step > maxDim * 2) step *= 2
            val (px, w, h) = Bitonal.decode(p.data, p.width, p.height, step)
            return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(p.data, 0, p.data.size, o)
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= maxDim) sample *= 2
        return BitmapFactory.decodeByteArray(p.data, 0, p.data.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** MRC page: the background enlarged to the text layer's size, the black text multiplied over it. */
    private fun decodeMrc(p: PdfPage, maxDim: Int): Bitmap? {
        var step = 1
        while (maxOf(p.maskW, p.maskH) / step > maxDim * 2) step *= 2
        val (mk, w, h) = Bitonal.decode(p.mask!!, p.maskW, p.maskH, step)
        val o = BitmapFactory.decodeByteArray(p.data, 0, p.data.size) ?: return null
        val bg = Bitmap.createScaledBitmap(o, w, h, true)
        if (bg !== o) o.recycle()
        val px = IntArray(w * h)
        bg.getPixels(px, 0, w, 0, 0, w, h)
        bg.recycle()
        for (i in px.indices) {
            val m = mk[i] and 255
            if (m < 255) {
                val c = px[i]
                val r = ((c shr 16) and 255) * m / 255
                val g = ((c shr 8) and 255) * m / 255
                val b = (c and 255) * m / 255
                px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /** The page as an image file: the original JPEG, a lossless 1-bit PNG, or (MRC) the layers joined. */
    fun file(p: PdfPage): Pair<ByteArray, String> = when {
        p.mask != null -> {
            val bm = decodeMrc(p, maxOf(p.maskW, p.maskH)) ?: error("decode")
            try { Imaging.toJpeg(bm, 90) to "jpg" } finally { bm.recycle() }
        }
        p.bitonal -> Bitonal.toPng(p.data, p.width, p.height) to "png"
        else -> p.data to "jpg"
    }

    /**
     * v3.10 Compact quality: a document page (Magic) as MRC - sharp 1-bit black text over a half-size JPEG
     * background (stamps, seals, coloured ink, paper). Null when the page has too little / too much black
     * writing for it to pay off (then one JPEG is used).
     */
    fun mrc(bm: Bitmap, jpegQuality: Int): PdfPage? {
        val w = bm.width
        val h = bm.height
        val px = IntArray(w * h)
        bm.getPixels(px, 0, w, 0, 0, w, h)
        if (!Mrc.worthIt(px)) return null
        val (mask, _) = Mrc.split(px, w, h)
        val bw = maxOf(1, w / 2)
        val bh = maxOf(1, h / 2)
        val small = Bitmap.createScaledBitmap(bm, bw, bh, true)
        val jpeg = try { Imaging.toJpeg(small, jpegQuality) } finally { if (small !== bm) small.recycle() }
        return PdfPage(jpeg, bw, bh, mask = mask, maskW = w, maskH = h)
    }

    /** Bitmap (after the B&W filter) -> a 1-bit page. */
    fun bitonal(bm: Bitmap, exactA4: Boolean = false): PdfPage {
        // a low-resolution page (photo from a small camera / WhatsApp) is enlarged with smoothing before it is made
        // 1-bit: the letter edges then follow the grey anti-aliasing instead of becoming coarse steps
        val long = maxOf(bm.width, bm.height)
        val src = if (long in 1..2399) {
            val f = minOf(2f, 2400f / long)
            Bitmap.createScaledBitmap(bm, (bm.width * f).toInt(), (bm.height * f).toInt(), true)
        } else bm
        try {
            val w = src.width
            val h = src.height
            val px = IntArray(w * h)
            src.getPixels(px, 0, w, 0, 0, w, h)
            return PdfPage(Bitonal.encode(px, w, h), w, h, exactA4 = exactA4, bitonal = true)
        } finally {
            if (src !== bm) src.recycle()
        }
    }
}
