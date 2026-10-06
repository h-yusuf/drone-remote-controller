package com.zora.drone.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zora.drone.control.MotorTest
import com.zora.drone.net.DroneLink
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

// Index 0..3 = M1..M4. Position, wire colour and spin from esp-drone docs/WIRING.md §7.
private val MOTORS = listOf(
    "M1" to "depan-kanan · PH · CCW",
    "M2" to "belakang-kanan · MB · CW",
    "M3" to "belakang-kiri · PH · CCW",
    "M4" to "depan-kiri · MB · CW",
)
private val ACCENT = Color(0xFF4FC3F7)
private val RED = Color(0xFFD32F2F)

@Composable
fun MotorTestScreen(test: MotorTest, link: DroneLink) {
    val connected by link.connected.collectAsState()
    val param by link.paramError.collectAsState()
    BackHandler { test.exit() }

    // Ramp: 10 % -> 100 %, +5 % every 2 s (same as tools/thrusttest), then stop.
    val ramping = test.ramp != null
    LaunchedEffect(ramping) {
        while (test.ramp != null) {
            delay(2000)
            test.ramp = test.ramp?.let { if (it >= 100) null else it + 5 }
        }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF101418))) {
        Text(
            "LEPAS PROPELLER", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().background(RED).padding(8.dp),
        )
        Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            // Top view of the drone, front up.
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("▲ DEPAN", color = Color.Gray, modifier = Modifier.align(Alignment.CenterHorizontally))
                for (row in listOf(listOf(3, 0), listOf(2, 1))) {
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        for (m in row) {
                            HoldButton(
                                MOTORS[m].first, "${MOTORS[m].second}\n${test.percentOf(m)} %",
                                pressed = test.percentOf(m) > 0,
                                onPress = { test.held = if (it) test.held + m else test.held - m },
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            )
                        }
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Status("Drone", if (connected) "connected" else "no reply", connected)
                    Status("Firmware", when (param) {
                        null -> "menunggu balasan…"
                        0 -> "OK"
                        2 -> "param tidak ada (firmware lama?)"
                        else -> "error $param"
                    }, param == 0)
                }
                Text("PWM ${test.percent} % (${test.percent * 65535 / 100})", color = Color.White, fontSize = 18.sp)
                Slider(
                    value = test.percent.toFloat(), onValueChange = { test.percent = it.roundToInt() },
                    valueRange = 0f..100f, steps = 19,
                )
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    HoldButton("SEMUA", "tahan", test.all, { test.all = it }, Modifier.weight(1f).fillMaxHeight())
                    Button(
                        onClick = { test.ramp = if (ramping) null else 10 },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    ) { Text(test.ramp?.let { "RAMP $it %\n(tap = stop)" } ?: "RAMP\n10→100 %", textAlign = TextAlign.Center) }
                }
                Row(Modifier.height(64.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = test::stop, colors = ButtonDefaults.buttonColors(containerColor = RED),
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    ) { Text("STOP", fontSize = 22.sp, fontWeight = FontWeight.Black) }
                    OutlinedButton(onClick = test::exit, modifier = Modifier.weight(1f).fillMaxHeight()) {
                        Text("KELUAR", color = Color.White)
                    }
                }
            }
        }
    }
}

/** Runs only while a finger is on it. */
@Composable
private fun HoldButton(label: String, sub: String, pressed: Boolean, onPress: (Boolean) -> Unit, modifier: Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(16.dp))
            .background(if (pressed) ACCENT else Color(0xFF2A3038))
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    onPress(true)
                    waitForUpOrCancellation()
                    onPress(false)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, color = if (pressed) Color.Black else Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black)
            Text(sub, color = if (pressed) Color.Black else Color.Gray, fontSize = 13.sp, textAlign = TextAlign.Center)
        }
    }
}
