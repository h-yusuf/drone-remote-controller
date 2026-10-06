package com.zora.drone.proto

import java.nio.ByteBuffer
import java.nio.ByteOrder

object Crtp {
    /**
     * Link echo (port 15, ch 0) carrying a timestamp, + checksum. Setpoints get no reply, so the
     * echo is how we detect the drone and measure round-trip time (crtpservice.c echoes it as-is).
     */
    fun echo(timeNanos: Long): ByteArray {
        val a = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN).put(0xF0.toByte()).putLong(timeNanos).array()
        a[9] = checksum(a, 9)
        return a
    }

    /** Timestamp from an echo reply; null if not an echo or bad checksum. */
    fun echoTime(b: ByteArray, len: Int): Long? {
        if (len != 10 || (b[0].toInt() and 0xF3) != 0xF0 || b[9] != checksum(b, 9)) return null
        return ByteBuffer.wrap(b, 1, 8).order(ByteOrder.LITTLE_ENDIAN).long
    }

    private fun checksum(a: ByteArray, n: Int) = (0 until n).sumOf { a[it].toInt() and 0xFF }.toByte()

    /** Commander setpoint (port 3, ch 0) + 1-byte checksum. Layout: crtp_commander_rpyt.c:48, wifi_esp32.c:54. */
    fun encodeSetpoint(roll: Float, pitch: Float, yaw: Float, thrust: Int): ByteArray {
        val a = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .put(0x30.toByte()).putFloat(roll).putFloat(pitch).putFloat(yaw)
            .putShort(thrust.coerceIn(0, 60000).toShort())
            .array()
        a[15] = checksum(a, 15)
        return a
    }
}
