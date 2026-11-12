package com.micro.airwallet

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.ReaderException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun AnimatedQr(frames: List<String>) {
    var index by remember(frames) { mutableIntStateOf(0) }
    LaunchedEffect(frames) {
        while (frames.size > 1) {
            kotlinx.coroutines.delay(700)
            index = (index + 1) % frames.size
        }
    }
    if (frames.isEmpty()) return
    val bitmap = remember(frames, index) {
        runCatching {
            val matrix = QRCodeWriter().encode(frames[index], BarcodeFormat.QR_CODE, 512, 512)
            Bitmap.createBitmap(IntArray(512 * 512) { pixel ->
                if (matrix[pixel % 512, pixel / 512]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            }, 512, 512, Bitmap.Config.ARGB_8888)
        }.getOrNull()
    }
    if (bitmap != null) Image(bitmap.asImageBitmap(), "交易二维码", Modifier.fillMaxWidth().background(Color.White).padding(12.dp))
    else Text("二维码生成失败，数据超出容量")
    if (frames.size > 1) Text("${index + 1} / ${frames.size}")
}

@Composable
fun QrScanner(onResult: (String) -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }
    if (!granted) {
        Column {
            Text("需要相机权限扫描 AirVault 二维码")
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("授权相机") }
        }
        return
    }
    val owner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER } }
    DisposableEffect(owner, previewView) {
        val active = AtomicBoolean(true)
        val executor = Executors.newSingleThreadExecutor()
        var last = ""
        var lastTime = 0L
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            runCatching {
                if (!active.get()) return@addListener
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(executor) { image ->
                    try {
                        val decoded = decodeQr(image)
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (active.get() && decoded != null && (decoded != last || now - lastTime > 1200)) {
                            last = decoded
                            lastTime = now
                            ContextCompat.getMainExecutor(context).execute { if (active.get()) onResult(decoded) }
                        }
                    } finally { image.close() }
                }
                provider.unbindAll()
                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            active.set(false)
            if (future.isDone) runCatching { future.get().unbindAll() }
            executor.shutdownNow()
        }
    }
    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxWidth().height(360.dp))
}

private fun decodeQr(image: ImageProxy): String? {
    val plane = image.planes[0]
    val bytes = ByteArray(image.width * image.height)
    for (row in 0 until image.height) for (column in 0 until image.width) {
        bytes[row * image.width + column] = plane.buffer.get(row * plane.rowStride + column * plane.pixelStride)
    }
    return try {
        MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(
            PlanarYUVLuminanceSource(bytes, image.width, image.height, 0, 0, image.width, image.height, false)
        ))).text
    } catch (_: ReaderException) { null }
}
