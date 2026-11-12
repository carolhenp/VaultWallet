package com.micro.airvault

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EntropySensorsUiTest {
    @get:Rule val compose = createComposeRule()

    @Before
    fun prepareMicrophone() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE))
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        }
    }

    @Test
    fun unfinishedCollectionCanBeCancelled() {
        var error by mutableStateOf("")
        var collecting by mutableStateOf(true)
        compose.setContent {
            if (collecting) {
                EntropyAudioCapture({}, { it.fill(0) }, { error = it })
                EntropyGyroscopeCapture({}, { it?.fill(0) })
            }
        }
        val started = SystemClock.elapsedRealtime()
        compose.waitUntil(3_000) { SystemClock.elapsedRealtime() - started >= 1_000 || error.isNotEmpty() }
        assertEquals("", error)
        compose.runOnIdle { collecting = false }
        compose.waitForIdle()
        assertEquals("", error)
    }
}
