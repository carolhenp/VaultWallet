package com.micro.airvault

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

@Composable
internal fun EntropyAudioCapture(onProgress: (Int) -> Unit, onComplete: (ByteArray) -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val active = AtomicBoolean(true)
        val recorder = AtomicReference<AudioRecord?>()
        val executor = Executors.newSingleThreadExecutor()
        val main = ContextCompat.getMainExecutor(context)
        executor.execute {
            val collector = AudiblePcmCollector()
            try {
                check(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED) { "麦克风权限已撤销" }
                val minimum = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(minimum > 0) { "麦克风不支持 16 kHz PCM" }
                val audio = AudioRecord(MediaRecorder.AudioSource.MIC, 16_000,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minimum, 3200))
                recorder.set(audio)
                val buffer = ByteArray(3200)
                try {
                    check(audio.state == AudioRecord.STATE_INITIALIZED) { "麦克风初始化失败" }
                    audio.startRecording()
                    check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "麦克风未开始录音" }
                    var reportedProgress = 0
                    while (active.get() && !collector.complete) {
                        val count = audio.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                        check(count >= 0) { "麦克风读取失败：$count" }
                        if (collector.addIfAudible(buffer, count) && collector.progress > reportedProgress) {
                            reportedProgress = collector.progress
                            val progress = reportedProgress
                            main.execute { if (active.get()) onProgress(progress) }
                        }
                        buffer.fill(0, 0, count)
                    }
                    if (active.get() && collector.complete) {
                        val result = collector.takeComplete()
                        main.execute { if (active.get()) onComplete(result) else result.fill(0) }
                    }
                } finally {
                    buffer.fill(0)
                    runCatching { audio.stop() }
                    audio.release()
                    recorder.set(null)
                }
            } catch (exception: Exception) {
                main.execute { if (active.get()) onError(exception.message ?: "麦克风采集失败") }
            } finally { collector.clear() }
        }
        onDispose {
            active.set(false)
            recorder.get()?.let { runCatching { it.stop() } }
            executor.shutdownNow()
        }
    }
}
