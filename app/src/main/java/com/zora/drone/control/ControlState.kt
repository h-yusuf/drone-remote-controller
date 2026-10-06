package com.zora.drone.control

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import com.zora.drone.proto.Crtp
import kotlin.math.abs
import kotlin.math.sign

// Tuning knobs. Calibrate on the bench before flying with props.
const val MAX_THRUST_APP = 60000 // = firmware MAX_THRUST (stock); lower to limit power
const val MAX_ANGLE = 15f        // deg, roll/pitch
const val MAX_YAW = 150f         // deg/s
const val DEADZONE = 0.05f
const val EXPO = 0.3f
const val PITCH_SIGN = 1f        // flip to -1f if stick forward raises the rear motors

/** Deadzone, then expo: x^3*e + x*(1-e). Input/output -1..1. */
fun axis(v: Float): Float {
    if (abs(v) < DEADZONE) return 0f
    val x = (abs(v) - DEADZONE) / (1 - DEADZONE) * sign(v)
    return x * x * x * EXPO + x * (1 - EXPO)
}

/** Left stick vertical -1 (bottom)..1 (top) -> 0..MAX_THRUST_APP. */
fun thrustOf(y: Float): Int {
    val t = (y.coerceIn(-1f, 1f) + 1) / 2
    return if (t < DEADZONE) 0 else ((t - DEADZONE) / (1 - DEADZONE) * MAX_THRUST_APP).toInt()
}

/** Sticks in -1..1, y up = positive. Read by the 50 Hz loop on a background thread. */
class ControlState {
    var left by mutableStateOf(Offset(0f, -1f))
    var right by mutableStateOf(Offset.Zero)
    var armed by mutableStateOf(false)
        private set
    /** Thrust held at 0 until the throttle stick has been seen at the bottom. */
    var locked by mutableStateOf(true)
        private set
    var thrust by mutableStateOf(0)
        private set

    fun arm(on: Boolean) { armed = on; locked = true }
    fun stop() { armed = false; locked = true }

    fun packet(): ByteArray {
        val raw = thrustOf(left.y)
        if (raw == 0 && locked) locked = false
        val t = if (armed && !locked) raw else 0
        if (t != thrust) thrust = t
        return Crtp.encodeSetpoint(
            roll = axis(right.x) * MAX_ANGLE,
            pitch = PITCH_SIGN * axis(right.y) * MAX_ANGLE,
            yaw = axis(left.x) * MAX_YAW,
            thrust = t,
        )
    }
}
