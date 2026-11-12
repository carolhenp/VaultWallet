package com.micro.airvault

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import java.util.concurrent.atomic.AtomicBoolean

@Composable
internal fun EntropyGyroscopeCapture(
    onProgress: (Int) -> Unit,
    onComplete: (ByteArray?) -> Unit
) {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val handler = Handler(Looper.getMainLooper())
        val entropy = GyroscopeEntropy()
        val active = AtomicBoolean(true)
        val start = SystemClock.elapsedRealtimeNanos()
        var listener: SensorEventListener? = null
        if (sensor == null) {
            onProgress(100)
            onComplete(null)
        } else {
            entropy.begin(start)
            var reportedProgress = 0
            val sensorListener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    if (!active.get()) return
                    val accepted = runCatching {
                        entropy.addSample(event.timestamp, event.values[0], event.values[1], event.values[2])
                    }.getOrDefault(false)
                    if (accepted) {
                        val percent = entropy.acceptedSamples * 100 / GyroscopeEntropy.REQUIRED_SAMPLES
                        if (percent > reportedProgress) {
                            reportedProgress = percent
                            handler.post { if (active.get()) onProgress(percent) }
                        }
                    }
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            listener = sensorListener
            val registered = runCatching {
                manager.registerListener(sensorListener, sensor, SensorManager.SENSOR_DELAY_GAME)
            }.getOrDefault(false)
            if (!registered) {
                onProgress(100)
                onComplete(null)
            } else {
                val tick = object : Runnable {
                    override fun run() {
                        if (!active.get()) return
                        val elapsed = SystemClock.elapsedRealtimeNanos() - start
                        if (elapsed < 5_000_000_000L ||
                            entropy.acceptedSamples < GyroscopeEntropy.REQUIRED_SAMPLES) {
                            handler.postDelayed(this, 10)
                        } else {
                            manager.unregisterListener(sensorListener)
                            entropy.end(SystemClock.elapsedRealtimeNanos())
                            onComplete(entropy.finish())
                        }
                    }
                }
                handler.post(tick)
            }
        }
        onDispose {
            active.set(false)
            handler.removeCallbacksAndMessages(null)
            listener?.let(manager::unregisterListener)
            entropy.clear()
        }
    }
}
