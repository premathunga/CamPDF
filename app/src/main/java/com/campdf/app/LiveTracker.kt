package com.campdf.app

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Turns the page detections of successive camera preview frames into what the camera screen shows:
 * a smoothed page outline, and an "auto-capture" progress that fills up while the page is held steady.
 *
 * v3.3.1 (after the first phone test: auto-capture took far too long, the outline jumped):
 * - Detections of a still page wobble by ~2-3% of the frame from frame to frame (measured), so steadiness is judged
 *   on the SMOOTHED outline and on the average corner movement, with a 3.5% tolerance.
 * - A single frame whose outline is far (> 8%) from the current one is treated as a glitch and ignored; if the next
 *   frame agrees with it, the page really moved and the outline jumps there.
 * - The outline is hidden only after 3 frames in a row without a page.
 * - Pages smaller than [minArea] of the frame never trigger a capture (too far away to be readable).
 * Plain Kotlin, no Android types: unit-tested on the PC (tools/jvmtest/LiveTest.kt).
 */
class LiveTracker(
    private val holdMs: Long = 1000L,
    private val tolerance: Float = 0.035f,
    private val minArea: Float = 0.12f,
    private val jump: Float = 0.08f
) {
    class State(val quad: FloatArray?, val progress: Float) {
        val ready: Boolean get() = progress >= 1f
    }

    private var shown: FloatArray? = null
    private var pending: FloatArray? = null
    private var misses = 0
    private var anchor: FloatArray? = null
    private var steadySince = -1L

    fun reset() {
        shown = null
        pending = null
        misses = 0
        anchor = null
        steadySince = -1L
    }

    /** q = normalized TL,TR,BR,BL of the page in this frame (null = no page found), t = frame time in ms. */
    fun update(q: FloatArray?, t: Long): State {
        if (q == null || q.size != 8 || !plausible(q)) {
            misses++
            if (misses >= 3) {
                shown = null
                anchor = null
                steadySince = -1L
            }
            return State(shown?.copyOf(), progress(t))
        }
        misses = 0
        val s = shown
        if (s == null) {
            shown = q.copyOf()
        } else if (maxMove(s, q) > jump) {
            val p = pending
            if (p != null && maxMove(p, q) <= jump) {
                // two frames agree: the page really moved
                shown = q.copyOf()
                pending = null
                anchor = null
                steadySince = -1L
            } else {
                pending = q.copyOf() // possible glitch: wait for the next frame
                return State(s.copyOf(), progress(t))
            }
        } else {
            pending = null
            shown = FloatArray(8) { s[it] + (q[it] - s[it]) * 0.4f }
        }
        val cur = shown!!
        val a = anchor
        if (area(cur) < minArea) {
            anchor = null
            steadySince = -1L
        } else if (a == null || meanMove(a, cur) > tolerance) {
            anchor = cur.copyOf()
            steadySince = t
        }
        return State(cur.copyOf(), progress(t))
    }

    private fun progress(t: Long): Float =
        if (steadySince < 0 || shown == null) 0f else ((t - steadySince).toFloat() / holdMs).coerceIn(0f, 1f)

    private fun maxMove(a: FloatArray, b: FloatArray): Float {
        var m = 0f
        for (i in 0 until 4) m = max(m, hypot(a[2 * i] - b[2 * i], a[2 * i + 1] - b[2 * i + 1]))
        return m
    }

    private fun meanMove(a: FloatArray, b: FloatArray): Float {
        var m = 0f
        for (i in 0 until 4) m += hypot(a[2 * i] - b[2 * i], a[2 * i + 1] - b[2 * i + 1])
        return m / 4f
    }

    companion object {
        /** Shoelace area of a normalized quad (fraction of the frame). */
        fun area(q: FloatArray): Float {
            var s = 0f
            for (i in 0 until 4) {
                val j = (i + 1) % 4
                s += q[2 * i] * q[2 * j + 1] - q[2 * j] * q[2 * i + 1]
            }
            return abs(s) / 2f
        }

        /** Convex, not degenerate. */
        fun plausible(q: FloatArray): Boolean {
            if (q.any { !it.isFinite() }) return false
            var sgn = 0
            for (i in 0 until 4) {
                val j = (i + 1) % 4
                val k = (i + 2) % 4
                val c = (q[2 * j] - q[2 * i]) * (q[2 * k + 1] - q[2 * j + 1]) - (q[2 * j + 1] - q[2 * i + 1]) * (q[2 * k] - q[2 * j])
                if (abs(c) < 1e-5f) return false
                val s = if (c > 0) 1 else -1
                if (sgn != 0 && s != sgn) return false
                sgn = s
            }
            return area(q) > 0.02f
        }
    }
}
