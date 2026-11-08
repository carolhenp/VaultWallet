package com.micro.airvault

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import org.bouncycastle.crypto.digests.SHA3Digest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min

@Composable
internal fun EntropyAudioCapture(
    onComplete: (ByteArray) -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    var seconds by remember { mutableFloatStateOf(0f) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(Manifest.permission.RECORD_AUDIO)
    }
    Text("麦克风  ${"%.1f".format(seconds)} / 3.0 秒", style = MaterialTheme.typography.titleMedium)
    if (!granted) {
        Column {
            Text("需要麦克风权限完成本次采集。")
            Button(onClick = { launcher.launch(Manifest.permission.RECORD_AUDIO) }) { Text("授权麦克风") }
        }
        return
    }

    DisposableEffect(granted) {
        val active = AtomicBoolean(true)
        val recorder = AtomicReference<AudioRecord?>()
        val executor = Executors.newSingleThreadExecutor()
        executor.execute {
            try {
                val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
                check(minimum > 0) { "麦克风不支持 16 kHz PCM" }
                val audio = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL,
                    ENCODING,
                    max(minimum, 3200)
                )
                recorder.set(audio)
                check(audio.state == AudioRecord.STATE_INITIALIZED) { "麦克风初始化失败" }
                val digest = SHA3Digest(256)
                val buffer = ByteArray(max(minimum, 3200))
                var collected = 0
                val deadline = SystemClock.elapsedRealtimeNanos() + 7_000_000_000L
                try {
                    audio.startRecording()
                    check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "麦克风未开始录音" }
                    while (active.get() && collected < REQUIRED_BYTES) {
                        check(SystemClock.elapsedRealtimeNanos() < deadline) { "麦克风采集超时" }
                        val read = audio.read(buffer, 0, min(buffer.size, REQUIRED_BYTES - collected), AudioRecord.READ_BLOCKING)
                        check(read >= 0) { "麦克风读取失败：$read" }
                        if (read > 0) {
                            digest.update(buffer, 0, read)
                            buffer.fill(0, 0, read)
                            collected += read
                            val elapsed = collected.toFloat() / BYTES_PER_SECOND
                            ContextCompat.getMainExecutor(context).execute { if (active.get()) seconds = elapsed }
                        }
                    }
                    if (active.get() && collected == REQUIRED_BYTES) {
                            val hash = ByteArray(EntropyCollector.HASH_BYTES).also { digest.doFinal(it, 0) }
                        ContextCompat.getMainExecutor(context).execute {
                            if (active.get()) onComplete(hash) else hash.fill(0)
                        }
                    }
                } finally {
                    buffer.fill(0)
                    runCatching { audio.stop() }
                    audio.release()
                    recorder.set(null)
                }
            } catch (exception: Exception) {
                ContextCompat.getMainExecutor(context).execute {
                    if (active.get()) onError(exception.message ?: "麦克风采集失败")
                }
            }
        }
        onDispose {
            active.set(false)
            recorder.get()?.let { runCatching { it.stop() } }
            executor.shutdownNow()
        }
    }
}

private const val SAMPLE_RATE = 16_000
private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
private const val BYTES_PER_SECOND = SAMPLE_RATE * 2
private const val REQUIRED_BYTES = BYTES_PER_SECOND * 3
