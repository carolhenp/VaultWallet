package com.micro.airvault

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

@Composable
internal fun EntropyCameraCapture(
    framesCaptured: Int,
    onFrame: (ByteArray) -> Unit,
    onGyroscopeDigest: (ByteArray?) -> Unit,
    onError: (String) -> Unit,
    useGyroscope: Boolean = true
) {
    val context = LocalContext.current
    val sensorManager = remember(context) { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    val gyroscope = remember(sensorManager, useGyroscope) {
        if (useGyroscope) sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) else null
    }
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var gyroscopeAvailable by remember(gyroscope) { mutableStateOf(gyroscope != null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    Text("相机画面  $framesCaptured / 3", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
    if (!gyroscopeAvailable) Text("陀螺仪不可用，将使用其他采集数据继续创建钱包。")
    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(Manifest.permission.CAMERA)
    }
    if (!granted) {
        Column {
            Text("需要相机权限完成本次采集。")
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("授权相机") }
        }
        return
    }

    val owner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    DisposableEffect(owner, previewView, gyroscope) {
        val active = AtomicBoolean(true)
        val sensorFailed = AtomicBoolean(false)
        val schedule = CameraFrameSchedule()
        val gyroEntropy = GyroscopeEntropy()
        val gyroThread = gyroscope?.let { HandlerThread("AirVaultGyroscope").apply { start() } }
        val gyroHandler = gyroThread?.let { Handler(it.looper) }
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val sensorListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (!active.get()) return
                val accepted = runCatching {
                    gyroEntropy.addSample(event.timestamp, event.values[0], event.values[1], event.values[2])
                }.isSuccess
                if (!accepted) {
                    sensorFailed.set(true)
                    sensorManager.unregisterListener(this)
                    gyroEntropy.clear()
                    mainExecutor.execute { if (active.get()) gyroscopeAvailable = false }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        val registered = gyroscope != null && gyroHandler != null && runCatching {
            sensorManager.registerListener(sensorListener, gyroscope, SensorManager.SENSOR_DELAY_GAME, gyroHandler)
        }.getOrDefault(false)
        val executor = Executors.newSingleThreadExecutor()
        if (!registered) gyroscopeAvailable = false
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                if (!active.get()) return@addListener
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { image ->
                    try {
                        val now = SystemClock.elapsedRealtimeNanos()
                        if (active.get() && registered && !sensorFailed.get() &&
                            schedule.capturedFrames < EntropyCollector.CAMERA_FRAMES) {
                            gyroEntropy.begin(now)
                        }
                        if (active.get() && schedule.shouldCapture(now)) {
                            val frame = normalizedLuma(image)
                            if (schedule.capturedFrames == EntropyCollector.CAMERA_FRAMES) {
                                val deliver: (ByteArray?) -> Unit = { digest ->
                                    mainExecutor.execute {
                                        if (active.get()) {
                                            onFrame(frame)
                                            onGyroscopeDigest(digest)
                                        } else {
                                            frame.fill(0)
                                            digest?.fill(0)
                                        }
                                    }
                                }
                                if (registered && gyroHandler != null) {
                                    runCatching { gyroEntropy.end(now) }
                                    if (!gyroHandler.post {
                                            val digest = runCatching { gyroEntropy.finish() }.getOrNull()
                                            if (digest == null) mainExecutor.execute {
                                                if (active.get()) gyroscopeAvailable = false
                                            }
                                            deliver(digest)
                                        }) deliver(null)
                                } else deliver(null)
                            } else mainExecutor.execute {
                                if (active.get()) onFrame(frame) else frame.fill(0)
                            }
                        }
                    } catch (exception: Exception) {
                        mainExecutor.execute {
                            if (active.get()) onError(exception.message ?: "相机采集失败")
                        }
                    } finally {
                        image.close()
                    }
                }
                provider.unbindAll()
                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (exception: Exception) {
                if (active.get()) onError(exception.message ?: "相机不可用")
            }
        }, mainExecutor)
        onDispose {
            active.set(false)
            if (registered) sensorManager.unregisterListener(sensorListener)
            gyroEntropy.clear()
            gyroThread?.quitSafely()
            if (future.isDone) runCatching { future.get().unbindAll() }
            executor.shutdownNow()
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.width(minOf(maxWidth, 270.dp)).aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(8.dp)).testTag("entropy-camera-preview")
        )
    }
}

internal class CameraFrameSchedule {
    private var startedAtNanos = -1L
    private var lastCaptureNanos = 0L
    private var captured = 0
    val capturedFrames: Int get() = captured

    fun shouldCapture(nowNanos: Long): Boolean {
        if (startedAtNanos < 0L) {
            startedAtNanos = nowNanos
            return false
        }
        if (captured == EntropyCollector.CAMERA_FRAMES ||
            nowNanos - startedAtNanos < (captured + 1) * 1_000_000_000L ||
            (captured > 0 && nowNanos - lastCaptureNanos < 1_000_000_000L)) return false
        captured++
        lastCaptureNanos = nowNanos
        return true
    }
}

internal fun normalizedLuma(image: ImageProxy): ByteArray {
    val originalWidth = image.width
    val originalHeight = image.height
    val rotation = image.imageInfo.rotationDegrees
    require(rotation in listOf(0, 90, 180, 270)) { "相机旋转角度无效" }
    val rotatedWidth = if (rotation == 90 || rotation == 270) originalHeight else originalWidth
    val rotatedHeight = if (rotation == 90 || rotation == 270) originalWidth else originalHeight
    val outputWidth = EntropyCollector.FRAME_WIDTH
    val outputHeight = EntropyCollector.FRAME_HEIGHT
    val scale = max(outputWidth.toFloat() / rotatedWidth, outputHeight.toFloat() / rotatedHeight)
    val cropLeft = (rotatedWidth - outputWidth / scale) / 2f
    val cropTop = (rotatedHeight - outputHeight / scale) / 2f
    val plane = image.planes[0]
    val buffer = plane.buffer
    val frame = ByteArray(outputWidth * outputHeight)
    for (row in 0 until outputHeight) {
        val rotatedY = (cropTop + (row + 0.5f) / scale).toInt().coerceIn(0, rotatedHeight - 1)
        for (column in 0 until outputWidth) {
            val rotatedX = (cropLeft + (column + 0.5f) / scale).toInt().coerceIn(0, rotatedWidth - 1)
            val sourceX: Int
            val sourceY: Int
            when (rotation) {
                90 -> {
                    sourceX = rotatedY
                    sourceY = originalHeight - 1 - rotatedX
                }
                180 -> {
                    sourceX = originalWidth - 1 - rotatedX
                    sourceY = originalHeight - 1 - rotatedY
                }
                270 -> {
                    sourceX = originalWidth - 1 - rotatedY
                    sourceY = rotatedX
                }
                else -> {
                    sourceX = rotatedX
                    sourceY = rotatedY
                }
            }
            frame[row * outputWidth + column] = buffer.get(sourceY * plane.rowStride + sourceX * plane.pixelStride)
        }
    }
    return frame
}
