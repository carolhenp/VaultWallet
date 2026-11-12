package com.micro.airvault

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.ScrollState
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
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.micro.airvault.ui.theme.Accent
import com.micro.airvault.ui.theme.Muted
import com.micro.airvault.ui.theme.Paper
import com.micro.airvault.ui.theme.Surface
import com.micro.airvault.ui.theme.Warm
import org.json.JSONObject
import com.micro.airprotocol.BitcoinAddressType

@Composable
internal fun VaultUi(
    screen: Screen,
    onRetryStartup: () -> Unit = {},
    customChains: List<CustomChain> = emptyList(),
    onAddCustomChain: () -> Unit = {},
    onSaveCustomChain: (String, String, CustomAddressFormat, String) -> Unit = { _, _, _, _ -> },
    phrase: String,
    phraseRevealEpoch: Int,
    mnemonicImport: MnemonicImportState,
    importKeyboardLetters: String,
    importInputEpoch: Int,
    onImportWordCount: (Int) -> Unit,
    onImportPosition: (Int) -> Unit,
    onImportWord: (String) -> Unit,
    confirmed: Boolean,
    onConfirm: (Boolean) -> Unit,
    backupPositions: List<Int>,
    backupAnswers: Map<Int, String>,
    incorrectBackupPositions: Set<Int>,
    backupVerified: Boolean,
    backupKeyboardLetters: String,
    activeBackupPosition: Int,
    address: String,
    requestText: String,
    onRequestText: (String) -> Unit,
    request: SigningRequest?,
    result: String,
    resultFrames: List<String>,
    addressFrames: List<String>,
    scanProgress: String,
    scanError: String,
    onDismissScanError: () -> Unit,
    error: String,
    busy: Boolean,
    backPhotos: Int,
    frontPhotos: Int,
    audioProgress: Int,
    gyroProgress: Int,
    gyroSkipped: Boolean,
    collectionReady: Boolean,
    selectedChain: WalletChain,
    bitcoinAddressType: BitcoinAddressType,
    onSelectBitcoinAddressType: (BitcoinAddressType) -> Unit,
    onSelectChain: (WalletChain) -> Unit,
    onBack: () -> Unit,
    onCreate: () -> Unit,
    onBeginCollection: () -> Unit,
    onCameraPhoto: (CameraSide, ByteArray) -> Unit,
    onAudioProgress: (Int) -> Unit,
    onAudioData: (ByteArray) -> Unit,
    onGyroscopeProgress: (Int) -> Unit,
    onGyroscopeData: (ByteArray?) -> Unit,
    onDrawingConfirm: (List<DrawSample>) -> Unit,
    onCollectionError: (String) -> Unit,
    onImport: () -> Unit,
    onStartBackupVerification: () -> Unit,
    onSelectBackupPosition: (Int) -> Unit,
    onSelectBackupWord: (String) -> Unit,
    onSubmitBackupVerification: () -> Unit,
    onSave: () -> Unit,
    onRestore: () -> Unit,
    onUnlock: () -> Unit,
    onScan: () -> Unit,
    onRequest: () -> Unit,
    onPayload: (String) -> Unit,
    onSign: () -> Unit,
    onOpenWallet: () -> Unit,
    onRecovery: () -> Unit,
    onDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    wallets: List<StoredWallet>,
    currentWallet: StoredWallet?,
    walletName: String,
    onWalletName: (String) -> Unit,
    onSelectWallet: (String) -> Unit,
    onAddWallet: () -> Unit,
    accountRows: Map<WalletAccount, DerivedAddress>,
    selectedAccount: WalletAccount,
    onOpenAccount: (WalletAccount) -> Unit,
    onRenameWallet: (String) -> Unit,
    onCopyAddress: () -> Unit,
    onBrowseAddresses: () -> Unit,
    browserBranch: Int,
    browserAddresses: List<DerivedAddress>,
    onBrowserBranch: (Int) -> Unit,
    pairingWallet: PairingWallet,
    pairingFrames: List<String>,
    onPair: (PairingWallet) -> Unit
) {
    if (screen == Screen.SPLASH) {
        VaultSplashContent(busy, error, onRetryStartup)
        return
    }
    var phraseVisible by remember(screen, phraseRevealEpoch) { mutableStateOf(false) }
    val pageScroll = remember(screen) { ScrollState(0) }
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (screen != Screen.HOME && (screen != Screen.WELCOME || wallets.isNotEmpty())) {
                    IconButton(onClick = onBack, enabled = !busy, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                    Spacer(Modifier.width(8.dp))
                } else {
                    Icon(Icons.Default.Shield, contentDescription = null, tint = Accent)
                    Spacer(Modifier.width(10.dp))
                }
                Text(when (screen) {
                    Screen.IMPORT -> "导入助记词"
                    Screen.ACCOUNTS -> "钱包地址"
                    Screen.ADDRESS -> "地址详情"
                    Screen.PAIRING -> "配对账户"
                    Screen.ADDRESS_BROWSER -> "地址浏览器"
                    else -> "AirVault"
                }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                if (screen == Screen.BACKUP_VERIFY && !backupVerified) {
                    TextButton(
                        onClick = onSubmitBackupVerification,
                        enabled = MnemonicBackupVerification.filled(backupPositions, backupAnswers),
                        modifier = Modifier.testTag("backup-verify-action")
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("验证")
                    }
                } else if (screen == Screen.IMPORT) {
                    TextButton(onClick = onRestore, enabled = !busy && mnemonicImport.complete,
                        modifier = Modifier.testTag("import-next")) {
                        Text("下一步")
                        Icon(Icons.Default.ChevronRight, contentDescription = null)
                    }
                } else if (screen == Screen.ACCOUNTS) {
                    HomeActionsMenu(onRecovery, onDelete, onAddCustomChain, enabled = !busy)
                } else if (screen == Screen.ADDRESS) {
                    AddressActionsMenu(onCopyAddress, onBrowseAddresses, onScan, enabled = !busy,
                        supportsSigning = selectedAccount.supportsSigning)
                }
            }
            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
            if (screen == Screen.RESULT) {
                SigningResultContent(result, resultFrames, onOpenWallet, onBack, error)
            } else if (screen == Screen.HOME) {
                WalletCards(wallets, busy, onSelectWallet, onAddWallet, error, customChains.size)
            } else if (screen == Screen.ACCOUNTS) {
                WalletAddressList(currentWallet?.name.orEmpty(), accountRows, busy, error, onUnlock, onOpenAccount, onRenameWallet, WalletAccount.including(customChains))
            } else if (screen == Screen.CUSTOM_CHAIN) {
                CustomChainContent(busy, error, onSaveCustomChain)
            } else if (screen == Screen.IMPORT) {
                MnemonicImportContent(mnemonicImport, importKeyboardLetters, importInputEpoch, busy, error,
                    onImportWordCount, onImportPosition, onImportWord)
            } else if (screen == Screen.ADDRESS) {
                WalletAddressContent(selectedAccount, accountRows[selectedAccount], busy, onPair, onScan, error)
            } else if (screen == Screen.PAIRING) {
                AccountPairingContent(pairingWallet, selectedAccount, pairingFrames)
            } else if (screen == Screen.ADDRESS_BROWSER) {
                DerivedAddressBrowser(selectedAccount, browserBranch, browserAddresses, busy, onBrowserBranch, error)
            } else {
                Column(
                    Modifier.weight(1f).verticalScroll(pageScroll).padding(horizontal = 22.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                if (error.isNotEmpty() && screen != Screen.BACKUP_VERIFY) {
                    Text(error, color = Warm, modifier = Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(6.dp)).padding(14.dp))
                }
                when (screen) {
                    Screen.WELCOME -> {
                        WelcomeActionCard(
                            title = "生成助记词",
                            description = "结合安全随机数、前后摄像头图像、麦克风声音、陀螺仪变化（如可用）和手绘轨迹，生成 24 词 BIP-39 助记词。",
                            icon = Icons.Default.Key,
                            onClick = onCreate
                        )
                        WelcomeActionCard(
                            title = "导入助记词",
                            description = "支持 BIP-39 英文助记词（12、15、18、21、24 词），以及 BIP-44、BIP-84、BIP-86 和 Solana Ed25519 派生路径。",
                            icon = Icons.Default.FileDownload,
                            onClick = onImport
                        )
                    }
                    Screen.ENTROPY_INFO -> {
                        Heading("准备采集随机数据", "用于生成新的助记词")
                        Icon(Icons.Default.CameraAlt, contentDescription = null, tint = Accent, modifier = Modifier.size(48.dp))
                        Text("创建钱包需要采集前后置摄像头画面、麦克风声音、陀螺仪运动数据和手绘轨迹，用于在本机生成助记词。继续后会申请相机和麦克风权限。请在画板上随意绘图，并晃动手机、发出声音；没有陀螺仪的设备会跳过该项采集。采集过程不会保存照片或录音。", color = Muted)
                        EntropyPermissionGate(onContinue = onBeginCollection)
                    }
                    Screen.ENTROPY_DRAW -> {
                        Heading("绘制图形", "完成采集后确认")
                        EntropyDrawing(collectionReady, onDrawingConfirm) { drawingProgress ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                EntropyProgress("随机数", 100, { Icon(Icons.Default.Casino, null) }, Modifier.weight(1f))
                                EntropyProgress("摄像头", (backPhotos + frontPhotos) * 100 /
                                    (EntropyCollector.PHOTOS_PER_CAMERA * 2),
                                    { Icon(Icons.Default.CameraAlt, null) }, Modifier.weight(1f))
                                EntropyProgress("声音", audioProgress, { Icon(Icons.Default.Mic, null) }, Modifier.weight(1f))
                                EntropyProgress("陀螺仪", gyroProgress, { Icon(Icons.Default.Sensors, null) },
                                    Modifier.weight(1f), gyroSkipped)
                                EntropyProgress("画板", drawingProgress, { Icon(Icons.Default.Edit, null) }, Modifier.weight(1f))
                            }
                        }
                        EntropyCameraCapture(onCameraPhoto, onCollectionError)
                        EntropyAudioCapture(onAudioProgress, onAudioData, onCollectionError)
                        EntropyGyroscopeCapture(onGyroscopeProgress, onGyroscopeData)
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
                                    modifier = Modifier.align(Alignment.Center),
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
                            ActionButton("验证助记词", onStartBackupVerification,
                                enabled = confirmed && phraseVisible,
                                icon = { Icon(Icons.Default.CheckCircle, null) })
                        }
                    }
                    Screen.BACKUP_VERIFY -> {
                        Heading("验证助记词", "填写随机抽取的 10 个单词后统一验证")
                        BackupVerification(
                            positions = backupPositions,
                            answers = backupAnswers,
                            incorrect = incorrectBackupPositions,
                            passed = backupVerified,
                            keyboardLetters = backupKeyboardLetters,
                            active = activeBackupPosition,
                            error = error,
                            onSelectPosition = onSelectBackupPosition,
                            onSelectWord = onSelectBackupWord
                        )
                    }
                    Screen.WALLET_NAME -> {
                        WalletNameContent(walletName, onWalletName, onSave, busy)
                    }
                    Screen.IMPORT, Screen.HOME, Screen.ACCOUNTS, Screen.ADDRESS, Screen.PAIRING, Screen.ADDRESS_BROWSER -> Unit
                    Screen.REQUEST -> {
                        Heading("签名请求", "导入来自联网设备的离线请求")
                        ChainSelector(selectedChain, onSelectChain)
                        if (selectedChain == WalletChain.BITCOIN) {
                            BitcoinAddressTypeSelector(bitcoinAddressType, onSelectBitcoinAddressType, !busy)
                        }
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
                        if (scanProgress.isNotEmpty()) Text(scanProgress, color = Accent)
                        QrScanner(onPayload)
                        if (scanError.isNotEmpty()) {
                            AlertDialog(
                                onDismissRequest = onDismissScanError,
                                title = { Text("无法读取签名请求") },
                                text = { Text(scanError) },
                                confirmButton = {
                                    TextButton(onClick = onDismissScanError) { Text("继续扫描") }
                                }
                            )
                        }
                    }
                    Screen.REVIEW -> {
                        Heading("批准交易", "核对交易详情后进行生物识别")
                        Detail("钱包", currentWallet?.name.orEmpty())
                        Detail("网络", selectedChain.label)
                        Detail("发起地址", address.ifEmpty { "本钱包默认账户，签名时解锁" })
                        when (request) {
                            is SigningRequest.Message -> {
                                Detail("类型", "EIP-191 个人消息签名")
                                Detail("链 ID", request.chainId.toString())
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
                                ContractReview.decode(request.data, selectedChain).forEach { (label, value) -> Detail(label, value) }
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
                                ContractReview.decode(request.data, selectedChain).forEach { (label, value) -> Detail(label, value) }
                                if (request.data != "0x") Text("包含合约调用数据，请确认其实际效果。", color = Warm)
                            }
                            is SigningRequest.ChainTransaction -> {
                                request.review.forEach { (label, value) -> Detail(label, value) }
                                if (request.warning.isNotEmpty()) Text(request.warning, color = Warm)
                                Detail("原始未签名交易", request.payload)
                                request.previousTransactions.forEachIndexed { index, previous ->
                                    Detail("前序交易 ${index + 1} 原始数据", previous)
                                }
                            }
                            null -> Unit
                        }
                    }
                    Screen.SPLASH, Screen.CUSTOM_CHAIN, Screen.RESULT -> Unit
                    Screen.DELETE -> {
                        Heading("销毁本机钱包", "此操作无法撤销")
                        Text("将删除「${currentWallet?.name.orEmpty()}」的本机数据。请确认已保存可用的恢复助记词。", color = Warm)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = onConfirmDelete, colors = ButtonDefaults.buttonColors(containerColor = Warm), modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.DeleteOutline, null)
                            Spacer(Modifier.width(8.dp))
                            Text("确认销毁")
                        }
                    }
                }
                }
                if (screen == Screen.REVIEW) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 10.dp)) {
                        ActionButton("批准交易", onSign, enabled = !busy && request != null,
                            icon = { Icon(Icons.Default.Fingerprint, null) })
                    }
                }
            }
        }
    }
}

