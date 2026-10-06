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

    /**
     * Param set-by-name (port 2, ch 3, MISC_SETBYNAME) + checksum, param.c:181-207.
     * Type byte must match the firmware's exactly: 0x08 = uint8, 0x09 = uint16 (param.h:157-159).
     */
    fun setParam(group: String, name: String, value: Int, uint16: Boolean): ByteArray {
        val g = group.toByteArray()
        val n = name.toByteArray()
        val b = ByteBuffer.allocate(2 + g.size + 1 + n.size + 1 + 1 + (if (uint16) 2 else 1) + 1)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(0x23.toByte()).put(0.toByte()).put(g).put(0.toByte()).put(n).put(0.toByte())
        if (uint16) b.put(0x09.toByte()).putShort(value.toShort()) else b.put(0x08.toByte()).put(value.toByte())
        val a = b.array()
        a[a.size - 1] = checksum(a, a.size - 1)
        return a
    }

    /** Error code from a set-by-name reply (0 = OK, ENOENT = unknown name, EINVAL = wrong type); null if not one. */
    fun paramReplyError(b: ByteArray, len: Int): Int? {
        if (len < 5 || (b[0].toInt() and 0xF3) != 0x23 || b[1].toInt() != 0) return null
        if (b[len - 1] != checksum(b, len - 1)) return null
        var zeros = 0
        for (i in 2 until len - 2) {
            if (b[i].toInt() == 0 && ++zeros == 2) return b[i + 1].toInt() and 0xFF
        }
        return null
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
