package com.micro.airvault

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import com.micro.airvault.ui.theme.Accent
import com.micro.airvault.ui.theme.Muted

@Composable
internal fun EntropyPermissionGate(onContinue: () -> Unit) {
    val context = LocalContext.current
    var permissionError by remember { mutableStateOf(false) }
    val permissions = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (permissions.all { grants[it] == true }) onContinue()
        else permissionError = true
    }
    Button(onClick = {
        if (permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) {
            onContinue()
        } else launcher.launch(permissions)
    }, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.AutoMirrored.Filled.ArrowForward, null)
        Text("继续")
    }
    if (permissionError) Text("需要相机和麦克风权限才能采集数据，请授权后重试。", color = Accent)
}

@Composable
internal fun EntropyProgress(label: String, percent: Int, icon: @Composable () -> Unit,
    modifier: Modifier = Modifier, skipped: Boolean = false) {
    Column(modifier.height(68.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween) {
        icon()
        Text(label, color = Muted, style = MaterialTheme.typography.labelSmall)
        Text(if (skipped) "已跳过" else "${percent.coerceIn(0, 100)}%", color = Accent,
            style = MaterialTheme.typography.labelSmall)
    }
}