@Composable
internal fun SigningResultContent(
    result: String,
    frames: List<String>,
    onOpenWallet: () -> Unit,
    onDone: () -> Unit,
    openWalletError: String = ""
) {
    var showSignedData by remember { mutableStateOf(false) }
    val canOpenWallet = frames.isNotEmpty() || remember(result) {
        runCatching { JSONObject(result).has("signedTransaction") }.getOrDefault(false)
    }
    BoxWithConstraints(Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) {
        val qrSize = minOf(maxWidth, (maxHeight - if (frames.size > 1) 182.dp else 158.dp)
            .coerceAtLeast(96.dp))
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("交易已批准", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("签名结果", color = Muted, style = MaterialTheme.typography.bodySmall)
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                if (frames.isNotEmpty()) {
                    QrAnimation(frames, "签名结果二维码",
                        Modifier.size(qrSize).testTag("signed-result-qr"))
                } else {
                    QrImage(result, "签名结果二维码",
                        Modifier.size(qrSize).testTag("signed-result-qr"))
                }
            }
            if (canOpenWallet) {
                OutlinedButton(onClick = onOpenWallet, modifier = Modifier.fillMaxWidth()) {
                    Text("在本机 AirWallet 中打开")
                }
            }
            if (openWalletError.isNotEmpty()) {
                Text(openWalletError, color = Warm, style = MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { showSignedData = true }) { Text("查看签名数据") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDone) { Text("完成") }
            }
        }
    }
    if (showSignedData) {
        AlertDialog(
            onDismissRequest = { showSignedData = false },
            title = { Text("签名结果") },
            text = {
                Text(result, modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            },
            confirmButton = { TextButton(onClick = { showSignedData = false }) { Text("关闭") } }
        )
    }
}

