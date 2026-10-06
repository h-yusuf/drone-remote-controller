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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.zora.drone.proto.Telemetry
import kotlin.math.abs

@Composable
fun FlyScreen(state: ControlState, link: DroneLink, onMotorTest: () -> Unit) {
    val ip by link.wifiIp.collectAsState()
    val onDrone = ip?.startsWith(DRONE_SUBNET) == true
    val connected by link.connected.collectAsState()
    val rssi by link.rssi.collectAsState()
    val rtt by link.rttMs.collectAsState()
    val tel by link.telemetry.collectAsState()
    var levelDialog by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Color(0xFF101418)).padding(24.dp)) {
        val t = tel?.takeIf { connected }
        Row(Modifier.align(Alignment.TopCenter), horizontalArrangement = Arrangement.spacedBy(20.dp),
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
            Status("Tilt", t?.let { "R %.1f° P %.1f°".format(it.roll, it.pitch) } ?: "–",
                t != null && abs(t.roll) < 2 && abs(t.pitch) < 2)
            Status("Batt", t?.let { "%.2f V".format(it.vbat) } ?: "–", (t?.vbat ?: 0f) >= 3.5f)
            Text("thrust ${state.thrust}" + if (state.armed && state.locked) " (lower stick)" else "",
                color = Color.White, fontSize = 16.sp)
            Text("ARM", color = Color.White, fontWeight = FontWeight.Bold)
            Switch(checked = state.armed, onCheckedChange = state::arm)
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
        // Only while disarmed, so test mode / calibration never overlap with flying.
        Column(Modifier.align(Alignment.BottomCenter), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { levelDialog = true }, enabled = !state.armed && t != null) {
                Text("SET LEVEL 0°", color = Color.White)
            }
            OutlinedButton(onClick = onMotorTest, enabled = !state.armed) { Text("TEST MOTOR", color = Color.White) }
        }
    }
    if (levelDialog) LevelDialog(tel, onDismiss = { levelDialog = false }) { trigger ->
        link.setLevel(trigger)
        levelDialog = false
    }
}

@Composable
private fun LevelDialog(t: Telemetry?, onDismiss: () -> Unit, onTrigger: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set level 0°") },
        text = {
            Text(
                "Taruh drone di permukaan datar dengan posisi seperti saat terbang (baterai terpasang), jangan disentuh. " +
                    "Posisi sekarang dijadikan 0° dan disimpan permanen di drone.\n\n" +
                    (t?.let {
                        "Sekarang: R %.1f° P %.1f°\nKalibrasi lama: R %.1f° P %.1f°"
                            .format(it.roll, it.pitch, it.calRoll, it.calPitch)
                    } ?: "Belum ada telemetri.")
            )
        },
        confirmButton = { TextButton(onClick = { onTrigger(1) }) { Text("SET LEVEL") } },
        dismissButton = {
            Row {
                TextButton(onClick = { onTrigger(2) }) { Text("Hapus kalibrasi") }
                TextButton(onClick = onDismiss) { Text("Batal") }
            }
        },
    )
}

@Composable
internal fun Status(label: String, value: String, ok: Boolean) {
    Column {
        Text(label, color = Color.Gray, fontSize = 12.sp)
        Text(value, color = if (ok) Color(0xFF66BB6A) else Color(0xFFFFA726), fontSize = 16.sp)
    }
}
