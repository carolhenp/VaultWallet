package com.micro.airvault

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.micro.airvault.ui.theme.Accent
import com.micro.airvault.ui.theme.Ink
import com.micro.airvault.ui.theme.Muted
import com.micro.airvault.ui.theme.Paper
import com.micro.airvault.ui.theme.Surface
import com.micro.airvault.ui.theme.Warm

@Composable
internal fun WalletCards(wallets: List<StoredWallet>, busy: Boolean, onOpen: (String) -> Unit, onCreate: () -> Unit, error: String, customChainCount: Int = 0) {
    LazyColumn(Modifier.fillMaxSize().testTag("wallet-card-list"), contentPadding = PaddingValues(22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("我的钱包", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
        if (error.isNotEmpty()) item { Text(error, color = Warm) }
        items(wallets, key = { it.id }) { wallet ->
            Card(onClick = { onOpen(wallet.id) }, enabled = !busy,
                colors = CardDefaults.cardColors(containerColor = Surface),
                modifier = Modifier.fillMaxWidth().testTag("wallet-card-${wallet.id}")) {
                Row(Modifier.padding(22.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.AccountBalanceWallet, null, tint = Accent, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(wallet.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text("${WalletChain.entries.size + customChainCount} 条链 · ${WalletAccount.all.size + customChainCount} 种地址", color = Muted, style = MaterialTheme.typography.bodyMedium)
                    }
                    Icon(Icons.Default.ChevronRight, "浏览钱包地址", tint = Muted)
                }
            }
        }
        item {
            Button(onClick = onCreate, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("create-wallet")) {
                Icon(Icons.Default.Add, null)
                Spacer(Modifier.width(8.dp))
                Text("创建新钱包")
            }
        }
    }
}

@Composable
internal fun WalletAddressList(
    name: String, rows: Map<WalletAccount, DerivedAddress>, busy: Boolean, error: String,
    onUnlock: () -> Unit, onOpen: (WalletAccount) -> Unit, onRename: (String) -> Unit,
    accounts: List<WalletAccount> = WalletAccount.all
) {
    var showRename by rememberSaveable(name) { mutableStateOf(false) }
    var draftName by rememberSaveable(name) { mutableStateOf(name) }
    LazyColumn(Modifier.fillMaxSize().testTag("wallet-address-list"), contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { draftName = name; showRename = true }
                .padding(horizontal = 22.dp, vertical = 8.dp).testTag("wallet-name-edit"),
                verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                Icon(Icons.Default.Edit, "修改钱包名称", tint = Muted, modifier = Modifier.size(20.dp))
            }
        }
        if (error.isNotEmpty()) item { Text(error, color = Warm, modifier = Modifier.padding(horizontal = 22.dp)) }
        if (rows.isEmpty()) item {
            Column(Modifier.padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (busy) "正在读取钱包地址…" else "解锁后查看钱包的所有链地址", color = Muted)
                Button(onClick = onUnlock, enabled = !busy) { Text("解锁地址") }
            }
        }
        items(accounts, key = { it.id }) { account ->
            val derived = rows[account]
            ListItem(
                headlineContent = { Text(if (account.bitcoinType == null || account.customChain != null) "${account.title} · ${account.addressType}" else account.title,
                    fontWeight = FontWeight.Bold, fontSize = 16.sp
                ) },
                supportingContent = { Text(derived?.address ?: "锁定", fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Light, fontSize = 12.sp) },
                leadingContent = { AccountImage(account, 34.dp) },
                trailingContent = { IconButton(onClick = { onOpen(account) }, enabled = !busy && derived != null) {
                    Icon(Icons.Default.ChevronRight, "打开 ${account.title}")
                } },
                modifier = Modifier.fillMaxWidth().accountClick(!busy && derived != null) { onOpen(account) }
                    .testTag("account-${account.id}"),
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    }
    if (showRename) {
        val valid = draftName.trim().length in 1..VaultStore.MAX_NAME_LENGTH
        AlertDialog(onDismissRequest = { if (!busy) showRename = false },
            title = { Text("修改钱包名称") },
            text = {
                OutlinedTextField(value = draftName, onValueChange = { draftName = it },
                    label = { Text("钱包名称") }, singleLine = true, enabled = !busy,
                    isError = !valid, supportingText = { Text("${draftName.trim().length} / ${VaultStore.MAX_NAME_LENGTH}") },
                    modifier = Modifier.fillMaxWidth().testTag("rename-wallet-input"))
            },
            confirmButton = {
                TextButton(onClick = { onRename(draftName); showRename = false }, enabled = valid && !busy) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showRename = false }, enabled = !busy) { Text("取消") } })
    }
}

