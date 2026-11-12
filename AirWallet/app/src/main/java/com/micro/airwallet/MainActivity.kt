package com.micro.airwallet

import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.core.view.WindowCompat
import androidx.lifecycle.Observer
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.micro.airprotocol.WireCodec
import com.micro.airprotocol.WireMessage
import com.micro.airwallet.ui.theme.AirWalletTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Base64

internal enum class Screen { HOME, IMPORT, SCAN_ACCOUNT, COMPOSE, REQUEST, SCAN_RESULT, VERIFIED, SETTINGS }

class MainActivity : ComponentActivity() {
    private lateinit var store: WatchStore
    private lateinit var client: ChainClient
    private var screen by mutableStateOf(Screen.IMPORT)
    private var selectedChain by mutableStateOf(WalletChain.ETHEREUM)
    private var accounts by mutableStateOf<Map<WalletChain, WatchedAccount>>(emptyMap())
    private var balance by mutableStateOf("--")
    private var history by mutableStateOf<List<HistoryEntry>>(emptyList())
    private var lastSync by mutableStateOf(0L)
    private var draft by mutableStateOf(TransferDraft())
    private var pending by mutableStateOf<WireMessage?>(null)
    private var signedRaw by mutableStateOf<ByteArray?>(null)
    private var endpoint by mutableStateOf("")
    private var manualAddress by mutableStateOf("")
    private var compiled by mutableStateOf("")
    private var previous by mutableStateOf("")
    private var scanProgress by mutableStateOf("")
    private var error by mutableStateOf("")
    private var notice by mutableStateOf("")
    private var busy by mutableStateOf(false)
    private var refreshing by mutableStateOf(false)
    private var refreshGeneration = 0
    private val collector = WireCodec.Collector()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(244, 246, 245)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
        store = WatchStore(this)
        client = ChainClient(store)
        accounts = store.accounts()
        if (selectedChain !in accounts) selectedChain = accounts.keys.firstOrNull() ?: WalletChain.ETHEREUM
        pending = store.pending()
        screen = if (accounts.isEmpty()) Screen.IMPORT else Screen.HOME
        balance = accounts[selectedChain]?.let { store.balance(selectedChain, it.address) } ?: "--"
        endpoint = store.endpoint(selectedChain)
        if (accounts.containsKey(selectedChain)) refresh()
        setContent {
            AirWalletTheme {
                BackHandler(screen != Screen.HOME) { goBack() }
                WalletUi(
                    screen = screen, chain = selectedChain, account = accounts[selectedChain],
                    accounts = accounts, balance = balance, history = history, lastSync = lastSync, draft = draft,
                    pending = pending, signedRaw = signedRaw, endpoint = endpoint,
                    manualAddress = manualAddress, compiled = compiled, previous = previous,
                    scanProgress = scanProgress, error = error, notice = notice, busy = busy,
                    refreshing = refreshing,
                    onBack = ::goBack, onSelectChain = ::selectChain, onRefresh = ::refresh,
                    onImport = { screen = Screen.IMPORT }, onScanAccount = { startScan(Screen.SCAN_ACCOUNT) },
                    onScanResult = { startScan(Screen.SCAN_RESULT) }, onScanFrame = ::scan,
                    onManualAddress = { manualAddress = it }, onManualImport = ::importManual,
                    onCompose = { draft = TransferDraft(tokenDecimals = if (selectedChain.isEvm || selectedChain == WalletChain.SOLANA) "" else "6"); compiled = ""; previous = ""; screen = Screen.COMPOSE },
                    onDraft = { draft = it }, onCompiled = { compiled = it }, onPrevious = { previous = it },
                    onPrepare = ::prepare, onPrepareCompiled = ::prepareCompiled,
                    onShowPending = { screen = Screen.REQUEST },
                    onOpenVault = ::openVault,
                    onBroadcast = ::broadcast, onSettings = { endpoint = store.endpoint(selectedChain); screen = Screen.SETTINGS },
                    onEndpoint = { endpoint = it }, onSaveEndpoint = ::saveEndpoint,
                    onCancelPending = { pending = null; signedRaw = null; store.savePending(null); screen = Screen.HOME }
                )
            }
        }
        acceptDeepLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptDeepLink(intent)
    }

    override fun onDestroy() {
        store.close()
        super.onDestroy()
    }

    private fun acceptDeepLink(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "airwallet" || uri.host !in setOf("result", "result-json")) return
        try {
            require(uri.toString().length <= 200_000) { "签名结果过长" }
            val request = pending ?: error("没有待签交易")
            val payload = Base64.getUrlDecoder().decode(uri.lastPathSegment ?: "")
            val message = if (uri.host == "result-json") {
                WireVerifier.responseFromSignedJson(request, payload.toString(Charsets.UTF_8))
            } else WireCodec.decode(payload)
            val account = accounts[WalletChain.entries[request.chain]] ?: error("观察地址不存在")
            signedRaw = WireVerifier.verify(request, message, account)
            selectedChain = WalletChain.entries[request.chain]
            screen = Screen.VERIFIED
            error = ""
            refresh()
        } catch (exception: Exception) {
            error = exception.message ?: "签名结果无效"
            screen = Screen.REQUEST
        }
    }

    private fun openVault() {
        val request = pending ?: return
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(WireCodec.encode(request))
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("airvault://sign/$encoded"))
                .setPackage("com.micro.airvault"))
        } catch (exception: Exception) {
            error = "本机未找到 AirVault：${exception.message ?: "无法打开"}"
        }
    }

    private fun goBack() {
        error = ""
        screen = when (screen) {
            Screen.SCAN_ACCOUNT -> Screen.IMPORT
            Screen.SCAN_RESULT -> Screen.REQUEST
            Screen.COMPOSE, Screen.IMPORT, Screen.REQUEST, Screen.VERIFIED, Screen.SETTINGS -> Screen.HOME
            Screen.HOME -> Screen.HOME
        }
    }

    private fun selectChain(chain: WalletChain) {
        ++refreshGeneration
        refreshing = false
        selectedChain = chain
        balance = accounts[chain]?.let { store.balance(chain, it.address) } ?: "--"
        history = emptyList()
        lastSync = accounts[chain]?.let { store.lastSync(chain, it.address) } ?: 0
        endpoint = store.endpoint(chain)
        error = ""
        notice = ""
        if (accounts.containsKey(chain)) refresh()
    }

    private fun refresh() {
        val chain = selectedChain
        if (chain.isAddressOnly) return
        val account = accounts[chain] ?: return
        val generation = ++refreshGeneration
        refreshing = true
        error = ""
        lifecycleScope.launch {
            try {
                val cached = withContext(Dispatchers.IO) {
                    store.migrateLegacyHistory()
                    Triple(store.balance(chain, account.address), store.history(chain, account.address),
                        store.lastSync(chain, account.address))
                }
                if (generation != refreshGeneration || selectedChain != chain ||
                    accounts[chain]?.address != account.address) return@launch
                balance = cached.first
                history = cached.second
                lastSync = cached.third
                enqueueRefresh(chain, account.address, generation)
            } catch (exception: Exception) {
                if (generation == refreshGeneration) {
                    error = exception.message ?: "读取本地记录失败"
                    refreshing = false
                }
            }
        }
    }

    private fun enqueueRefresh(chain: WalletChain, address: String, generation: Int) {
        val request = OneTimeWorkRequestBuilder<ChainSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf("chain" to chain.name, "address" to address))
            .build()
        val manager = WorkManager.getInstance(this)
        val liveData = manager.getWorkInfoByIdLiveData(request.id)
        val observer = object : Observer<WorkInfo?> {
            override fun onChanged(info: WorkInfo?) {
                if (info == null) return
                if (!info.state.isFinished) {
                    if (generation == refreshGeneration && selectedChain == chain &&
                        accounts[chain]?.address == address) {
                        refreshing = info.state == WorkInfo.State.RUNNING
                    }
                    return
                }
                liveData.removeObserver(this)
                if (generation != refreshGeneration || selectedChain != chain ||
                    accounts[chain]?.address != address) return
                lifecycleScope.launch {
                    if (info.state == WorkInfo.State.SUCCEEDED) {
                        try {
                            val cached = withContext(Dispatchers.IO) {
                                Triple(store.balance(chain, address), store.history(chain, address),
                                    store.lastSync(chain, address))
                            }
                            if (generation == refreshGeneration && selectedChain == chain &&
                                accounts[chain]?.address == address) {
                                balance = cached.first
                                history = cached.second
                                lastSync = cached.third
                            }
                        } catch (exception: Exception) {
                            error = exception.message ?: "读取本地记录失败"
                        }
                    } else if (info.state == WorkInfo.State.FAILED) {
                        error = info.outputData.getString("error") ?: "链上更新失败"
                    }
                    if (generation == refreshGeneration) refreshing = false
                }
            }
        }
        liveData.observe(this, observer)
        manager.enqueueUniqueWork("chain_sync_${chain.name}_$address", ExistingWorkPolicy.REPLACE, request)
    }

    private fun startScan(next: Screen) {
        collector.clear()
        scanProgress = ""
        error = ""
        screen = next
    }

    private fun scan(text: String) {
        if (screen != Screen.SCAN_ACCOUNT && screen != Screen.SCAN_RESULT) return
        try {
            val payload = collector.offer(text)
            scanProgress = "已扫描 ${collector.received} / ${collector.total} 帧"
            if (payload == null) return
            val message = WireCodec.decode(payload)
            if (screen == Screen.SCAN_ACCOUNT) {
                val imported = WireVerifier.importAccounts(message)
                accounts = accounts + imported
                store.saveAccounts(accounts)
                screen = Screen.HOME
                selectChain(if (imported.size == 1) imported.keys.single() else WalletChain.ETHEREUM)
                notice = "观察账户已导入"
            } else {
                val request = pending ?: error("没有待签交易")
                val account = accounts[WalletChain.entries[request.chain]] ?: error("观察地址不存在")
                signedRaw = WireVerifier.verify(request, message, account)
                selectedChain = WalletChain.entries[request.chain]
                screen = Screen.VERIFIED
                error = ""
                refresh()
            }
        } catch (exception: Exception) {
            error = exception.message ?: "二维码无效"
        }
    }

    private fun importManual() {
        try {
            require(selectedChain.isEvm) { "此网络请从 AirVault 扫描包含公钥的观察账户二维码" }
            val address = ChainClient.checkedEvmAddress(manualAddress.trim())
            accounts = accounts + (selectedChain to WatchedAccount(address))
            store.saveAccounts(accounts)
            manualAddress = ""
            balance = store.balance(selectedChain, address)
            history = emptyList()
            lastSync = store.lastSync(selectedChain, address)
            notice = "观察地址已导入"
            screen = Screen.HOME
            refresh()
        } catch (exception: Exception) { error = exception.message ?: "地址无效" }
    }

    private fun prepare() {
        val chain = selectedChain
        val from = accounts[chain]?.address ?: return
        busy = true
        error = ""
        lifecycleScope.launch {
            try {
                val message = withContext(Dispatchers.IO) {
                    when {
                        chain.isEvm -> client.prepareEvm(chain, from, draft)
                        chain == WalletChain.BITCOIN -> client.prepareBitcoin(from, draft)
                        chain == WalletChain.SOLANA -> client.prepareSolana(from, draft)
                        chain == WalletChain.TRON -> client.prepareTron(from, draft)
                        else -> error("此网络暂不支持交易签名")
                    }
                }
                acceptPrepared(message)
            } catch (exception: Exception) { error = exception.message ?: "交易准备失败" }
            finally { busy = false }
        }
    }

    private fun prepareCompiled() {
        try {
            val previousTxs = previous.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
            acceptPrepared(client.importCompiled(selectedChain, compiled.trim(), previousTxs))
        } catch (exception: Exception) { error = exception.message ?: "交易导入失败" }
    }

    private fun acceptPrepared(message: WireMessage) {
        WireCodec.frames(WireCodec.encode(message))
        pending = message
        store.savePending(message)
        signedRaw = null
        screen = Screen.REQUEST
        error = ""
    }

    private fun broadcast() {
        val request = pending ?: return
        val raw = signedRaw ?: return
        val chain = WalletChain.entries[request.chain]
        busy = true
        error = ""
        lifecycleScope.launch {
            try {
                val hash = withContext(Dispatchers.IO) { client.broadcast(chain, raw) }
                notice = "已广播：$hash"
                store.savePending(null)
                pending = null
                signedRaw = null
                screen = Screen.HOME
            } catch (exception: Exception) { error = exception.message ?: "广播失败" }
            finally {
                busy = false
                if (pending == null) refresh()
            }
        }
    }

    private fun saveEndpoint() {
        try {
            store.saveEndpoint(selectedChain, endpoint.trim())
            notice = "节点地址已保存"
            screen = Screen.HOME
            refresh()
        } catch (exception: Exception) { error = exception.message ?: "节点地址无效" }
    }
}
