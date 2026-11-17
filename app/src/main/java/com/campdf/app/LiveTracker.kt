package com.campdf.app

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * v3.3: turns the page detections of successive camera preview frames into what the camera screen shows:
 * a smoothed page outline, and an "auto-capture" progress that fills up while the page is held steady.
 *
 * - The outline follows new detections half-way each frame (no jumping), and is hidden only after 3 frames in a
 *   row without a page (one missed frame does not make it flicker).
 * - The page counts as steady while no corner moves more than [tolerance] (fraction of the frame) away from where
 *   it was when the steady period started. Progress = steady time / [holdMs]; 1 = take the photo.
 * - Pages smaller than [minArea] of the frame never trigger a capture (too far away to be readable).
 * Plain Kotlin, no Android types, so it is unit-tested on the PC (tools/jvmtest).
 */
class LiveTracker(
    private val holdMs: Long = 1200L,
    private val tolerance: Float = 0.025f,
    private val minArea: Float = 0.12f
) {
    class State(val quad: FloatArray?, val progress: Float) {
        val ready: Boolean get() = progress >= 1f
    }

    private var shown: FloatArray? = null
    private var misses = 0
    private var anchor: FloatArray? = null
    private var steadySince = -1L

    fun reset() {
        shown = null
        misses = 0
        anchor = null
        steadySince = -1L
    }

    /** q = normalized TL,TR,BR,BL of the page in this frame (null = no page found), t = frame time in ms. */
    fun update(q: FloatArray?, t: Long): State {
        if (q == null || q.size != 8 || !plausible(q)) {
            misses++
            anchor = null
            steadySince = -1L
            if (misses >= 3) shown = null
            return State(shown?.copyOf(), 0f)
        }
        misses = 0
        val s = shown
        shown = if (s == null) q.copyOf() else FloatArray(8) { s[it] + (q[it] - s[it]) * 0.5f }
        val a = anchor
        if (area(q) < minArea) {
            anchor = null
            steadySince = -1L
        } else if (a == null || maxMove(a, q) > tolerance) {
            anchor = q.copyOf()
            steadySince = t
        }
        val progress = if (steadySince < 0) 0f else ((t - steadySince).toFloat() / holdMs).coerceIn(0f, 1f)
        return State(shown?.copyOf(), progress)
    }

    private fun maxMove(a: FloatArray, b: FloatArray): Float {
        var m = 0f
        for (i in 0 until 4) m = max(m, hypot(a[2 * i] - b[2 * i], a[2 * i + 1] - b[2 * i + 1]))
        return m
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