private fun Modifier.accountClick(enabled: Boolean, action: () -> Unit): Modifier =
    clickable(enabled = enabled, onClick = action)

@Composable
internal fun WalletAddressContent(
    account: WalletAccount, derived: DerivedAddress?, busy: Boolean,
    onPair: (PairingWallet) -> Unit, onScan: () -> Unit, error: String
) {
    var showQr by remember(account) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(22.dp)
            .testTag("address-detail-scroll"),
            verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AccountImage(account, 64.dp)
                Text(account.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                if (account.chain == WalletChain.ZCASH) Text("透明地址 · P2PKH", color = Muted)
            }
            Card(colors = CardDefaults.cardColors(containerColor = Surface)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("钱包地址", color = Muted)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(derived?.address.orEmpty(), modifier = Modifier.weight(1f), fontFamily = FontFamily.Monospace)
                        IconButton(onClick = { showQr = true }, enabled = derived != null, modifier = Modifier.testTag("address-qr-action")) {
                            Icon(Icons.Default.QrCode2, "显示地址二维码", tint = Accent)
                        }
                    }
                    HorizontalDivider()
                    Text("导出路径", color = Muted)
                    Text(derived?.path ?: account.path, fontFamily = FontFamily.Monospace)
                    account.customChain?.takeIf { it.addressFormat == CustomAddressFormat.EVM }?.let { custom ->
                        HorizontalDivider()
                        Text("ChainId", color = Muted)
                        Text(custom.chainId?.toString() ?: "未设置", fontFamily = FontFamily.Monospace,
                            modifier = Modifier.testTag("custom-chain-id-value"))
                    }
                }
            }
            if (account.customChain == null) {
                Text("你可以用以下方式同步账号：", style = MaterialTheme.typography.titleMedium)
                PairingWallet.entries.forEach { target ->
                    Button(onClick = { onPair(target) }, enabled = !busy && derived != null && target.supports(account),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Paper, contentColor = Ink,
                            disabledContainerColor = Paper.copy(alpha = 0.65f), disabledContentColor = Ink.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth().testTag("pair-${target.name}")) {
                        Image(painterResource(when (target) {
                            PairingWallet.AIRWALLET -> R.drawable.pairing_airwallet
                            PairingWallet.METAMASK -> R.drawable.pairing_metamask
                            PairingWallet.TRUST_WALLET -> R.drawable.pairing_trustwallet
                        }), contentDescription = null, modifier = Modifier.size(32.dp).then(
                            if (target == PairingWallet.TRUST_WALLET) Modifier.clip(RoundedCornerShape(8.dp)) else Modifier))
                        Spacer(Modifier.width(14.dp))
                        Text(target.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        Icon(Icons.Default.ChevronRight, null)
                    }
                }
                if (account.chain == WalletChain.ZCASH) Text("Zcash 账号同步仅支持 AirWallet。", color = Muted)
                else if (account.chain == WalletChain.BITCOIN) Text("Bitcoin 账号同步仅支持 AirWallet。Trust Wallet 的只读钱包导入暂不支持 Bitcoin。", color = Muted)
                else if (!account.chain.isEvm) Text("MetaMask 的二维码硬件账户配对支持 EVM 网络。", color = Muted)
            } else {
                Text("自定义链支持地址二维码、复制地址和地址浏览。", color = Muted)
            }
            if (error.isNotEmpty()) Text(error, color = Warm)
        }
        if (account.supportsSigning) {
            HorizontalDivider()
            Box(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp)) {
                Button(onClick = onScan, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().testTag("address-scan-button")) {
                    Icon(Icons.Default.QrCodeScanner, null)
                    Spacer(Modifier.width(8.dp))
                    Text("扫描签名请求")
                }
            }
        }
    }
    if (showQr && derived != null) {
        AlertDialog(onDismissRequest = { showQr = false },
            title = { Text(account.title) },
            text = { Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                QrImage(derived.address, "钱包地址二维码", Modifier.fillMaxWidth().aspectRatio(1f))
                Text(derived.address, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            } },
            confirmButton = { TextButton(onClick = { showQr = false }) { Text("关闭") } })
    }
}