@Composable
internal fun ExportAddressDialog(frames: List<String>, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) {
            val qrSize = minOf(maxWidth - 24.dp, maxHeight - 112.dp)
            Column(
                Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(8.dp))
                    .padding(12.dp).testTag("address-export-dialog"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("导出观察账户", modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "关闭")
                    }
                }
                QrAnimation(frames, "观察账户二维码", Modifier.size(qrSize).testTag("address-export-qr"))
            }
        }
    }
}

@Composable
internal fun HomeActionsMenu(
    onRecovery: () -> Unit,
    onDelete: () -> Unit,
    onAddCustomChain: () -> Unit = {},
    enabled: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.testTag("home-actions-menu")) {
            Icon(Icons.Default.MoreVert, contentDescription = "钱包操作")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("添加自定义链") },
                leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
                onClick = { expanded = false; onAddCustomChain() }
            )
            DropdownMenuItem(
                text = { Text("查看助记词") },
                leadingIcon = { Icon(Icons.Default.Visibility, contentDescription = null) },
                onClick = {
                    expanded = false
                    onRecovery()
                }
            )
            DropdownMenuItem(
                text = { Text("销毁钱包", color = Warm) },
                leadingIcon = { Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = Warm) },
                onClick = {
                    expanded = false
                    onDelete()
                }
            )
        }
    }
}

