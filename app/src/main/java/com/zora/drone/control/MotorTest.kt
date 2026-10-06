package com.zora.drone.control

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Bench test: drive M1..M4 directly through firmware param motorPowerSet (bypasses the stabilizer).
 * Motor index 0..3 = M1..M4. Read by the 50 Hz loop on a background thread.
 */
class MotorTest {
    var active by mutableStateOf(false)
        private set
    /** Slider, 0..100 %. */
    var percent by mutableStateOf(20)
    /** Motors whose hold button is pressed. */
    var held by mutableStateOf(emptySet<Int>())
    var all by mutableStateOf(false)
    /** Ramp step in %, null when not ramping. */
    var ramp by mutableStateOf<Int?>(null)

    fun enter() { stop(); active = true }
    fun exit() { stop(); active = false }
    fun stop() { held = emptySet(); all = false; ramp = null }

    fun percentOf(motor: Int): Int = if (!active) 0 else ramp ?: if (all || motor in held) percent else 0
    fun pwm(motor: Int): Int = percentOf(motor) * 65535 / 100
}
