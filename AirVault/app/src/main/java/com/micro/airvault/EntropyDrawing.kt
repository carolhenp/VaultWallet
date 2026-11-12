package com.micro.airvault

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.micro.airvault.ui.theme.Accent
import com.micro.airvault.ui.theme.Surface

@Composable
internal fun EntropyDrawing(
    collectionReady: Boolean,
    onConfirm: (List<DrawSample>) -> Unit,
    progress: @Composable (Int) -> Unit
) {
    val samples = remember { mutableStateListOf<DrawSample>() }
    var confirming by remember { mutableStateOf(false) }
    val movements = samples.count { !it.startsStroke }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Canvas(
            Modifier.fillMaxWidth().height(260.dp)
                .background(Surface, RoundedCornerShape(6.dp))
                .border(1.dp, Accent.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            if (samples.size < MAX_SAMPLES) {
                                samples += DrawSample(offset.x, offset.y, SystemClock.elapsedRealtimeNanos(), true)
                            }
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            if (samples.size < MAX_SAMPLES) {
                                samples += DrawSample(change.position.x, change.position.y, SystemClock.elapsedRealtimeNanos(), false)
                            }
                        }
                    )
                }
        ) {
            var path = Path()
            samples.forEach { sample ->
                if (sample.startsStroke) {
                    drawPath(path, Accent, style = Stroke(width = 3.dp.toPx()))
                    path = Path().apply { moveTo(sample.x, sample.y) }
                } else {
                    path.lineTo(sample.x, sample.y)
                }
            }
            drawPath(path, Accent, style = Stroke(width = 3.dp.toPx()))
        }
        progress((movements * 100 / EntropyCollector.MIN_DRAW_MOVEMENTS).coerceAtMost(100))
        OutlinedButton(onClick = { samples.clear() }, enabled = samples.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.DeleteOutline, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("清除画板")
        }
        Button(
            onClick = { confirming = true },
            enabled = collectionReady && movements >= EntropyCollector.MIN_DRAW_MOVEMENTS,
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Text("完成绘制")
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("确认本次采集") },
            text = { Text("确认后将根据本次采集生成新的 24 词助记词。") },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    onConfirm(samples.toList())
                }) { Text("确认生成") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("返回绘制") } }
        )
    }
}

private const val MAX_SAMPLES = 8192
