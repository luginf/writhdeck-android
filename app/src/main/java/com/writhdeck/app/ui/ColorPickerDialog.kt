package com.writhdeck.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.writhdeck.app.R
import kotlin.math.roundToInt

private fun pickerParseHex(hex: String): Color {
    val s = hex.trim().removePrefix("#")
    return try {
        if (s.length == 6) Color(0xFF000000.toInt() or s.toInt(16)) else Color.Gray
    } catch (_: Exception) { Color.Gray }
}

private fun pickerColorToHex(color: Color): String =
    "%02x%02x%02x".format(
        (color.red   * 255).roundToInt().coerceIn(0, 255),
        (color.green * 255).roundToInt().coerceIn(0, 255),
        (color.blue  * 255).roundToInt().coerceIn(0, 255),
    )

private fun Color.toHSV(): FloatArray {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(
        android.graphics.Color.argb(
            255,
            (red   * 255).roundToInt().coerceIn(0, 255),
            (green * 255).roundToInt().coerceIn(0, 255),
            (blue  * 255).roundToInt().coerceIn(0, 255),
        ),
        hsv
    )
    return hsv
}

private fun hsvToColor(h: Float, s: Float, v: Float): Color =
    Color(android.graphics.Color.HSVToColor(floatArrayOf(
        h.coerceIn(0f, 360f),
        s.coerceIn(0f, 1f),
        v.coerceIn(0f, 1f),
    )))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColorPickerDialog(
    initialHex: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val initColor = remember(initialHex) { pickerParseHex(initialHex) }
    val initHSV   = remember(initColor)  { initColor.toHSV() }

    var hue      by remember { mutableFloatStateOf(initHSV[0]) }
    var sat      by remember { mutableFloatStateOf(initHSV[1]) }
    var bri      by remember { mutableFloatStateOf(initHSV[2]) }
    var hexField by remember { mutableStateOf(pickerColorToHex(initColor)) }

    fun updateFromPicker(h: Float, s: Float, b: Float) {
        hue = h; sat = s; bri = b
        hexField = pickerColorToHex(hsvToColor(h, s, b))
    }

    val currentColor = remember(hue, sat, bri) { hsvToColor(hue, sat, bri) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm("#$hexField") }) { Text(stringResource(R.string.colorpicker_ok_button)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.colorpicker_cancel_button)) }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SVPanel(hue = hue, sat = sat, bri = bri) { s, b -> updateFromPicker(hue, s, b) }
                HueBar(hue = hue) { h -> updateFromPicker(h, sat, bri) }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .background(currentColor)
                            .border(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                    )
                    OutlinedTextField(
                        value = hexField,
                        onValueChange = { v ->
                            val f = v.filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
                                .take(6).lowercase()
                            hexField = f
                            if (f.length == 6) {
                                try {
                                    val c = Color(0xFF000000.toInt() or f.toInt(16))
                                    val hsv = c.toHSV()
                                    hue = hsv[0]; sat = hsv[1]; bri = hsv[2]
                                } catch (_: Exception) {}
                            }
                        },
                        prefix = { Text("#") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        isError = hexField.length != 6,
                    )
                }
            }
        }
    )
}

@Composable
private fun SVPanel(hue: Float, sat: Float, bri: Float, onChange: (Float, Float) -> Unit) {
    val baseColor = remember(hue) { hsvToColor(hue, 1f, 1f) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(RoundedCornerShape(4.dp))
    ) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        onChange(
                            (change.position.x / w).coerceIn(0f, 1f),
                            (1f - change.position.y / h).coerceIn(0f, 1f),
                        )
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { pos ->
                        onChange(
                            (pos.x / w).coerceIn(0f, 1f),
                            (1f - pos.y / h).coerceIn(0f, 1f),
                        )
                    }
                }
        ) {
            drawRect(brush = Brush.horizontalGradient(listOf(Color.White, baseColor)))
            drawRect(brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))

            val cx = sat * size.width
            val cy = (1f - bri) * size.height
            drawCircle(Color.White, radius = 8.dp.toPx(), center = Offset(cx, cy), style = Stroke(2.dp.toPx()))
            drawCircle(Color.Black, radius = 7.5f.dp.toPx(), center = Offset(cx, cy), style = Stroke(1.dp.toPx()))
        }
    }
}

@Composable
private fun HueBar(hue: Float, onChange: (Float) -> Unit) {
    val colors = remember { (0..12).map { hsvToColor(it * 30f, 1f, 1f) } }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .clip(RoundedCornerShape(4.dp))
    ) {
        val w = constraints.maxWidth.toFloat()

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        onChange((change.position.x / w).coerceIn(0f, 1f) * 360f)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { pos ->
                        onChange((pos.x / w).coerceIn(0f, 1f) * 360f)
                    }
                }
        ) {
            drawRect(brush = Brush.horizontalGradient(colors))
            val x = (hue / 360f) * size.width
            drawRect(
                color = Color.White,
                topLeft = Offset(x - 2.dp.toPx(), 0f),
                size = Size(4.dp.toPx(), size.height),
            )
            drawRect(
                color = Color(0x80000000),
                topLeft = Offset(x - 2.dp.toPx(), 0f),
                size = Size(4.dp.toPx(), size.height),
                style = Stroke(1.dp.toPx()),
            )
        }
    }
}
