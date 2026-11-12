package com.micro.airwallet

import com.micro.airprotocol.BitcoinAddressType

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.micro.airprotocol.WireCodec
import com.micro.airprotocol.WireMessage
import com.micro.airwallet.ui.theme.Accent
import com.micro.airwallet.ui.theme.Ink
import com.micro.airwallet.ui.theme.Muted
import com.micro.airwallet.ui.theme.Surface
import com.micro.airwallet.ui.theme.Warm
import org.web3j.utils.Numeric
import java.security.MessageDigest

@Composable
internal fun WalletUi(
    screen: Screen, chain: WalletChain, account: WatchedAccount?, accounts: Map<WalletChain, WatchedAccount>,
    balance: String, history: List<HistoryEntry>, lastSync: Long, draft: TransferDraft,
    pending: WireMessage?, signedRaw: ByteArray?,
    endpoint: String, manualAddress: String, compiled: String, previous: String, scanProgress: String,
    error: String, notice: String, busy: Boolean, refreshing: Boolean,
    onBack: () -> Unit, onSelectChain: (WalletChain) -> Unit,
    onRefresh: () -> Unit, onImport: () -> Unit, onScanAccount: () -> Unit, onScanResult: () -> Unit,
    onScanFrame: (String) -> Unit, onManualAddress: (String) -> Unit, onManualImport: () -> Unit,
    onCompose: () -> Unit, onDraft: (TransferDraft) -> Unit, onCompiled: (String) -> Unit,
    onPrevious: (String) -> Unit, onPrepare: () -> Unit, onPrepareCompiled: () -> Unit,
    onShowPending: () -> Unit,
    onOpenVault: () -> Unit,
    onBroadcast: () -> Unit, onSettings: () -> Unit, onEndpoint: (String) -> Unit,
    onSaveEndpoint: () -> Unit, onCancelPending: () -> Unit
) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (screen != Screen.HOME && screen != Screen.IMPORT) IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                }
                Text("AirWallet", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                Text("观察钱包", color = Accent, style = MaterialTheme.typography.labelLarge)
                if (screen == Screen.HOME && !chain.isAddressOnly) IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "网络设置") }
            }
            HorizontalDivider()
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (notice.isNotBlank() && screen == Screen.HOME) Text(notice, color = Accent)
                if (error.isNotBlank()) Text(error, color = Warm,
                    modifier = Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(6.dp)).padding(12.dp))
                when (screen) {
                    Screen.HOME -> {
                        ChainPicker(chain, onSelectChain)
                        if (!chain.isAddressOnly) {
                            Text("可用余额", color = Muted)
                            Text("$balance ${chain.symbol}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                        }
                        if (account == null) {
                            Text("此网络尚未导入观察地址", color = Muted)
                            FullButton("导入观察账户", onImport)
                        } else {
                            Text("账户地址", color = Muted)
                            if (chain == WalletChain.BITCOIN) {
                                Text(BitcoinAddressType.fromAddress(account.address).label, color = Muted)
                            }
                            if (chain == WalletChain.ZCASH) Text("透明地址 · P2PKH", color = Muted)
                            Text(account.address, style = MaterialTheme.typography.bodyMedium)
                            if (chain.isAddressOnly) {
                                Text("已同步公开地址，暂不支持余额查询和交易签名。", color = Muted)
                            } else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(onClick = onCompose, modifier = Modifier.weight(1f)) {
                                    Icon(Icons.AutoMirrored.Filled.Send, null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("发送")
                                }
                                OutlinedButton(onClick = onRefresh, enabled = !refreshing) {
                                    Icon(Icons.Default.Refresh, "刷新")
                                }
                            }
                        }
                        if (pending != null) {
                            HorizontalDivider()
                            Text("待签交易", style = MaterialTheme.typography.titleMedium)
                            Text(WalletChain.entries[pending.chain].label + " · " + pending.id.take(4).joinToString("") { "%02x".format(it) }, color = Muted)
                            OutlinedButton(onClick = onShowPending, modifier = Modifier.fillMaxWidth()) {
                                Text("显示待签二维码")
                            }
                            OutlinedButton(onClick = onScanResult, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.QrCodeScanner, null)
                                Spacer(Modifier.width(8.dp))
                                Text("扫描签名结果")
                            }
                            TextButton(onClick = onCancelPending) { Text("取消待签交易") }
                        }
                        if (!chain.isAddressOnly) {
                            HorizontalDivider()
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("最近交易", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                if (refreshing) Text("更新中", color = Muted)
                            }
                            if (lastSync > 0) Text("上次更新 " + java.text.SimpleDateFormat(
                                "yyyy-MM-dd HH:mm", LocalLocale.current.platformLocale)
                                .format(java.util.Date(lastSync)), color = Muted,
                                style = MaterialTheme.typography.labelSmall)
                            if (history.isEmpty()) Text("暂无交易记录", color = Muted)
                            history.forEach { entry ->
                                Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                                    Row {
                                        Text(entry.direction, color = Ink, modifier = Modifier.weight(1f))
                                        if (entry.amount.isNotEmpty()) Text(entry.amount)
                                    }
                                    Text(entry.hash, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodySmall)
                                    if (entry.time > 0) Text(
                                        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", LocalLocale.current.platformLocale)
                                            .format(java.util.Date(entry.time * 1000)),
                                        color = Muted, style = MaterialTheme.typography.labelSmall)
                                }
                                HorizontalDivider()
                            }
                        }
                        TextButton(onClick = onImport) {
                            Icon(Icons.Default.Add, null)
                            Spacer(Modifier.width(6.dp))
                            Text("添加或更新观察账户")
                        }
                    }
                    Screen.IMPORT -> {
                        Text("导入观察账户", style = MaterialTheme.typography.headlineSmall)
                        Text("扫描 AirVault 的“导出观察账户”二维码", color = Muted)
                        FullButton("扫描二维码", onScanAccount, icon = { Icon(Icons.Default.QrCodeScanner, null) })
                        HorizontalDivider()
                        ChainPicker(chain, onSelectChain)
                        if (chain.isEvm) {
                            Field(manualAddress, onManualAddress, "EVM 地址")
                            OutlinedButton(onClick = onManualImport, modifier = Modifier.fillMaxWidth()) { Text("仅导入此地址") }
                        } else Text("此网络需从 AirVault 扫码导入公钥和地址。", color = Muted)
                    }
                    Screen.SCAN_ACCOUNT, Screen.SCAN_RESULT -> {
                        Text(if (screen == Screen.SCAN_ACCOUNT) "扫描观察账户" else "扫描已签名交易",
                            style = MaterialTheme.typography.headlineSmall)
                        if (scanProgress.isNotBlank()) Text(scanProgress, color = Accent)
                        QrScanner(onScanFrame)
                    }
                    Screen.COMPOSE -> {
                        Text("准备交易", style = MaterialTheme.typography.headlineSmall)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ChainIcon(chain, 30)
                            Spacer(Modifier.width(8.dp))
                            Text(chain.label)
                        }
                        val modes = if (chain == WalletChain.BITCOIN)
                            listOf(TransferMode.NATIVE) else TransferMode.entries
                        if (modes.size > 1) SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            modes.forEachIndexed { index, mode ->
                                SegmentedButton(selected = draft.mode == mode,
                                    onClick = { onDraft(draft.copy(mode = mode)) },
                                    colors = SegmentedButtonDefaults.colors(
                                        activeContainerColor = Accent.copy(alpha = 0.14f),
                                        activeContentColor = Ink),
                                    shape = SegmentedButtonDefaults.itemShape(index, modes.size)) {
                                    Text(when (mode) { TransferMode.NATIVE -> "原生币"; TransferMode.TOKEN -> "代币"; TransferMode.CONTRACT -> "合约" })
                                }
                            }
                        }
                        Field(draft.recipient, { onDraft(draft.copy(recipient = it)) },
                            if (draft.mode == TransferMode.CONTRACT) "合约地址" else "收款地址")
                        Field(draft.amount, { onDraft(draft.copy(amount = it)) },
                            if (draft.mode == TransferMode.TOKEN) "代币数量" else "金额 ${chain.symbol}", KeyboardType.Decimal)
                        if (draft.mode == TransferMode.TOKEN) {
                            Field(draft.tokenContract, { onDraft(draft.copy(tokenContract = it)) }, "代币合约地址")
                            Field(draft.tokenDecimals, { onDraft(draft.copy(tokenDecimals = it)) },
                                if (chain.isEvm || chain == WalletChain.SOLANA) "代币精度（留空自动读取）" else "代币精度", KeyboardType.Number)
                        }
                        if (draft.mode == TransferMode.CONTRACT) Field(draft.data, { onDraft(draft.copy(data = it)) }, "调用数据 0x...", minLines = 3)
                        if (chain.isEvm) {
                            Field(draft.nonce, { onDraft(draft.copy(nonce = it)) }, "Nonce（留空自动获取）", KeyboardType.Number)
                            Field(draft.gasLimit, { onDraft(draft.copy(gasLimit = it)) }, "Gas 上限（留空自动估算）", KeyboardType.Number)
                            Field(draft.fee, { onDraft(draft.copy(fee = it)) }, "最高 Gas 单价 Gwei（留空自动获取）", KeyboardType.Decimal)
                            Field(draft.priorityFee, { onDraft(draft.copy(priorityFee = it)) }, "优先费 Gwei（留空使用 1）", KeyboardType.Decimal)
                        } else if (chain == WalletChain.BITCOIN) {
                            Field(draft.fee, { onDraft(draft.copy(fee = it)) }, "费率 sat/vB（留空使用 5）", KeyboardType.Decimal)
                        } else if (chain == WalletChain.TRON && draft.mode != TransferMode.NATIVE) {
                            Field(draft.gasLimit, { onDraft(draft.copy(gasLimit = it)) }, "费用上限 SUN（留空使用 100000000）", KeyboardType.Number)
                        }
                        FullButton(if (busy) "准备中" else "生成待签交易", onPrepare, !busy)
                        if (chain in listOf(WalletChain.BITCOIN, WalletChain.SOLANA, WalletChain.TRON)) {
                            HorizontalDivider()
                            Text("导入已编译交易", style = MaterialTheme.typography.titleMedium)
                            Field(compiled, onCompiled, when (chain) {
                                WalletChain.BITCOIN -> "PSBT Base64 或未签名交易十六进制"
                                WalletChain.SOLANA -> "未签名交易 Base64"
                                else -> "未签名交易十六进制"
                            }, minLines = 4)
                            if (chain == WalletChain.BITCOIN)
                                Field(previous, onPrevious, "每行一个完整前序交易，按输入顺序", minLines = 4)
                            OutlinedButton(onClick = onPrepareCompiled, enabled = compiled.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                                Text("导入并显示二维码")
                            }
                        }
                    }
                    Screen.REQUEST -> {
                        Text("等待离线签名", style = MaterialTheme.typography.headlineSmall)
                        Text("在 AirVault 扫描以下交易二维码", color = Muted)
                        if (pending != null) {
                            Text(WalletChain.entries[pending.chain].label, fontWeight = FontWeight.Medium)
                            RequestSummary(pending)
                            val frames = remember(pending) { WireCodec.frames(WireCodec.encode(pending)) }
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { AnimatedQr(frames) }
                            Text("请求 ID  " + pending.id.take(8).joinToString("") { "%02x".format(it) }, color = Muted)
                            FullButton("扫描 AirVault 的签名结果", onScanResult, icon = { Icon(Icons.Default.QrCodeScanner, null) })
                            OutlinedButton(onClick = onOpenVault, modifier = Modifier.fillMaxWidth()) {
                                Text("在本机 AirVault 中打开")
                            }
                        }
                    }
                    Screen.VERIFIED -> {
                        Text("签名已验证", style = MaterialTheme.typography.headlineSmall)
                        if (pending != null && signedRaw != null) {
                            Text("网络  " + WalletChain.entries[pending.chain].label)
                            Text("账户  " + (accounts[WalletChain.entries[pending.chain]]?.address ?: ""))
                            Text("交易摘要  " + Numeric.toHexString(MessageDigest.getInstance("SHA-256").digest(signedRaw)),
                                style = MaterialTheme.typography.bodySmall)
                            FullButton(if (busy) "广播中" else "广播交易", onBroadcast, !busy,
                                icon = { Icon(Icons.AutoMirrored.Filled.Send, null) })
                        }
                    }
                    Screen.SETTINGS -> {
                        Text("网络节点", style = MaterialTheme.typography.headlineSmall)
                        Text(chain.label)
                        Field(endpoint, onEndpoint, "HTTPS 节点地址", minLines = 2)
                        Text("余额、交易状态与广播均通过此节点查询。", color = Muted)
                        FullButton("保存节点地址", onSaveEndpoint)
                    }
                }
            }
        }
    }
}

