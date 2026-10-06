package com.zora.drone.net

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import com.zora.drone.control.ControlState
import com.zora.drone.control.MotorTest
import com.zora.drone.proto.Crtp
import com.zora.drone.proto.Telemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import kotlin.math.pow

/**
 * Binds a UDP socket to the drone's WiFi (no-internet network, which Android 10+ won't route by default),
 * sends setpoints at 50 Hz and tracks replies. User joins ESP-DRONE_xxx manually (blueprint option A).
 */
const val DRONE_IP = "192.168.43.42"
const val DRONE_SUBNET = "192.168.43."

// Distance from RSSI, log-distance path loss. Calibrate: read dBm with the drone 1 m away -> RSSI_AT_1M;
// raise PATH_LOSS_N (2 open field .. 3.5 indoors) if the estimate reads short at range.
// ponytail: RSSI is noisy (+-50% distance error); a real range needs GPS/UWB on the drone.
const val RSSI_AT_1M = -40
const val PATH_LOSS_N = 2.5f

fun distanceM(rssi: Int): Float = 10f.pow((RSSI_AT_1M - rssi) / (10 * PATH_LOSS_N))

class DroneLink(
    private val cm: ConnectivityManager,
    private val state: ControlState,
    private val motorTest: MotorTest,
) {
    /** IPv4 of the bound WiFi, null while searching. SSID needs location permission; the drone subnet is enough. */
    val wifiIp = MutableStateFlow<String?>(null)
    val connected = MutableStateFlow(false)
    /** Tablet-side WiFi RSSI in dBm. */
    val rssi = MutableStateFlow<Int?>(null)
    /** Echo round-trip time, smoothed. */
    val rttMs = MutableStateFlow<Float?>(null)
    /** Last motor-test param reply: 0 = accepted, else firmware errno. Null until one arrives. */
    val paramError = MutableStateFlow<Int?>(null)
    /** Latest telemetry (10 Hz while connected), null until one arrives. */
    val telemetry = MutableStateFlow<Telemetry?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val drone = InetSocketAddress(DRONE_IP, 2390)
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var lastRx = 0L
    private var sendJob: Job? = null
    private var rxJob: Job? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            socket?.close()
            socket = DatagramSocket().apply { network.bindSocket(this); soTimeout = 500 }
        }

        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
            wifiIp.value = lp.linkAddresses.map { it.address }.filterIsInstance<Inet4Address>()
                .firstOrNull()?.hostAddress
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            rssi.value = caps.signalStrength.takeIf { it != NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED }
        }

        override fun onLost(network: Network) {
            socket?.close()
            socket = null
            wifiIp.value = null
            rssi.value = null
        }
    }

    fun start() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.requestNetwork(request, callback)
        sendJob = scope.launch {
            var tick = 0
            var testOn = false
            while (isActive) {
                send(state.packet())
                if (tick % 10 == 0) send(Crtp.echo(System.nanoTime())) // 5 Hz
                if (motorTest.active) {
                    if (!testOn) paramError.value = null
                    testOn = true
                    // Resend every 100 ms: UDP may drop, and the firmware drops out of test mode after 1 s of silence.
                    if (tick % 5 == 0) sendMotors(enable = true, motorTest::pwm)
                } else if (testOn) {
                    testOn = false
                    repeat(3) { sendMotors(enable = false) }
                }
                tick++
                connected.value = SystemClock.elapsedRealtime() - lastRx < 1000
                delay(20)
            }
        }
        rxJob = scope.launch {
            val pkt = DatagramPacket(ByteArray(64), 64)
            while (isActive) {
                val s = socket
                if (s == null) { delay(100); continue }
                try {
                    pkt.length = pkt.data.size
                    s.receive(pkt)
                    lastRx = SystemClock.elapsedRealtime()
                    Crtp.paramReplyError(pkt.data, pkt.length)?.let { paramError.value = it }
                    Crtp.telemetry(pkt.data, pkt.length)?.let { telemetry.value = it }
                    Crtp.echoTime(pkt.data, pkt.length)?.let {
                        val ms = (System.nanoTime() - it) / 1e6f
                        rttMs.value = rttMs.value?.let { old -> old * 0.8f + ms * 0.2f } ?: ms
                    }
                } catch (_: IOException) {}
            }
        }
    }

    /** Stop the loop, send thrust 0 five times, then release the network. */
    fun stop() {
        val loop = sendJob ?: return
        val rx = rxJob
        sendJob = null
        rxJob = null
        runCatching { cm.unregisterNetworkCallback(callback) } // sync, so a quick start() can re-register
        val s = socket
        scope.launch {
            loop.cancel()
            loop.join()
            val zero = Crtp.encodeSetpoint(0f, 0f, 0f, 0)
            repeat(5) { send(zero, s); delay(20) }
            repeat(3) { sendMotors(enable = false, s = s) } // harmless if test mode was never on
            rx?.cancel()
            s?.close()
            if (socket === s) socket = null
            wifiIp.value = null
            connected.value = false
            rssi.value = null
            rttMs.value = null
            telemetry.value = null
        }
    }

    /**
     * Level calibration (blueprint §12): 1 = take the current pose as 0°/0° and save to NVS, 2 = clear.
     * Sent twice; the firmware ignores rewrites while it samples.
     */
    fun setLevel(trigger: Int) {
        scope.launch {
            repeat(2) {
                send(Crtp.setParam("levelCal", "trigger", trigger, uint16 = false))
                delay(20)
            }
        }
    }

    /** m1..m4 first, then enable, so entering/leaving test mode never spins a motor at a stale value. */
    private fun sendMotors(enable: Boolean, pwm: (Int) -> Int = { 0 }, s: DatagramSocket? = socket) {
        for (i in 0..3) send(Crtp.setParam("motorPowerSet", "m${i + 1}", pwm(i), uint16 = true), s)
        send(Crtp.setParam("motorPowerSet", "enable", if (enable) 1 else 0, uint16 = false), s)
    }

    private fun send(b: ByteArray, s: DatagramSocket? = socket) {
        if (s == null) return
        try { s.send(DatagramPacket(b, b.size, drone)) } catch (_: IOException) {}
    }
}