@Composable
internal fun WalletNameContent(name: String, onName: (String) -> Unit, onSave: () -> Unit, busy: Boolean) {
    Heading("设置钱包名称", "为钱包起一个方便辨认的名称")
    OutlinedTextField(
        value = name,
        onValueChange = onName,
        enabled = !busy,
        singleLine = true,
        label = { Text("钱包名称") },
        supportingText = { Text("${name.trim().length} / ${VaultStore.MAX_NAME_LENGTH}") },
        isError = name.trim().length > VaultStore.MAX_NAME_LENGTH,
        modifier = Modifier.fillMaxWidth().testTag("wallet-name-input")
    )
    ActionButton(if (busy) "正在保存…" else "启用生物识别并保存", onSave,
        enabled = !busy && name.trim().length in 1..VaultStore.MAX_NAME_LENGTH,
        icon = { Icon(Icons.Default.Fingerprint, null) })
}

@Composable
internal fun WalletSelector(
    wallets: List<StoredWallet>,
    current: StoredWallet?,
    onSelect: (String) -> Unit,
    enabled: Boolean
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled,
            modifier = Modifier.fillMaxWidth().testTag("wallet-selector")) {
            Text(current?.name ?: "冷钱包", modifier = Modifier.weight(1f), maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "切换钱包")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            wallets.forEach { wallet ->
                DropdownMenuItem(
                    text = { Text(wallet.name) },
                    trailingIcon = {
                        if (wallet.id == current?.id) Icon(Icons.Default.Check, contentDescription = "当前钱包")
                    },
                    onClick = { expanded = false; onSelect(wallet.id) }
                )
            }
        }
    }
}