@Composable
private fun RequestSummary(message: WireMessage) {
    val fields = message.fields
    val chain = WalletChain.entries.getOrNull(message.chain) ?: return
    when (message.kind) {
        WireCodec.EVM_LEGACY, WireCodec.EVM_1559 -> if (fields.size >= 6) {
            Text("目标  ${Numeric.toHexString(fields[1])}", style = MaterialTheme.typography.bodyMedium)
            Text("金额  ${ChainClient.amount(WireCodec.unsigned(fields[2]), 18)} ${chain.symbol}")
            Text("Gas 上限  ${WireCodec.unsigned(fields[3])}")
            Text("最高网络费用  ${ChainClient.amount(WireCodec.unsigned(fields[3]) *
                WireCodec.unsigned(fields[if (message.kind == WireCodec.EVM_1559) 5 else 4]), 18)} ${chain.symbol}")
            if (fields.last().isNotEmpty()) Text("合约数据  ${Numeric.toHexString(fields.last()).take(66)}…", color = Warm)
        }
        WireCodec.BITCOIN_PSBT -> Text("Bitcoin PSBT · ${fields.single().size} 字节", color = Muted)
        WireCodec.SOLANA -> Text("Solana 编译交易 · ${fields.single().size} 字节", color = Muted)
        WireCodec.TRON -> Text("TRON protobuf 交易 · ${fields.single().size} 字节", color = Muted)
    }
}

