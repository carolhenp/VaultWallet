package com.micro.airvault

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
import androidx.compose.foundation.shape.RoundedCornerShape
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
internal fun QrScanner(onResult: (String) -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(Manifest.permission.CAMERA)
    }
    if (!granted) {
        Column {
            Text("需要相机权限读取二维码。也可以返回并手动输入请求。")
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("授权相机") }
        }
        return
    }

    val owner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    DisposableEffect(owner, previewView) {
        val active = AtomicBoolean(true)
        val delivered = AtomicBoolean(false)
        val executor = Executors.newSingleThreadExecutor()
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
                        if (active.get() && !delivered.get()) {
                            val decoded = decodeQr(image)
                            if (decoded != null && delivered.compareAndSet(false, true)) {
                                ContextCompat.getMainExecutor(context).execute { if (active.get()) onResult(decoded) }
                            }
                        }
                    } finally {
                        image.close()
                    }
                }
                provider.unbindAll()
                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (_: Exception) {
                // Camera permission and hardware availability are handled by the manual input path.
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            active.set(false)
            if (future.isDone) runCatching { future.get().unbindAll() }
            executor.shutdownNow()
        }
    }
    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxWidth().height(380.dp))
}

private fun decodeQr(image: ImageProxy): String? {
    val plane = image.planes[0]
    val width = image.width
    val height = image.height
    val bytes = ByteArray(width * height)
    val buffer = plane.buffer
    for (row in 0 until height) {
        for (column in 0 until width) {
            bytes[row * width + column] = buffer.get(row * plane.rowStride + column * plane.pixelStride)
        }
    }
    val source = PlanarYUVLuminanceSource(bytes, width, height, 0, 0, width, height, false)
    return try {
        MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source))).text
    } catch (_: ReaderException) {
        null
    }
}

@Composable
internal fun QrImage(value: String, description: String = "签名结果二维码", modifier: Modifier = Modifier.fillMaxWidth()) {
    if (value.isEmpty()) return
    val bitmap = remember(value) {
        runCatching {
            val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 512, 512)
            val pixels = IntArray(512 * 512) { index ->
                if (matrix[index % 512, index / 512]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            }
            Bitmap.createBitmap(pixels, 512, 512, Bitmap.Config.ARGB_8888)
        }
            .getOrNull()
    }
    if (bitmap == null) {
        Text("结果过长，无法生成单张二维码。请使用下方文本导入联网设备。")
    } else {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = description,
            modifier = modifier.background(Color.White, RoundedCornerShape(6.dp)).padding(12.dp)
        )
    }
}
