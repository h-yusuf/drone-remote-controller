package com.zora.drone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zora.drone.control.ControlState
import com.zora.drone.net.DRONE_SUBNET
import com.zora.drone.net.DroneLink
import com.zora.drone.net.distanceM

@Composable
fun FlyScreen(state: ControlState, link: DroneLink, onMotorTest: () -> Unit) {
    val ip by link.wifiIp.collectAsState()
    val onDrone = ip?.startsWith(DRONE_SUBNET) == true
    val connected by link.connected.collectAsState()
    val rssi by link.rssi.collectAsState()
    val rtt by link.rttMs.collectAsState()
    Box(Modifier.fillMaxSize().background(Color(0xFF101418)).padding(24.dp)) {
        Row(Modifier.align(Alignment.TopCenter), horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Status("WiFi", when {
                ip == null -> "searching…"
                onDrone -> "drone ($ip)"
                else -> "NOT drone WiFi ($ip)"
            }, onDrone)
            Status("Drone", if (connected) "connected" else "no reply", connected)
            Status("Range", rssi?.let { "~%.0f m (%d dBm)".format(distanceM(it), it) } ?: "–",
                (rssi ?: -100) > -75)
            Status("Ping", rtt?.takeIf { connected }?.let { "%.0f ms".format(it) } ?: "–",
                connected && (rtt ?: 999f) < 100)
            Text("thrust ${state.thrust}" + if (state.armed && state.locked) " (lower stick)" else "",
                color = Color.White, fontSize = 16.sp)
            Text("ARM", color = Color.White, fontWeight = FontWeight.Bold)
            Switch(checked = state.armed, onCheckedChange = state::arm)
            // Only while disarmed, so test mode never overlaps with flying.
            OutlinedButton(onClick = onMotorTest, enabled = !state.armed) { Text("TEST MOTOR", color = Color.White) }
        }
        Joystick(state.left, { state.left = it }, centerY = false,
            Modifier.align(Alignment.BottomStart).fillMaxHeight(0.75f))
        Joystick(state.right, { state.right = it }, centerY = true,
            Modifier.align(Alignment.BottomEnd).fillMaxHeight(0.75f))
        Button(
            onClick = state::stop,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
            modifier = Modifier.align(Alignment.Center).size(160.dp),
        ) { Text("STOP", fontSize = 32.sp, fontWeight = FontWeight.Black) }
    }
}

@Composable
internal fun Status(label: String, value: String, ok: Boolean) {
    Column {
        Text(label, color = Color.Gray, fontSize = 12.sp)
        Text(value, color = if (ok) Color(0xFF66BB6A) else Color(0xFFFFA726), fontSize = 16.sp)
    }
}
