package com.zora.drone

import com.zora.drone.control.MAX_THRUST_APP
import com.zora.drone.control.axis
import com.zora.drone.control.thrustOf
import com.zora.drone.net.RSSI_AT_1M
import com.zora.drone.net.distanceM
import com.zora.drone.proto.Crtp
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CrtpTest {
    @Test fun zeroSetpoint() {
        val b = Crtp.encodeSetpoint(0f, 0f, 0f, 0)
        assertEquals(16, b.size)
        assertArrayEquals(ByteArray(16).also { it[0] = 0x30; it[15] = 0x30 }, b)
    }

    @Test fun handComputedPacket() {
        // roll=1.0 (0x3F800000), pitch=0, yaw=0, thrust=1000 (0x03E8), all LE
        val expected = byteArrayOf(0x30, 0, 0, 0x80.toByte(), 0x3F, 0, 0, 0, 0, 0, 0, 0, 0, 0xE8.toByte(), 0x03, 0)
        expected[15] = (0x30 + 0x80 + 0x3F + 0xE8 + 0x03).toByte()
        assertArrayEquals(expected, Crtp.encodeSetpoint(1f, 0f, 0f, 1000))
    }

    @Test fun mapping() {
        assertEquals(0f, axis(0.04f), 0f)
        assertEquals(1f, axis(1f), 1e-6f)
        assertEquals(-1f, axis(-1f), 1e-6f)
        assertEquals(0, thrustOf(-1f))
        assertEquals(MAX_THRUST_APP, thrustOf(1f))
    }

    @Test fun echoRoundTrip() {
        val b = Crtp.echo(123456789L).copyOf(64)
        assertEquals(123456789L, Crtp.echoTime(b, 10))
        b[3] = (b[3] + 1).toByte()
        assertEquals(null, Crtp.echoTime(b, 10)) // checksum catches corruption
        assertEquals(null, Crtp.echoTime(Crtp.encodeSetpoint(0f, 0f, 0f, 0), 16))
    }

    @Test fun distance() {
        assertEquals(1f, distanceM(RSSI_AT_1M), 1e-4f)
        assertEquals(10f, distanceM(RSSI_AT_1M - 25), 1e-3f) // n=2.5: -25 dB = 10x
    }

    @Test fun setParamByName() {
        // Blueprint §11 example: m1 = 20000 -> 23 00 "motorPowerSet" 00 "m1" 00 09 20 4E + checksum
        val body = byteArrayOf(0x23, 0) + "motorPowerSet".toByteArray() + 0 + "m1".toByteArray() + 0 +
            byteArrayOf(0x09, 0x20, 0x4E)
        val expected = body + body.sumOf { it.toInt() and 0xFF }.toByte()
        assertArrayEquals(expected, Crtp.setParam("motorPowerSet", "m1", 20000, uint16 = true))
        assertEquals(0x08.toByte(), Crtp.setParam("motorPowerSet", "enable", 1, uint16 = false).let { it[it.size - 3] })
    }

    @Test fun paramReply() {
        // Reply = request with the type byte replaced by the error code and the value dropped.
        fun reply(err: Int): ByteArray {
            val body = byteArrayOf(0x23, 0) + "motorPowerSet".toByteArray() + 0 + "m1".toByteArray() + 0 + err.toByte()
            return body + body.sumOf { it.toInt() and 0xFF }.toByte()
        }
        assertEquals(0, reply(0).let { Crtp.paramReplyError(it, it.size) })
        assertEquals(2, reply(2).let { Crtp.paramReplyError(it, it.size) })
        assertEquals(null, Crtp.echo(1).let { Crtp.paramReplyError(it, it.size) })
    }

    @Test fun motorTestPwm() {
        val t = com.zora.drone.control.MotorTest()
        assertEquals(0, t.pwm(0)) // inactive -> always 0
        t.enter(); t.held = setOf(0)
        assertEquals(20 * 65535 / 100, t.pwm(0))
        assertEquals(0, t.pwm(1))
        t.ramp = 100
        assertEquals(65535, t.pwm(3))
        t.exit()
        assertEquals(0, t.pwm(0))
    }
}
