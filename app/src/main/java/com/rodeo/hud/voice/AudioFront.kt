package com.rodeo.hud.voice

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.roundToInt

/**
 * Cleans the microphone signal before recognition: a high-pass filter that removes the engine's
 * thump and helmet wind rumble, which sit below speech but carry most of the noise energy on a
 * bike. Speech above [cutoffHz] is untouched.
 */
class HighPass(cutoffHz: Double = 200.0, rate: Int = 16_000) {
    private val b0: Double
    private val b1: Double
    private val b2: Double
    private val a1: Double
    private val a2: Double
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    init {
        // Second-order Butterworth (Q = 1/√2), from the RBJ audio EQ cookbook.
        val w0 = 2 * PI * cutoffHz / rate
        val alpha = sin(w0) / (2 * 0.7071067811865476)
        val c = cos(w0)
        val a0 = 1 + alpha
        b0 = (1 + c) / 2 / a0
        b1 = -(1 + c) / a0
        b2 = (1 + c) / 2 / a0
        a1 = -2 * c / a0
        a2 = (1 - alpha) / a0
    }

    /** Filters [n] samples of [input] into [out] (may be the same array). */
    fun process(input: ShortArray, n: Int, out: ShortArray) {
        for (i in 0 until n) {
            val x = input[i].toDouble()
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x
            y2 = y1; y1 = y
            out[i] = y.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    fun reset() {
        x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0
    }
}