@Composable
private fun FullButton(label: String, onClick: () -> Unit, enabled: Boolean = true,
                       icon: (@Composable () -> Unit)? = null) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().height(50.dp),
        shape = RoundedCornerShape(6.dp)) {
        if (icon != null) { icon(); Spacer(Modifier.width(8.dp)) }
        Text(label)
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: String,
                  keyboard: KeyboardType = KeyboardType.Text, minLines: Int = 1) {
    OutlinedTextField(value, onChange, modifier = Modifier.fillMaxWidth(),
        label = { Text(label) }, keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        minLines = minLines, shape = RoundedCornerShape(6.dp))
}

@Composable
private fun ChainIcon(chain: WalletChain, size: Int) {
    Image(painterResource(chain.icon), chain.label, Modifier.size(size.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChainPicker(selected: WalletChain, onSelect: (WalletChain) -> Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(6.dp)) {
        ChainIcon(selected, 28)
        Spacer(Modifier.width(10.dp))
        Text(selected.label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(Icons.Default.KeyboardArrowDown, "切换网络")
    }
    if (open) ModalBottomSheet(onDismissRequest = { open = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)) {
        Text("选择网络", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(18.dp))
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
            WalletChain.entries.forEach { chain ->
                Row(Modifier.fillMaxWidth().clickable { open = false; onSelect(chain) }
                    .height(56.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    ChainIcon(chain, 30)
                    Spacer(Modifier.width(12.dp))
                    Text(chain.label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (chain == selected) Icon(Icons.Default.Check, "已选择", tint = Accent)
                }
            }
        }
    }
}
