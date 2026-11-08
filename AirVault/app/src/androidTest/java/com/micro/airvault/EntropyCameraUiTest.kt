package com.micro.airvault

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.micro.airvault.ui.theme.Ink
import com.micro.airvault.ui.theme.Paper
import com.micro.airvault.ui.theme.AirVaultTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        assumeTrue(context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY))
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        }
    }

    @Test
    fun capturesCameraAndGyroscopeWithoutCoveringHeading() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        assumeTrue(sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null)
        var frames by mutableIntStateOf(0)
        var gyroCaptured by mutableStateOf(false)
        var error by mutableStateOf("")
        compose.setContent {
            AirVaultTheme {
                Column(Modifier.fillMaxSize().background(Ink).padding(22.dp)) {
                    Text("相机预览标题", color = Paper, fontSize = 24.sp)
                    EntropyCameraCapture(frames, { frame ->
                        frame.fill(0)
                        frames++
                    }, { digest ->
                        digest?.fill(0)
                        gyroCaptured = true
                    }, { error = it })
                }
            }
        }

        compose.waitUntil(20_000) { gyroCaptured || error.isNotEmpty() }
        assertEquals("", error)
        assertEquals(3, frames)
        val heading = compose.onNodeWithText("相机预览标题").assertIsDisplayed().getUnclippedBoundsInRoot()
        val preview = compose.onNodeWithTag("entropy-camera-preview").getUnclippedBoundsInRoot()
        assertTrue(preview.top >= heading.bottom)
    }

    @Test
    fun capturesCameraWhenGyroscopeIsUnavailable() {
        var frames by mutableIntStateOf(0)
        var gyroscopeHandled by mutableStateOf(false)
        var error by mutableStateOf("")
        compose.setContent {
            AirVaultTheme {
                EntropyCameraCapture(frames, { frame ->
                    frame.fill(0)
                    frames++
                }, { digest ->
                    assertEquals(null, digest)
                    gyroscopeHandled = true
                }, { error = it }, useGyroscope = false)
            }
        }

        compose.waitUntil(20_000) { gyroscopeHandled || error.isNotEmpty() }
        assertEquals("", error)
        assertEquals(3, frames)
        compose.onNodeWithText("陀螺仪不可用，将使用其他采集数据继续创建钱包。").assertIsDisplayed()
    }
}
