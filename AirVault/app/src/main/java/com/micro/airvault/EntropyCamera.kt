package com.micro.airvault

import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
internal fun EntropyCameraCapture(onPhoto: (CameraSide, ByteArray) -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val active = AtomicBoolean(true)
        val executor = Executors.newSingleThreadExecutor()
        val main = ContextCompat.getMainExecutor(context)
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (!active.get()) return@addListener
            try {
                val provider = future.get()
                fun bind(side: CameraSide) {
                    if (!active.get()) return
                    val selector = if (side == CameraSide.BACK) CameraSelector.DEFAULT_BACK_CAMERA
                        else CameraSelector.DEFAULT_FRONT_CAMERA
                    require(provider.hasCamera(selector)) { "${if (side == CameraSide.BACK) "后置" else "前置"}摄像头不可用" }
                    val schedule = CameraFrameSchedule()
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setResolutionSelector(ResolutionSelector.Builder()
                            .setResolutionFilter { sizes, _ -> sizes.sortedBy { it.width.toLong() * it.height } }
                            .build())
                        .build()
                    analysis.setAnalyzer(executor) { image ->
                        try {
                            if (active.get() && schedule.shouldCapture(SystemClock.elapsedRealtimeNanos())) {
                                val photo = rawYuvImage(image)
                                main.execute {
                                    if (active.get()) {
                                        onPhoto(side, photo)
                                        if (schedule.capturedFrames == EntropyCollector.PHOTOS_PER_CAMERA) {
                                            try {
                                                if (side == CameraSide.BACK) bind(CameraSide.FRONT)
                                                else provider.unbindAll()
                                            } catch (exception: Exception) {
                                                onError(exception.message ?: "切换摄像头失败")
                                            }
                                        }
                                    } else photo.fill(0)
                                }
                            }
                        } catch (exception: Exception) {
                            main.execute { if (active.get()) onError(exception.message ?: "相机采集失败") }
                        } finally { image.close() }
                    }
                    provider.unbindAll()
                    provider.bindToLifecycle(owner, selector, analysis)
                }
                bind(CameraSide.BACK)
            } catch (exception: Exception) {
                if (active.get()) onError(exception.message ?: "相机不可用")
            }
        }, main)
        onDispose {
            active.set(false)
            if (future.isDone) runCatching { future.get().unbindAll() }
            executor.shutdownNow()
        }
    }
}

internal class CameraFrameSchedule {
    private var startedAt = -1L
    private var captured = 0
    val capturedFrames: Int get() = captured

    fun shouldCapture(nowNanos: Long): Boolean {
        if (startedAt < 0) { startedAt = nowNanos; return false }
        if (captured == EntropyCollector.PHOTOS_PER_CAMERA ||
            nowNanos - startedAt < (captured + 1) * 1_000_000_000L) return false
        captured++
        return true
    }
}

internal fun rawYuvImage(image: ImageProxy): ByteArray {
    require(image.planes.size >= 3) { "相机未返回 YUV 图像" }
    val chromaWidth = (image.width + 1) / 2
    val chromaHeight = (image.height + 1) / 2
    val result = ByteArray(16 + image.width * image.height + 2 * chromaWidth * chromaHeight)
    ByteBuffer.wrap(result).putInt(image.width).putInt(image.height).putLong(image.imageInfo.timestamp)
    var offset = 16
    image.planes.take(3).forEachIndexed { index, plane ->
        val width = if (index == 0) image.width else chromaWidth
        val height = if (index == 0) image.height else chromaHeight
        val buffer = plane.buffer
        for (row in 0 until height) for (column in 0 until width) {
            result[offset++] = buffer.get(row * plane.rowStride + column * plane.pixelStride)
        }
    }
    return result
}
