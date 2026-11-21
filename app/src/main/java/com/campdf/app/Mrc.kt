package com.campdf.app

/**
 * v3.10 MRC ("mixed raster content", the trick behind CamScanner's small files) for the Compact quality:
 * a document page is stored as two layers -
 *   - text: the dark, colourless ink as a sharp 1-bit mask at full resolution (Bitonal, drawn in black), and
 *   - background: everything else (paper, coloured stamps, seals, pictures) as a small JPEG at half size.
 * The page looks the same, letters stay crisp, and the file is several times smaller than one big JPEG.
 * Coloured ink (blue / red pen, stamps) is NOT put into the black text layer - it stays in colour.
 * Plain Kotlin: tested on the PC.
 */
object Mrc {

    /** A pixel belongs to the text layer: dark and (nearly) without colour. */
    fun isText(p: Int): Boolean {
        val r = (p shr 16) and 255
        val g = (p shr 8) and 255
        val b = p and 255
        val l = (r * 299 + g * 587 + b * 114) / 1000
        val c = maxOf(r, maxOf(g, b)) - minOf(r, minOf(g, b))
        return l < 165 && c < 40
    }

    /**
     * Splits [argb] (w*h) into the text mask (zlib 1-bit rows, 0 = ink, as Bitonal) and the background pixels.
     */
    fun split(argb: IntArray, w: Int, h: Int): Pair<ByteArray, IntArray> {
        val text = BooleanArray(w * h) { isText(argb[it]) }
        // mask image for Bitonal: white (= no ink) everywhere except text pixels
        val maskPx = IntArray(w * h) { if (text[it]) 0xFF000000.toInt() else -1 }
        val mask = Bitonal.encode(maskPx, w, h)
        // The letters also stay in the background (small and soft there): where the 1-bit layer misses a faint,
        // thin part of a stroke, the background still shows it, so no letter can break up.
        return mask to argb
    }

    /** True when the page has enough black writing for MRC to pay off (else a single JPEG is kept). */
    fun worthIt(argb: IntArray): Boolean {
        var n = 0
        var i = 0
        while (i < argb.size) { if (isText(argb[i])) n++; i += 7 }
        val share = n * 7.0 / argb.size
        return share in 0.004..0.35
    }
}
