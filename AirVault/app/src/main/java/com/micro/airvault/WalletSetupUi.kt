package com.micro.airvault

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.micro.airvault.ui.theme.Accent
import com.micro.airvault.ui.theme.Muted
import com.micro.airvault.ui.theme.Warm

@Composable
internal fun VaultSplashContent(busy: Boolean, error: String, onRetry: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp).testTag("wallet-splash"),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Default.Shield, null, tint = Accent, modifier = Modifier.size(72.dp))
            Spacer(Modifier.height(20.dp))
            Text("AirVault", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(28.dp))
            if (busy) {
                CircularProgressIndicator(Modifier.size(28.dp), color = Accent)
                Spacer(Modifier.height(16.dp))
                Text("正在读取钱包…", color = Muted)
            } else if (error.isNotEmpty()) {
                Text(error, color = Warm)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onRetry, modifier = Modifier.testTag("startup-retry")) { Text("重试") }
            }
        }
    }
}

@Composable
internal fun CustomChainContent(busy: Boolean, error: String, onSave: (String, String, CustomAddressFormat, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var chainId by rememberSaveable { mutableStateOf("") }
    var path by rememberSaveable { mutableStateOf(CustomAddressFormat.EVM.examplePath) }
    var format by rememberSaveable { mutableStateOf(CustomAddressFormat.EVM) }
    var expanded by remember { mutableStateOf(false) }
    val nameError = remember(name) { runCatching { CustomChainValidation.name(name) }.exceptionOrNull()?.message }
    val pathError = remember(path, format) { runCatching { CustomChainValidation.path(path, format) }.exceptionOrNull()?.message }
    val chainIdError = remember(chainId, format) { runCatching { CustomChainValidation.chainId(chainId, format) }.exceptionOrNull()?.message }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("添加自定义链", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("链名称") },
            singleLine = true, enabled = !busy, isError = name.isNotEmpty() && nameError != null,
            supportingText = { Text(if (name.isNotEmpty() && nameError != null) nameError else "最多 30 个字符") },
            modifier = Modifier.fillMaxWidth().testTag("custom-chain-name"))
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("custom-chain-format")) {
                Text("地址格式 · ${format.label}", modifier = Modifier.weight(1f))
                Icon(Icons.Default.KeyboardArrowDown, null)
            }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                CustomAddressFormat.entries.forEach { option ->
                    DropdownMenuItem(text = { Text(option.label) }, onClick = {
                        if (path == format.examplePath) path = option.examplePath
                        format = option
                        expanded = false
                    })
                }
            }
        }
        if (format == CustomAddressFormat.EVM) {
            OutlinedTextField(value = chainId, onValueChange = { chainId = it }, label = { Text("ChainId") },
                singleLine = true, enabled = !busy, isError = chainId.isNotEmpty() && chainIdError != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                supportingText = { Text(if (chainId.isNotEmpty() && chainIdError != null) chainIdError else "EVM 网络必填，填写十进制正整数") },
                modifier = Modifier.fillMaxWidth().testTag("custom-chain-id"))
        }
        OutlinedTextField(value = path, onValueChange = { path = it }, label = { Text("派生路径") },
            singleLine = true, enabled = !busy, isError = pathError != null,
            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
            supportingText = { Text(pathError ?: "例如 ${format.examplePath}") },
            modifier = Modifier.fillMaxWidth().testTag("custom-chain-path"))
        Text("保存后从当前钱包派生地址。配置会保存在本机，并应用于本机的所有钱包。", color = Muted)
        if (error.isNotEmpty()) Text(error, color = Warm)
        Button(onClick = { onSave(name, path, format, chainId) },
            enabled = !busy && nameError == null && pathError == null && chainIdError == null,
            modifier = Modifier.fillMaxWidth().testTag("save-custom-chain")) {
            Text(if (busy) "正在保存…" else "保存")
        }
    }
}