@Composable
internal fun AddressActionsMenu(onCopy: () -> Unit, onBrowse: () -> Unit, onScan: () -> Unit, enabled: Boolean,
                               supportsSigning: Boolean = true) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.testTag("address-actions-menu")) {
            Icon(Icons.Default.MoreVert, "地址操作")
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("复制地址") }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                onClick = { expanded = false; onCopy() })
            DropdownMenuItem(text = { Text("地址浏览器") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.ListAlt, null) },
                onClick = { expanded = false; onBrowse() })
            if (supportsSigning) DropdownMenuItem(text = { Text("扫描签名请求") }, leadingIcon = { Icon(Icons.Default.QrCodeScanner, null) },
                modifier = Modifier.testTag("address-scan-menu-item"),
                onClick = { expanded = false; onScan() })
        }
    }
}

@Composable
internal fun AccountPairingContent(target: PairingWallet, account: WalletAccount, frames: List<String>) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(22.dp)) {
        AccountImage(account, 48.dp)
        Text("配对账户", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("用 ${target.label} 钱包扫描二维码", style = MaterialTheme.typography.titleMedium)
        QrAnimation(frames, "${target.label} 配对二维码", Modifier.fillMaxWidth().aspectRatio(1f).testTag("pairing-qr"))
        Text(account.title, color = Accent)
        Text(when (target) {
            PairingWallet.AIRWALLET -> "在 AirWallet 中选择扫码导入观察账户，同步当前 ${account.title} 的公开地址和公钥。"
            PairingWallet.METAMASK -> "在 MetaMask 的「连接硬件钱包」中选择二维码连接，导入此 EVM 账户。当前用于账户同步；交易签名请使用 AirWallet。"
            PairingWallet.TRUST_WALLET -> "在 Trust Wallet 中添加观察钱包，选择对应网络并扫描地址。观察账户可查看资产，签名请使用 AirWallet。"
        }, color = Muted)
    }
}

@Composable
internal fun DerivedAddressBrowser(account: WalletAccount, branch: Int, addresses: List<DerivedAddress>, busy: Boolean,
    onBranch: (Int) -> Unit, error: String) {
    var selectedAddress by remember(account, branch) { mutableStateOf<DerivedAddress?>(null) }
    val clipboard = LocalContext.current.getSystemService(ClipboardManager::class.java)
    Column(Modifier.fillMaxSize()) {
        Text(account.title, modifier = Modifier.padding(22.dp), style = MaterialTheme.typography.titleLarge)
        SecondaryTabRow(selectedTabIndex = branch) {
            listOf("收款地址", "找零地址").forEachIndexed { index, label ->
                Tab(selected = branch == index, onClick = { onBranch(index) }, enabled = !busy,
                    text = { Text(label) }, modifier = Modifier.testTag("address-tab-$index"))
            }
        }
        if (error.isNotEmpty()) Text(error, color = Warm, modifier = Modifier.padding(22.dp))
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.weight(1f).testTag("derived-address-list"), contentPadding = PaddingValues(22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("${if (branch == 0) "收款地址" else "找零地址"} · 0–99", color = Muted) }
            items(addresses, key = { it.path }) { derived ->
                Card(onClick = { selectedAddress = derived }, enabled = !busy,
                    colors = CardDefaults.cardColors(containerColor = Surface),
                    modifier = Modifier.fillMaxWidth().testTag("derived-${derived.path}")) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(derived.address, fontFamily = FontFamily.Monospace)
                        Text(derived.path, color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
    selectedAddress?.let { derived ->
        AlertDialog(onDismissRequest = { selectedAddress = null },
            modifier = Modifier.testTag("derived-address-qr-dialog"),
            title = { Text(account.title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    QrImage(derived.address, "钱包地址二维码", Modifier.fillMaxWidth().aspectRatio(1f))
                    Text(derived.address, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    Text(derived.path, color = Muted, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = {
                        clipboard.setPrimaryClip(ClipData.newPlainText("钱包地址", derived.address))
                    }, modifier = Modifier.fillMaxWidth().testTag("derived-address-copy")) {
                        Icon(Icons.Default.ContentCopy, null)
                        Spacer(Modifier.width(8.dp))
                        Text("复制地址")
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selectedAddress = null }) { Text("关闭") } })
    }
}

@Composable
internal fun AccountImage(account: WalletAccount, size: androidx.compose.ui.unit.Dp) {
    if (account.customChain == null) ChainImage(account.chain, size)
    else Icon(Icons.Default.AccountBalanceWallet, contentDescription = null, tint = Accent, modifier = Modifier.size(size))
}
