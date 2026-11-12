package com.micro.airvault

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
class EntropyCameraUiTest {
    @get:Rule val compose = createComposeRule()

    @Before
    fun prepareCamera() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT))
        assumeTrue(context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA))
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        }
    }

    @Test
    fun capturesThreeFramesFromEachCamera() {
        var back by mutableIntStateOf(0)
        var front by mutableIntStateOf(0)
        var error by mutableStateOf("")
        compose.setContent {
            EntropyCameraCapture({ side, photo ->
                if (side == CameraSide.BACK) back++ else front++
                photo.fill(0)
            }, { error = it })
        }
        compose.waitUntil(30_000) { front == 3 || error.isNotEmpty() }
        assertEquals("", error)
        assertEquals(3, back)
        assertEquals(3, front)
    }
}
