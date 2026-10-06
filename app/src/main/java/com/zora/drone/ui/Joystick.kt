package com.zora.drone.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.Composable

/**
 * Touch-anywhere pad. Value in -1..1, y up = positive. X always re-centers on release;
 * Y re-centers only when [centerY] (throttle stick stays where it was left, like an RC throttle).
 */
@Composable
fun Joystick(value: Offset, onChange: (Offset) -> Unit, centerY: Boolean, modifier: Modifier = Modifier) {
    Canvas(
        modifier.aspectRatio(1f).pointerInput(centerY) {
            fun norm(p: Offset) = Offset(
                (p.x / size.width * 2 - 1).coerceIn(-1f, 1f),
                (1 - p.y / size.height * 2).coerceIn(-1f, 1f),
            )
            awaitEachGesture {
                val down = awaitFirstDown()
                val id = down.id
                var last = norm(down.position)
                onChange(last)
                while (true) {
                    val c = awaitPointerEvent().changes.firstOrNull { it.id == id } ?: break
                    if (!c.pressed) break
                    last = norm(c.position)
                    onChange(last)
                    c.consume()
                }
                onChange(Offset(0f, if (centerY) 0f else last.y))
            }
        }
    ) {
        val r = size.minDimension / 2
        drawCircle(Color(0x33FFFFFF), r)
        drawCircle(Color(0x66FFFFFF), r, style = Stroke(3f))
        drawLine(Color(0x33FFFFFF), Offset(center.x, 0f), Offset(center.x, size.height))
        drawLine(Color(0x33FFFFFF), Offset(0f, center.y), Offset(size.width, center.y))
        drawCircle(Color(0xFF4FC3F7), r * 0.22f, center + Offset(value.x * r, -value.y * r))
    }
}