@Composable
internal fun BitcoinAddressTypeSelector(selected: BitcoinAddressType, onSelect: (BitcoinAddressType) -> Unit, enabled: Boolean = true) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled,
            modifier = Modifier.fillMaxWidth().testTag("bitcoin-address-type-selector")) {
            Text("地址类型 · ${selected.label}", modifier = Modifier.weight(1f))
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "切换 Bitcoin 地址类型")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            BitcoinAddressType.entries.forEach { type ->
                DropdownMenuItem(
                    text = { Column { Text(type.label); Text(type.derivationPath, color = Muted, fontSize = 12.sp) } },
                    trailingIcon = { if (type == selected) Icon(Icons.Default.Check, contentDescription = "当前地址类型") },
                    onClick = { expanded = false; onSelect(type) }
                )
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
internal fun ChainImage(chain: WalletChain, size: androidx.compose.ui.unit.Dp) {
    Image(painter = painterResource(chain.iconRes), contentDescription = null, modifier = Modifier.size(size))
}

private val WalletChain.iconRes: Int
    get() = when (this) {
        WalletChain.ETHEREUM -> R.drawable.chain_ethereum
        WalletChain.BITCOIN -> R.drawable.chain_bitcoin
        WalletChain.SOLANA -> R.drawable.chain_solana
        WalletChain.TRON -> R.drawable.chain_tron
        WalletChain.ZCASH -> R.drawable.chain_zcash
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
private fun WelcomeActionCard(title: String, description: String, icon: ImageVector, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = Accent, modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(12.dp))
                Text(title, modifier = Modifier.weight(1f), color = Paper,
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Muted)
            }
            Text(description, color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
        }
    }
}

@Composable
internal fun PhraseGrid(phrase: String) {
    val words = phrase.split(' ')
    Column(Modifier.testTag("backup-phrase-grid").clearAndSetSemantics { },
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        words.chunked(3).forEachIndexed { rowIndex, row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEachIndexed { index, word ->
                    Row(
                        Modifier.weight(1f).height(38.dp)
                            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("${rowIndex * 3 + index + 1}.", color = Accent,
                            modifier = Modifier.width(22.dp), fontSize = 11.sp)
                        Text(word, fontWeight = FontWeight.Medium, fontSize = 12.sp,
                            maxLines = 1, softWrap = false)
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun Detail(label: String, value: String) {
    var showFullValue by remember(label, value) { mutableStateOf(false) }
    val longValue = value.length > 1024
    Column(Modifier.fillMaxWidth().border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(6.dp)).padding(14.dp)) {
        Text(label, color = Muted, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(7.dp))
        Text(if (longValue) value.take(256) + "..." else value,
            color = Paper, style = MaterialTheme.typography.bodyMedium, lineHeight = 21.sp)
        if (longValue) {
            TextButton(onClick = { showFullValue = true }) { Text("查看完整$label") }
        }
    }
    if (showFullValue) {
        AlertDialog(
            onDismissRequest = { showFullValue = false },
            title = { Text(label) },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items((value.length + 511) / 512) { index ->
                        Text(value.substring(index * 512, minOf((index + 1) * 512, value.length)),
                            color = Paper, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showFullValue = false }) { Text("关闭") } }
        )
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
