package com.micro.airvault

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.micro.airvault.ui.theme.Accent
import com.micro.airvault.ui.theme.Muted
import com.micro.airvault.ui.theme.Paper
import com.micro.airvault.ui.theme.Surface
import com.micro.airvault.ui.theme.Warm

@Composable
internal fun VaultUi(
    screen: Screen,
    phrase: String,
    inputPhrase: String,
    onInputPhrase: (String) -> Unit,
    confirmed: Boolean,
    onConfirm: (Boolean) -> Unit,
    address: String,
    requestText: String,
    onRequestText: (String) -> Unit,
    request: SigningRequest?,
    result: String,
    error: String,
    busy: Boolean,
    cameraFrames: Int,
    selectedChain: WalletChain,
    onSelectChain: (WalletChain) -> Unit,
    onBack: () -> Unit,
    onCreate: () -> Unit,
    onBeginCamera: () -> Unit,
    onCameraFrame: (ByteArray) -> Unit,
    onGyroscopeDigest: (ByteArray?) -> Unit,
    onBeginAudio: () -> Unit,
    onAudioDigest: (ByteArray) -> Unit,
    onDrawingConfirm: (List<DrawSample>) -> Unit,
    onCollectionError: (String) -> Unit,
    onImport: () -> Unit,
    onSave: () -> Unit,
    onRestore: () -> Unit,
    onUnlock: () -> Unit,
    onScan: () -> Unit,
    onRequest: () -> Unit,
    onPayload: (String) -> Unit,
    onSign: () -> Unit,
    onRecovery: () -> Unit,
    onDelete: () -> Unit,
    onConfirmDelete: () -> Unit
) {
    var showAddressQr by remember { mutableStateOf(false) }
    var phraseVisible by remember(screen) { mutableStateOf(false) }
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (screen !in setOf(Screen.WELCOME, Screen.HOME)) {
                    IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                    Spacer(Modifier.width(8.dp))
                } else {
                    Icon(Icons.Default.Shield, contentDescription = null, tint = Accent)
                    Spacer(Modifier.width(10.dp))
                }
                Text("AirVault", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text("离线", color = Accent, style = MaterialTheme.typography.labelLarge)
            }
            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                if (error.isNotEmpty()) {
                    Text(error, color = Warm, modifier = Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(6.dp)).padding(14.dp))
                }
                when (screen) {
                    Screen.WELCOME -> {
                        Heading("你的离线钱包", "多链 · BIP-39 · 设备生物识别")
                        Spacer(Modifier.height(18.dp))
                        Icon(Icons.Default.Key, contentDescription = null, tint = Accent, modifier = Modifier.size(56.dp))
                        Text("创建一个由本机保护的密钥。助记词是唯一的恢复方式。", color = Muted)
                        ActionButton("创建新钱包", onCreate, icon = { Icon(Icons.Default.Key, null) })
                        OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) { Text("恢复已有钱包") }
                    }
                    Screen.ENTROPY_CAMERA_INFO -> {
                        Heading("准备采集相机与陀螺仪数据", "第 1 步 · 3 秒")
                        Icon(Icons.Default.CameraAlt, contentDescription = null, tint = Accent, modifier = Modifier.size(48.dp))
                        Text("即将采集摄像头噪声，并在设备支持时采集陀螺仪数据。请允许使用摄像头，并在采集时随意晃动手机。本次采集时长为 3 秒。", color = Muted)
                        ActionButton("开始采集", onBeginCamera, icon = { Icon(Icons.Default.CameraAlt, null) })
                    }
                    Screen.ENTROPY_CAMERA -> {
                        Heading("采集相机与陀螺仪数据", "第 1 步 · 3 秒 / 3 帧")
                        EntropyCameraCapture(cameraFrames, onCameraFrame, onGyroscopeDigest, onCollectionError)
                    }
                    Screen.ENTROPY_AUDIO_INFO -> {
                        Heading("准备采集麦克风噪声", "第 2 步 · 3 秒")
                        Icon(Icons.Default.Mic, contentDescription = null, tint = Accent, modifier = Modifier.size(48.dp))
                        Text("即将获取麦克风数据。请允许使用麦克风，并在采集时随意发出一点声音。本次采集时长为 3 秒。", color = Muted)
                        ActionButton("开始采集", onBeginAudio, icon = { Icon(Icons.Default.Mic, null) })
                    }
                    Screen.ENTROPY_AUDIO -> {
                        Heading("采集麦克风数据", "第 2 步 · 3 秒")
                        EntropyAudioCapture(onAudioDigest, onCollectionError)
                    }
                    Screen.ENTROPY_DRAW -> {
                        Heading("绘制图形", "第 3 步 · 完成后确认")
                        EntropyDrawing(onDrawingConfirm)
                    }
                    Screen.BACKUP, Screen.RECOVERY -> {
                        Heading(if (screen == Screen.BACKUP) "备份助记词" else "恢复助记词", "请在无人旁观时记录，并离线妥善保管")
                        Box(Modifier.fillMaxWidth()) {
                            val displayedPhrase = if (phraseVisible) phrase else
                                List(phrase.count { it == ' ' } + 1) { "***" }.joinToString(" ")
                            PhraseGrid(displayedPhrase)
                            if (!phraseVisible) {
                                Button(
                                    onClick = { phraseVisible = true },
                                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Icon(Icons.Default.Visibility, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("查看助记词")
                                }
                            }
                        }
                        Text("任何拿到这些单词的人都能控制资产。设备丢失后只能凭助记词恢复。", color = Warm)
                        if (screen == Screen.BACKUP) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = confirmed, onCheckedChange = onConfirm, enabled = phraseVisible)
                                Text("我已离线备份并核对顺序")
                            }
                            ActionButton("启用生物识别并保存", onSave, enabled = confirmed && phraseVisible, icon = { Icon(Icons.Default.Fingerprint, null) })
                        }
                    }
                    Screen.IMPORT -> {
                        Heading("恢复钱包", "输入 12、15、18、21 或 24 个英文单词")
                        OutlinedTextField(
                            value = inputPhrase,
                            onValueChange = onInputPhrase,
                            modifier = Modifier.fillMaxWidth().height(180.dp),
                            label = { Text("助记词") },
                            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                            minLines = 5
                        )
                        ActionButton("验证并加密", onRestore, enabled = inputPhrase.isNotBlank(), icon = { Icon(Icons.Default.Fingerprint, null) })
                    }
                    Screen.HOME -> {
                        Heading("冷钱包", selectedChain.derivationPath)
                        ChainSelector(selectedChain, onSelectChain)
                        Column(Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(8.dp)).padding(20.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("账户地址", color = Muted)
                                Spacer(Modifier.weight(1f))
                                if (address.isNotEmpty()) {
                                    IconButton(onClick = { showAddressQr = !showAddressQr }) {
                                        Icon(Icons.Default.QrCode2, contentDescription = "显示地址二维码", tint = Accent)
                                    }
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Text(address.ifEmpty { "锁定" }, fontSize = if (address.isEmpty()) 28.sp else 17.sp, lineHeight = 26.sp, color = Paper)
                            if (showAddressQr && address.isNotEmpty()) {
                                Spacer(Modifier.height(16.dp))
                                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    QrImage(address, "钱包地址二维码", Modifier.size(200.dp))
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Text("私钥由生物识别授权解密", color = Accent, style = MaterialTheme.typography.labelMedium)
                        }
                        if (address.isEmpty()) ActionButton("解锁地址", onUnlock, enabled = !busy, icon = { Icon(Icons.Default.Fingerprint, null) })
                        ActionButton("扫描签名请求", onScan, icon = { Icon(Icons.Default.QrCodeScanner, null) })
                        OutlinedButton(onClick = onRequest, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.ContentPaste, null)
                            Spacer(Modifier.width(10.dp))
                            Text("输入签名请求")
                        }
                        HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
                        TextButton(onClick = onRecovery) {
                            Icon(Icons.Default.Visibility, null)
                            Spacer(Modifier.width(8.dp))
                            Text("查看恢复助记词")
                        }
                        TextButton(onClick = onDelete) {
                            Icon(Icons.Default.DeleteOutline, null)
                            Spacer(Modifier.width(8.dp))
                            Text("销毁本机钱包")
                        }
                    }
                    Screen.REQUEST -> {
                        Heading("签名请求", "导入来自联网设备的离线请求")
                        ChainSelector(selectedChain, onSelectChain)
                        OutlinedTextField(
                            value = requestText,
                            onValueChange = onRequestText,
                            modifier = Modifier.fillMaxWidth().height(240.dp),
                            label = { Text("请求 JSON") },
                            minLines = 8
                        )
                        ActionButton("审核请求", { onPayload(requestText) }, enabled = requestText.isNotBlank(), icon = { Icon(Icons.Default.CheckCircle, null) })
                        OutlinedButton(onClick = onScan, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.QrCodeScanner, null)
                            Spacer(Modifier.width(8.dp))
                            Text("扫码导入")
                        }
                    }
                    Screen.SCAN -> {
                        Heading("扫描请求", "对准联网设备上的请求二维码")
                        QrScanner(onPayload)
                    }
                    Screen.REVIEW -> {
                        Heading("核对签名内容", "确认每个字段后再进行生物识别")
                        Detail("网络", selectedChain.label)
                        when (request) {
                            is SigningRequest.Message -> {
                                Detail("类型", "EIP-191 个人消息签名")
                                Detail("消息", request.text)
                            }
                            is SigningRequest.Transaction -> {
                                Detail("类型", "以太坊 EIP-155 交易")
                                Detail("链 ID", request.chainId.toString())
                                Detail("接收地址", request.to)
                                Detail("转账金额", "${WalletEngine.ether(request.value)} ${selectedChain.symbol}")
                                Detail("Gas 上限", request.gasLimit.toString())
                                Detail("Gas 价格", "${WalletEngine.gwei(request.gasPrice)} Gwei")
                                Detail("最高网络费用", "${WalletEngine.ether(request.gasLimit * request.gasPrice)} ${selectedChain.symbol}")
                                Detail("Nonce", request.nonce.toString())
                                Detail("调用数据", request.data)
                                if (request.data != "0x") Text("包含合约调用数据，请确认其实际效果。", color = Warm)
                            }
                            is SigningRequest.Eip1559Transaction -> {
                                Detail("类型", "以太坊 EIP-1559 交易")
                                Detail("链 ID", request.chainId.toString())
                                Detail("接收地址", request.to)
                                Detail("转账金额", "${WalletEngine.ether(request.value)} ${selectedChain.symbol}")
                                Detail("Gas 上限", request.gasLimit.toString())
                                Detail("优先费用", "${WalletEngine.gwei(request.maxPriorityFeePerGas)} Gwei")
                                Detail("最高 Gas 单价", "${WalletEngine.gwei(request.maxFeePerGas)} Gwei")
                                Detail("最高网络费用", "${WalletEngine.ether(request.gasLimit * request.maxFeePerGas)} ${selectedChain.symbol}")
                                Detail("Nonce", request.nonce.toString())
                                Detail("调用数据", request.data)
                                if (request.data != "0x") Text("包含合约调用数据，请确认其实际效果。", color = Warm)
                            }
                            is SigningRequest.ChainTransaction -> {
                                request.review.forEach { (label, value) -> Detail(label, value) }
                                if (request.warning.isNotEmpty()) Text(request.warning, color = Warm)
                            }
                            null -> Unit
                        }
                        ActionButton("确认并签名", onSign, enabled = !busy && request != null, icon = { Icon(Icons.Default.Fingerprint, null) })
                    }
                    Screen.RESULT -> {
                        Heading("签名完成", "让联网设备扫描此二维码")
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { QrImage(result) }
                        Detail("签名结果", result)
                        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("完成") }
                    }
                    Screen.DELETE -> {
                        Heading("销毁本机钱包", "此操作无法撤销")
                        Text("设备中的加密助记词和 Keystore 密钥将被删除。确认已保存可用的恢复助记词。", color = Warm)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = onConfirmDelete, colors = ButtonDefaults.buttonColors(containerColor = Warm), modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.DeleteOutline, null)
                            Spacer(Modifier.width(8.dp))
                            Text("确认销毁")
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChainSelector(selected: WalletChain, onSelect: (WalletChain) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
        ChainImage(selected, 28.dp)
        Spacer(Modifier.width(12.dp))
        Text(selected.label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "切换网络")
    }
    if (expanded) {
        ModalBottomSheet(
            onDismissRequest = { expanded = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Surface,
            shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
        ) {
            Text(
                "选择网络",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 16.dp)
            )
            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp).testTag("chain-list"),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(WalletChain.entries, key = { it.name }) { chain ->
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (chain == selected) Accent.copy(alpha = 0.1f) else Color.Transparent)
                            .clickable {
                                expanded = false
                                onSelect(chain)
                            }
                            .height(58.dp)
                            .padding(horizontal = 22.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ChainImage(chain, 34.dp)
                        Spacer(Modifier.width(14.dp))
                        Text(chain.label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (chain == selected) {
                            Icon(Icons.Default.Check, contentDescription = "已选择", tint = Accent, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChainImage(chain: WalletChain, size: androidx.compose.ui.unit.Dp) {
    Image(painter = painterResource(chain.iconRes), contentDescription = null, modifier = Modifier.size(size))
}

private val WalletChain.iconRes: Int
    get() = when (this) {
        WalletChain.ETHEREUM -> R.drawable.chain_ethereum
        WalletChain.BITCOIN -> R.drawable.chain_bitcoin
        WalletChain.SOLANA -> R.drawable.chain_solana
        WalletChain.TRON -> R.drawable.chain_tron
        WalletChain.BNB_CHAIN -> R.drawable.chain_smartchain
        WalletChain.POLYGON -> R.drawable.chain_polygon
        WalletChain.ARBITRUM -> R.drawable.chain_arbitrum
        WalletChain.OPTIMISM -> R.drawable.chain_optimism
        WalletChain.BASE -> R.drawable.chain_base
        WalletChain.AVALANCHE -> R.drawable.chain_avalanchec
        WalletChain.GNOSIS -> R.drawable.chain_xdai
        WalletChain.CRONOS -> R.drawable.chain_cronos
        WalletChain.SONIC -> R.drawable.chain_sonic
    }

@Composable
private fun Heading(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(subtitle, color = Muted, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PhraseGrid(phrase: String) {
    val words = phrase.split(' ')
    Column(Modifier.clearAndSetSemantics { }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        words.chunked(2).forEachIndexed { rowIndex, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEachIndexed { index, word ->
                    Row(
                        Modifier.weight(1f).border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 12.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("${rowIndex * 2 + index + 1}.", color = Accent, modifier = Modifier.width(30.dp), fontSize = 12.sp)
                        Text(word, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Column(Modifier.fillMaxWidth().border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(6.dp)).padding(14.dp)) {
        Text(label, color = Muted, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(7.dp))
        Text(value, color = Paper, style = MaterialTheme.typography.bodyMedium, lineHeight = 21.sp)
    }
}

@Composable
private fun ActionButton(label: String, onClick: () -> Unit, enabled: Boolean = true, icon: @Composable () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(6.dp)) {
        icon()
        Spacer(Modifier.width(9.dp))
        Text(label, fontWeight = FontWeight.SemiBold)
    }
}
