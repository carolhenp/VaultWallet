package com.micro.airvault

import android.hardware.biometrics.BiometricPrompt
import android.os.Bundle
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.os.CancellationSignal
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import com.micro.airvault.ui.theme.AirVaultTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.crypto.Cipher
import com.micro.airprotocol.WireCodec
import com.micro.airprotocol.WireMessage
import com.micro.airprotocol.BitcoinAddressType
import java.util.Base64

internal enum class Screen {
    SPLASH, CUSTOM_CHAIN, WELCOME, ENTROPY_INFO, ENTROPY_DRAW, BACKUP, BACKUP_VERIFY, IMPORT, WALLET_NAME,
    HOME, ACCOUNTS, ADDRESS, PAIRING, ADDRESS_BROWSER, REQUEST, SCAN, REVIEW, RESULT, RECOVERY, DELETE
}

class MainActivity : ComponentActivity() {
    private lateinit var vault: VaultStore
    private lateinit var publicAddressSession: PublicAddressSession
    private var screen by mutableStateOf(Screen.SPLASH)
    private var startupSavedState: Bundle? = null
    private var phrase by mutableStateOf("")
    private var protectedMnemonic: ProtectedMnemonic? = null
    private var phraseRevealEpoch by mutableIntStateOf(0)
    private var mnemonicImport by mutableStateOf(MnemonicImportState())
    private var importKeyboardLetters by mutableStateOf("")
    private var importInputEpoch by mutableIntStateOf(0)
    private var confirmed by mutableStateOf(false)
    private var backupPositions by mutableStateOf<List<Int>>(emptyList())
    private var backupAnswers by mutableStateOf<Map<Int, String>>(emptyMap())
    private var incorrectBackupPositions by mutableStateOf<Set<Int>>(emptySet())
    private var backupVerified by mutableStateOf(false)
    private var backupKeyboardLetters by mutableStateOf("")
    private var activeBackupPosition by mutableIntStateOf(0)
    private var wallets by mutableStateOf<List<StoredWallet>>(emptyList())
    private var currentWallet by mutableStateOf<StoredWallet?>(null)
    private var customChains by mutableStateOf<List<CustomChain>>(emptyList())
    private var walletName by mutableStateOf("")
    private var namingImportedWallet = false
    private var address: String
        get() = publicAddressSession.address
        set(value) { publicAddressSession.address = value }
    private var requestText by mutableStateOf("")
    private var request by mutableStateOf<SigningRequest?>(null)
    private var result by mutableStateOf("")
    private var resultFrames by mutableStateOf<List<String>>(emptyList())
    private var resultPayload: ByteArray? = null
    private var addressFrames: List<String>
        get() = publicAddressSession.addressFrames
        set(value) { publicAddressSession.addressFrames = value }
    private var scanProgress by mutableStateOf("")
    private var scanError by mutableStateOf("")
    private val scannedFrameTexts = mutableSetOf<String>()
    private var rejectedScanFrames: Set<String> = emptySet()
    private var scanReturnScreen = Screen.HOME
    private var reviewReturnScreen = Screen.REQUEST
    private val qrCollector = WireCodec.Collector()
    private var wireRequest: WireMessage? = null
    private var error by mutableStateOf("")
    private var busy by mutableStateOf(false)
    private var backPhotos by mutableIntStateOf(0)
    private var frontPhotos by mutableIntStateOf(0)
    private var audioProgress by mutableIntStateOf(0)
    private var gyroProgress by mutableIntStateOf(0)
    private var gyroSkipped by mutableStateOf(false)
    private var collectionReady by mutableStateOf(false)
    private var selectedChain: WalletChain
        get() = publicAddressSession.selectedChain
        set(value) { publicAddressSession.selectedChain = value }
    private var bitcoinAddressType: BitcoinAddressType
        get() = publicAddressSession.bitcoinAddressType
        set(value) { publicAddressSession.bitcoinAddressType = value }
    private var unlockedAddresses: Map<WalletChain, String>
        get() = publicAddressSession.unlockedAddresses
        set(value) { publicAddressSession.unlockedAddresses = value }
    private var entropyCollector: EntropyCollector? = null
    private var biometricPending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        vault = VaultStore(this)
        publicAddressSession = ViewModelProvider(this)[PublicAddressSession::class.java]
        startupSavedState = savedInstanceState
        initializeWallets()
        setContent {
            AirVaultTheme {
                BackHandler(screen != Screen.SPLASH && screen != Screen.HOME && (screen != Screen.WELCOME || wallets.isNotEmpty())) { goBack() }
                VaultUi(
                    screen = screen,
                    onRetryStartup = ::initializeWallets,
                    customChains = customChains,
                    onAddCustomChain = { if (!busy && !biometricPending) { error = ""; screen = Screen.CUSTOM_CHAIN } },
                    onSaveCustomChain = ::saveCustomChain,
                    phrase = phrase,
                    phraseRevealEpoch = phraseRevealEpoch,
                    mnemonicImport = mnemonicImport,
                    importKeyboardLetters = importKeyboardLetters,
                    importInputEpoch = importInputEpoch,
                    onImportWordCount = ::selectImportWordCount,
                    onImportPosition = ::selectImportPosition,
                    onImportWord = ::selectImportWord,
                    confirmed = confirmed,
                    onConfirm = { confirmed = it },
                    backupPositions = backupPositions,
                    backupAnswers = backupAnswers,
                    incorrectBackupPositions = incorrectBackupPositions,
                    backupVerified = backupVerified,
                    backupKeyboardLetters = backupKeyboardLetters,
                    activeBackupPosition = activeBackupPosition,
                    wallets = wallets,
                    currentWallet = currentWallet,
                    walletName = walletName,
                    onWalletName = { walletName = it },
                    onSelectWallet = ::selectWallet,
                    onAddWallet = { if (!busy && !biometricPending) screen = Screen.WELCOME },
                    accountRows = publicAddressSession.accountRows,
                    selectedAccount = publicAddressSession.selectedAccount,
                    onOpenAccount = ::openAccount,
                    onRenameWallet = ::renameWallet,
                    onCopyAddress = ::copyAddress,
                    onBrowseAddresses = { browseAddresses(0) },
                    browserBranch = publicAddressSession.browserBranch,
                    browserAddresses = publicAddressSession.browserAddresses,
                    onBrowserBranch = ::browseAddresses,
                    pairingWallet = publicAddressSession.pairingWallet,
                    pairingFrames = publicAddressSession.pairingFrames,
                    onPair = ::pairAccount,
                    address = address,
                    requestText = requestText,
                    onRequestText = { requestText = it },
                    request = request,
                    result = result,
                    resultFrames = resultFrames,
                    addressFrames = addressFrames,
                    scanProgress = scanProgress,
                    scanError = scanError,
                    onDismissScanError = { scanError = "" },
                    error = error,
                    busy = busy,
                    backPhotos = backPhotos,
                    frontPhotos = frontPhotos,
                    audioProgress = audioProgress,
                    gyroProgress = gyroProgress,
                    gyroSkipped = gyroSkipped,
                    collectionReady = collectionReady,
                    selectedChain = selectedChain,
                    bitcoinAddressType = bitcoinAddressType,
                    onSelectBitcoinAddressType = ::selectBitcoinAddressType,
                    onSelectChain = ::selectChain,
                    onBack = ::goBack,
                    onCreate = ::startCreation,
                    onBeginCollection = { if (screen == Screen.ENTROPY_INFO) screen = Screen.ENTROPY_DRAW },
                    onCameraPhoto = ::acceptCameraPhoto,
                    onAudioProgress = { audioProgress = it },
                    onAudioData = ::acceptAudioData,
                    onGyroscopeProgress = { gyroProgress = it },
                    onGyroscopeData = ::acceptGyroscopeData,
                    onDrawingConfirm = ::finishCreation,
                    onCollectionError = ::failCreation,
                    onImport = ::startImport,
                    onStartBackupVerification = ::startBackupVerification,
                    onSelectBackupPosition = ::selectBackupPosition,
                    onSelectBackupWord = ::selectBackupWord,
                    onSubmitBackupVerification = ::submitBackupVerification,
                    onSave = ::saveWallet,
                    onRestore = ::restoreWallet,
                    onUnlock = ::unlockAddress,
                    onScan = ::startScan,
                    onRequest = { screen = Screen.REQUEST },
                    onPayload = { if (screen == Screen.SCAN || screen == Screen.REQUEST) acceptPayload(it) },
                    onSign = ::signRequest,
                    onOpenWallet = ::openWallet,
                    onRecovery = ::revealRecovery,
                    onDelete = { screen = Screen.DELETE },
                    onConfirmDelete = ::deleteWallet
                )
            }
        }
    }

    private fun initializeWallets() {
        if (busy) return
        screen = Screen.SPLASH
        error = ""
        busy = true
        lifecycleScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            try {
                refreshWallets()
                if (publicAddressSession.walletId != currentWallet?.id) publicAddressSession.clear()
                publicAddressSession.walletId = currentWallet?.id
                bitcoinAddressType = currentWallet?.bitcoinAddressType ?: BitcoinAddressType.SEGWIT
                val restored = startupSavedState?.getString("walletBrowseScreen")?.let { value ->
                    Screen.entries.find { it.name == value }
                }
                screen = if (restored in setOf(Screen.ACCOUNTS, Screen.ADDRESS, Screen.PAIRING, Screen.ADDRESS_BROWSER) &&
                    currentWallet != null) {
                    if (publicAddressSession.catalog == null) Screen.ACCOUNTS else restored!!
                } else walletHomeScreen()
            } catch (exception: kotlinx.coroutines.CancellationException) {
                throw exception
            } catch (exception: Exception) {
                publicAddressSession.clear()
                error = exception.message ?: "无法打开钱包数据库"
            } finally {
                busy = false
            }
            if (screen == Screen.ADDRESS_BROWSER && publicAddressSession.browserAddresses.isEmpty()) {
                browseAddresses(publicAddressSession.browserBranch)
            }
            if (screen != Screen.SPLASH) acceptDeepLink(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptDeepLink(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("walletBrowseScreen", screen.name)
        super.onSaveInstanceState(outState)
    }

    private fun acceptDeepLink(intent: Intent?) {
        if (screen == Screen.SPLASH || busy || biometricPending) return
        val uri = intent?.data ?: return
        if (uri.scheme != "airvault" || uri.host != "sign") return
        try {
            val bytes = Base64.getUrlDecoder().decode(uri.lastPathSegment ?: "")
            val wire = WireCodec.decode(bytes)
            val (chain, json) = WireBridge.request(wire)
            wireRequest = wire
            selectedChain = chain
            address = unlockedAddresses[chain].orEmpty()
            acceptPayload(json, keepWire = true)
        } catch (exception: Exception) {
            error = exception.message ?: "深度链接请求无效"
        }
    }

    private fun openWallet() {
        val payload = resultPayload ?: result.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8) ?: return
        val host = if (resultPayload == null) "result-json" else "result"
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("airwallet://$host/$encoded")))
        } catch (exception: Exception) {
            error = "本机未找到 AirWallet：${exception.message ?: "无法打开"}"
        }
    }

    override fun onStop() {
        super.onStop()
        if (screen in setOf(Screen.BACKUP, Screen.BACKUP_VERIFY, Screen.RECOVERY)) {
            phrase = ""
            phraseRevealEpoch++
        }
        if (biometricPending) return
        clearTransientSecrets()
    }

    override fun onStart() {
        super.onStart()
        if (screen in setOf(Screen.BACKUP, Screen.RECOVERY) && phrase.isEmpty()) {
            phrase = protectedMnemonic?.withMnemonic { it }.orEmpty()
        }
    }

    override fun onDestroy() {
        protectedMnemonic?.close()
        protectedMnemonic = null
        vault.close()
        super.onDestroy()
    }

    private fun clearProtectedMnemonic() {
        phrase = ""
        protectedMnemonic?.close()
        protectedMnemonic = null
    }

    private fun clearTransientSecrets() {
        if (screen in setOf(Screen.ENTROPY_INFO, Screen.ENTROPY_DRAW)) {
            abandonCreation()
        }
        clearImportInput()
        result = ""
        resultFrames = emptyList()
        resultPayload = null
        if (screen == Screen.RESULT) screen = Screen.HOME
    }

    private fun startScan() {
        scanReturnScreen = if (screen in setOf(Screen.REQUEST, Screen.ADDRESS, Screen.ACCOUNTS)) screen else Screen.ACCOUNTS
        qrCollector.clear()
        scanProgress = ""
        scanError = ""
        scannedFrameTexts.clear()
        rejectedScanFrames = emptySet()
        screen = Screen.SCAN
    }

    private fun goBack() {
        if (busy || biometricPending) return
        error = ""
        when (screen) {
            Screen.CUSTOM_CHAIN -> screen = Screen.ACCOUNTS
            Screen.ACCOUNTS -> screen = Screen.HOME
            Screen.ADDRESS -> screen = Screen.ACCOUNTS
            Screen.PAIRING, Screen.ADDRESS_BROWSER -> screen = Screen.ADDRESS
            Screen.WELCOME -> screen = walletHomeScreen()
            Screen.ENTROPY_INFO, Screen.ENTROPY_DRAW -> abandonCreation()
            Screen.BACKUP -> {
                clearProtectedMnemonic()
                confirmed = false
                clearBackupVerification()
                screen = walletHomeScreen()
            }
            Screen.BACKUP_VERIFY -> {
                clearBackupVerification()
                phrase = protectedMnemonic?.withMnemonic { it }.orEmpty()
                screen = Screen.BACKUP
            }
            Screen.WALLET_NAME -> {
                walletName = ""
                if (namingImportedWallet) {
                    clearProtectedMnemonic()
                    startImport()
                } else {
                    backupVerified = false
                    screen = Screen.BACKUP_VERIFY
                }
            }
            Screen.IMPORT -> {
                clearImportInput()
                screen = walletHomeScreen()
            }
            Screen.RECOVERY -> {
                clearProtectedMnemonic()
                screen = Screen.ACCOUNTS
            }
            Screen.SCAN -> {
                scanError = ""
                qrCollector.clear()
                screen = scanReturnScreen
            }
            Screen.REVIEW -> {
                if (reviewReturnScreen == Screen.SCAN) {
                    request = null
                    requestText = ""
                    wireRequest = null
                }
                screen = reviewReturnScreen
            }
            Screen.DELETE, Screen.REQUEST, Screen.RESULT -> {
                result = ""
                resultFrames = emptyList()
                resultPayload = null
                screen = Screen.ACCOUNTS
            }
            else -> Unit
        }
    }

    private fun startCreation() {
        if (busy || biometricPending) return
        entropyCollector?.clear()
        try {
            entropyCollector = EntropyCollector.create()
            backPhotos = 0
            frontPhotos = 0
            audioProgress = 0
            gyroProgress = 0
            gyroSkipped = false
            collectionReady = false
            error = ""
            screen = Screen.ENTROPY_INFO
        } catch (exception: Exception) {
            failCreation(exception.message ?: "随机数生成失败")
        }
    }

    private fun acceptCameraPhoto(side: CameraSide, photo: ByteArray) {
        val collector = entropyCollector
        if (screen != Screen.ENTROPY_DRAW || collector == null) {
            photo.fill(0)
            return
        }
        try {
            collector.addCameraPhoto(side, photo)
            backPhotos = collector.backCount
            frontPhotos = collector.frontCount
            collectionReady = collector.ready
        } catch (exception: Exception) {
            failCreation(exception.message ?: "相机采集失败")
        }
    }

    private fun acceptGyroscopeData(data: ByteArray?) {
        val collector = entropyCollector
        if (screen != Screen.ENTROPY_DRAW || collector == null) {
            data?.fill(0)
            return
        }
        try {
            collector.addGyroscopeData(data)
            gyroSkipped = data == null
            gyroProgress = 100
            collectionReady = collector.ready
        } catch (exception: Exception) {
            failCreation(exception.message ?: "陀螺仪采集失败")
        }
    }

    private fun acceptAudioData(data: ByteArray) {
        val collector = entropyCollector
        if (screen != Screen.ENTROPY_DRAW || collector == null) {
            data.fill(0)
            return
        }
        try {
            collector.addMicrophoneData(data)
            audioProgress = 100
            collectionReady = collector.ready
        } catch (exception: Exception) {
            failCreation(exception.message ?: "麦克风采集失败")
        }
    }

    private fun finishCreation(drawing: List<DrawSample>) {
        val collector = entropyCollector ?: return
        if (screen != Screen.ENTROPY_DRAW) return
        try {
            val entropy = collector.finish(drawing)
            entropyCollector = null
            val generated = WalletEngine.generateMnemonic(entropy)
            clearProtectedMnemonic()
            protectedMnemonic = ProtectedMnemonic.fromString(generated)
            phrase = generated
            confirmed = false
            clearBackupVerification()
            screen = Screen.BACKUP
        } catch (exception: Exception) {
            failCreation(exception.message ?: "助记词生成失败")
        }
    }

    private fun failCreation(message: String) {
        abandonCreation()
        error = message
    }

    private fun abandonCreation() {
        entropyCollector?.clear()
        entropyCollector = null
        backPhotos = 0
        frontPhotos = 0
        audioProgress = 0
        gyroProgress = 0
        gyroSkipped = false
        collectionReady = false
        clearProtectedMnemonic()
        confirmed = false
        clearBackupVerification()
        screen = walletHomeScreen()
    }

    private fun startBackupVerification() {
        val protected = protectedMnemonic ?: return
        if (screen != Screen.BACKUP || !confirmed) return
        backupPositions = protected.withMnemonic {
            MnemonicBackupVerification.selectPositions(it.split(' ').size)
        }
        backupAnswers = emptyMap()
        incorrectBackupPositions = emptySet()
        backupVerified = false
        backupKeyboardLetters = MnemonicBackupVerification.shuffledLetters(backupKeyboardLetters)
        activeBackupPosition = 0
        error = ""
        WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.ime())
        phrase = ""
        screen = Screen.BACKUP_VERIFY
    }

    private fun selectBackupPosition(index: Int) {
        if (screen != Screen.BACKUP_VERIFY || backupVerified || index !in backupPositions.indices) return
        activeBackupPosition = index
        error = ""
    }

    private fun selectBackupWord(word: String) {
        if (screen != Screen.BACKUP_VERIFY || backupVerified || activeBackupPosition !in backupPositions.indices) return
        backupAnswers = backupAnswers + (activeBackupPosition to word)
        incorrectBackupPositions = incorrectBackupPositions - activeBackupPosition
        error = ""
        activeBackupPosition = backupPositions.indices.firstOrNull { it !in backupAnswers } ?: activeBackupPosition
    }

    private fun submitBackupVerification() {
        if (busy || biometricPending) return
        if (screen != Screen.BACKUP_VERIFY || !MnemonicBackupVerification.filled(backupPositions, backupAnswers)) return
        val protected = protectedMnemonic ?: return
        val incorrect = protected.withMnemonic {
            MnemonicBackupVerification.incorrectPositions(it, backupPositions, backupAnswers)
        }
        incorrectBackupPositions = incorrect
        backupVerified = incorrect.isEmpty()
        error = if (incorrect.isEmpty()) "" else "有 ${incorrect.size} 个单词不正确，请修改标记的序号后重新验证"
        if (incorrect.isNotEmpty()) {
            activeBackupPosition = incorrect.min()
            backupKeyboardLetters = MnemonicBackupVerification.shuffledLetters(backupKeyboardLetters)
        } else {
            busy = true
            lifecycleScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                try {
                    walletName = vault.defaultWalletName()
                    namingImportedWallet = false
                    phrase = ""
                    screen = Screen.WALLET_NAME
                } catch (exception: Exception) {
                    backupVerified = false
                    error = exception.message ?: "无法读取钱包列表"
                } finally {
                    busy = false
                }
            }
        }
    }

    private fun clearBackupVerification() {
        backupPositions = emptyList()
        backupAnswers = emptyMap()
        incorrectBackupPositions = emptySet()
        backupVerified = false
        activeBackupPosition = 0
    }

    private fun walletHomeScreen() = if (currentWallet == null) Screen.WELCOME else Screen.HOME

    private suspend fun refreshWallets() {
        val loaded = withContext(Dispatchers.IO) {
            Triple(vault.wallets(), vault.currentWallet(), vault.customChains())
        }
        wallets = loaded.first
        currentWallet = loaded.second
        customChains = loaded.third
    }

    private fun selectWallet(id: String) {
        if (screen != Screen.HOME || busy || biometricPending) return
        busy = true
        lifecycleScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            var opened = false
            try {
                if (currentWallet?.id != id) {
                    vault.selectWallet(id)
                    refreshWallets()
                    clearWalletSession()
                }
                screen = Screen.ACCOUNTS
                opened = true
            } catch (exception: Exception) {
                error = exception.message ?: "无法切换钱包"
            } finally {
                busy = false
            }
            if (opened && publicAddressSession.catalog == null) unlockAddress()
        }
    }

    private fun clearWalletSession() {
        clearProtectedMnemonic()
        publicAddressSession.clear()
        publicAddressSession.walletId = currentWallet?.id
        bitcoinAddressType = currentWallet?.bitcoinAddressType ?: BitcoinAddressType.SEGWIT
        request = null
        requestText = ""
        wireRequest = null
        result = ""
        resultFrames = emptyList()
        resultPayload = null
        qrCollector.clear()
        scanProgress = ""
        scanError = ""
        error = ""
    }

    private fun applyPublicData(data: WalletEngine.PublicData) {
        publicAddressSession.catalog = data.catalog
        publicAddressSession.accountRows = WalletAccount.including(customChains).associateWith { data.catalog.address(it) }
        publicAddressSession.bitcoinAccounts = data.bitcoinAccounts
        publicAddressSession.publicKeys = data.publicKeys
        unlockedAddresses = data.addresses
        address = data.addresses.getValue(selectedChain)
        addressFrames = WireBridge.addresses(data.addresses, data.publicKeys)
    }

    private fun renameWallet(name: String) {
        if (screen != Screen.ACCOUNTS || busy || biometricPending) return
        val walletId = currentWallet?.id ?: return
        busy = true
        lifecycleScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            try {
                vault.renameWallet(walletId, name)
                refreshWallets()
                error = ""
            } catch (exception: Exception) {
                error = exception.message ?: "无法修改钱包名称"
            } finally { busy = false }
        }
    }

    private fun saveCustomChain(name: String, path: String, format: CustomAddressFormat, chainId: String) {
        if (screen != Screen.CUSTOM_CHAIN || busy || biometricPending || currentWallet == null) return
        busy = true
        error = ""
        lifecycleScope.launch {
            var saved = false
            try {
                withContext(Dispatchers.IO) { vault.addCustomChain(name, path, format, chainId) }
                refreshWallets()
                clearWalletSession()
                screen = Screen.ACCOUNTS
                saved = true
            } catch (exception: kotlinx.coroutines.CancellationException) {
                throw exception
            } catch (exception: Exception) {
                error = exception.message ?: "无法保存自定义链"
            } finally { busy = false }
            if (saved) unlockAddress()
        }
    }

    private fun openAccount(account: WalletAccount) {
        if (busy || biometricPending || publicAddressSession.accountRows[account] == null) return
        selectChain(account.chain)
        busy = true
        lifecycleScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            try {
                if (account.customChain == null) {
                    account.bitcoinType?.takeIf { it != bitcoinAddressType }?.let { updateBitcoinAddressType(it) }
                }
                publicAddressSession.selectedAccount = account
                error = ""
                screen = Screen.ADDRESS
            } catch (exception: Exception) {
                error = exception.message ?: "无法打开钱包地址"
            } finally { busy = false }
        }
    }

    private fun copyAddress() {
        val selected = publicAddressSession.accountRows[publicAddressSession.selectedAccount] ?: return
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("钱包地址", selected.address))
    }

    private fun browseAddresses(branch: Int) {
        if (busy || biometricPending || branch !in 0..1) return
        val catalog = publicAddressSession.catalog ?: return
        val account = publicAddressSession.selectedAccount
        publicAddressSession.browserBranch = branch
        publicAddressSession.browserAddresses = emptyList()
        error = ""
        screen = Screen.ADDRESS_BROWSER
        busy = true
        lifecycleScope.launch {
            try {
                publicAddressSession.browserAddresses = withContext(Dispatchers.Default) { catalog.addresses(account, branch) }
            } catch (exception: Exception) {
                error = exception.message ?: "无法派生钱包地址"
            } finally { busy = false }
        }
    }

    private fun pairAccount(target: PairingWallet) {
        if (busy || biometricPending) return
        val catalog = publicAddressSession.catalog ?: return
        val account = publicAddressSession.selectedAccount
        busy = true
        lifecycleScope.launch {
            try {
                val frames = withContext(Dispatchers.Default) { AccountPairing.frames(target, account, catalog) }
                publicAddressSession.pairingWallet = target
                publicAddressSession.pairingFrames = frames
                error = ""
                screen = Screen.PAIRING
            } catch (exception: Exception) {
                error = exception.message ?: "无法生成配对二维码"
            } finally { busy = false }
        }
    }

    private fun selectBitcoinAddressType(type: BitcoinAddressType) {
        if (busy || biometricPending || type == bitcoinAddressType || currentWallet == null) return
        busy = true
        lifecycleScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            try {
                updateBitcoinAddressType(type)
            } catch (exception: Exception) {
                error = exception.message ?: "无法切换 Bitcoin 地址类型"
            } finally { busy = false }
        }
    }

    private suspend fun updateBitcoinAddressType(type: BitcoinAddressType) {
        val walletId = currentWallet?.id ?: return
        vault.setBitcoinAddressType(walletId, type)
        refreshWallets()
        bitcoinAddressType = type
        val account = publicAddressSession.bitcoinAccounts[type]
        unlockedAddresses = if (account == null) unlockedAddresses - WalletChain.BITCOIN
            else unlockedAddresses + (WalletChain.BITCOIN to account.address)
        publicAddressSession.publicKeys = if (account == null) publicAddressSession.publicKeys - WalletChain.BITCOIN
            else publicAddressSession.publicKeys + (WalletChain.BITCOIN to account.publicKey)
        address = unlockedAddresses[selectedChain].orEmpty()
        addressFrames = if (publicAddressSession.publicKeys.size == WalletChain.entries.size)
            WireBridge.addresses(unlockedAddresses, publicAddressSession.publicKeys) else emptyList()
        request = null
        requestText = ""
        wireRequest = null
        result = ""
        resultFrames = emptyList()
        resultPayload = null
        error = ""
    }

    private fun selectChain(chain: WalletChain) {
        if (busy || biometricPending || selectedChain == chain) return
        selectedChain = chain
        address = unlockedAddresses[chain].orEmpty()
        request = null
        requestText = ""
        result = ""
        resultFrames = emptyList()
        resultPayload = null
        error = ""
    }

    private fun authenticate(cipher: suspend () -> Cipher, title: String, onSuccess: suspend (Cipher) -> Unit) {
        if (busy || biometricPending) return
        biometricPending = true
        lifecycleScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            try {
                error = ""
                val prepared = cipher()
                BiometricPrompt.Builder(this@MainActivity)
                    .setTitle(title)
                    .setSubtitle("AirVault 设备密钥授权")
                    .setNegativeButton("取消", mainExecutor) { _, _ ->
                        biometricPending = false
                        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) clearTransientSecrets()
                    }
                    .build()
                    .authenticate(BiometricPrompt.CryptoObject(prepared), CancellationSignal(), mainExecutor,
                        object : BiometricPrompt.AuthenticationCallback() {
                            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                                biometricPending = false
                                busy = true
                                lifecycleScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                                    try {
                                        val authorized = result.cryptoObject?.cipher ?: error("生物识别未返回密钥授权")
                                        onSuccess(authorized)
                                    } catch (exception: Exception) {
                                        error = exception.message ?: "密钥操作失败"
                                    } finally {
                                        busy = false
                                    }
                                }
                            }

                            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                                biometricPending = false
                                if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) clearTransientSecrets()
                                if (errorCode != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED &&
                                    errorCode != BiometricPrompt.BIOMETRIC_ERROR_CANCELED) {
                                    error = errString.toString()
                                }
                            }
                        })
            } catch (exception: Exception) {
                biometricPending = false
                error = "无法授权设备密钥：${exception.message ?: "请先录入强生物识别"}"
            }
        }
    }

    private fun saveWallet() {
        val protected = protectedMnemonic ?: return
        if (screen != Screen.WALLET_NAME || busy || biometricPending) return
        if (!namingImportedWallet && (!confirmed || !backupVerified ||
            !MnemonicBackupVerification.filled(backupPositions, backupAnswers))) return
        val name = walletName.trim()
        if (name.isEmpty() || name.length > VaultStore.MAX_NAME_LENGTH) {
            error = "钱包名称须为 1–30 个字符"
            return
        }
        authenticate({ vault.encryptionCipher() }, "加密并保存钱包") { cipher ->
            if (screen != Screen.WALLET_NAME || protectedMnemonic !== protected) return@authenticate
            val publicData = withContext(Dispatchers.Default) {
                protected.withMnemonic { WalletEngine.publicData(it, BitcoinAddressType.SEGWIT, customChains) }
            }
            vault.save(name, protected, cipher)
            refreshWallets()
            clearWalletSession()
            applyPublicData(publicData)
            confirmed = false
            clearBackupVerification()
            walletName = ""
            namingImportedWallet = false
            screen = Screen.HOME
        }
    }

    private fun clearImportInput() {
        mnemonicImport = MnemonicImportState(wordCount = mnemonicImport.wordCount)
        importInputEpoch++
    }

    private fun startImport() {
        if (busy || biometricPending) return
        clearImportInput()
        importKeyboardLetters = MnemonicBackupVerification.shuffledLetters(importKeyboardLetters)
        error = ""
        WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.ime())
        screen = Screen.IMPORT
    }

    private fun selectImportWordCount(count: Int) {
        if (screen != Screen.IMPORT || busy || biometricPending) return
        mnemonicImport = mnemonicImport.resize(count)
        error = ""
    }

    private fun selectImportPosition(index: Int) {
        if (screen != Screen.IMPORT || busy || biometricPending) return
        mnemonicImport = mnemonicImport.select(index)
        error = ""
    }

    private fun selectImportWord(word: String) {
        if (screen != Screen.IMPORT || busy || biometricPending) return
        mnemonicImport = mnemonicImport.enter(word)
        error = ""
    }

    private fun restoreWallet() {
        if (screen != Screen.IMPORT || busy || biometricPending || !mnemonicImport.complete) return
        val normalized = try {
            WalletEngine.normalizeMnemonic(mnemonicImport.mnemonic())
        } catch (exception: Exception) {
            error = "助记词校验失败，请核对单词及顺序"
            return
        }
        val protected = try {
            ProtectedMnemonic.fromString(normalized)
        } catch (exception: Exception) {
            error = exception.message ?: "无法保护助记词内存"
            return
        }
        clearImportInput()
        error = ""
        clearProtectedMnemonic()
        protectedMnemonic = protected
        busy = true
        lifecycleScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            try {
                walletName = vault.defaultWalletName()
                clearBackupVerification()
                namingImportedWallet = true
                screen = Screen.WALLET_NAME
            } catch (exception: Exception) {
                clearProtectedMnemonic()
                error = exception.message ?: "无法读取钱包列表"
            } finally {
                busy = false
            }
        }
    }

    private fun unlockAddress() {
        if (busy || biometricPending) return
        val walletId = currentWallet?.id ?: return
        if (publicAddressSession.catalog != null && !unlockedAddresses[selectedChain].isNullOrEmpty()) {
            address = unlockedAddresses.getValue(selectedChain)
            return
        }
        authenticate({ vault.decryptionCipher(walletId) }, "查看钱包地址") { cipher ->
            if (currentWallet?.id != walletId) return@authenticate
            val protected = vault.decryptProtected(cipher, walletId)
            try {
                val publicData = withContext(Dispatchers.Default) {
                    protected.withMnemonic { WalletEngine.publicData(it, bitcoinAddressType, customChains) }
                }
                if (currentWallet?.id == walletId) {
                    applyPublicData(publicData)
                }
            } finally {
                protected.close()
            }
        }
    }

    private fun revealRecovery() {
        val walletId = currentWallet?.id ?: return
        authenticate({ vault.decryptionCipher(walletId) }, "查看恢复助记词") { cipher ->
            if (currentWallet?.id != walletId) return@authenticate
            clearProtectedMnemonic()
            protectedMnemonic = vault.decryptProtected(cipher, walletId)
            phrase = protectedMnemonic!!.withMnemonic { it }
            screen = Screen.RECOVERY
        }
    }

    private fun acceptPayload(payload: String, keepWire: Boolean = false) {
        if (busy || biometricPending) return
        val fromScanner = screen == Screen.SCAN && !keepWire
        if (fromScanner) {
            if (scanError.isNotEmpty() || payload in rejectedScanFrames) return
            scannedFrameTexts += payload
        }
        try {
            val text = payload.trim()
            var scannedWire: WireMessage? = null
            var scannedChain: WalletChain? = null
            val requestJson = if (WireCodec.isFrame(text)) {
                val bytes = qrCollector.offer(text)
                scanProgress = "已扫描 ${qrCollector.received} / ${qrCollector.total} 帧"
                if (bytes == null) return
                val wire = WireCodec.decode(bytes)
                val (chain, json) = WireBridge.request(wire)
                scannedWire = wire
                scannedChain = chain
                json
            } else text
            val parsed = WalletEngine.parseRequest(requestJson)
            val requestChain = scannedChain ?: if (fromScanner) when (parsed) {
                is SigningRequest.ChainTransaction -> parsed.chain
                is SigningRequest.Message -> WalletChain.entries.firstOrNull { it.chainId == parsed.chainId }
                is SigningRequest.Transaction -> WalletChain.entries.firstOrNull { it.chainId == parsed.chainId }
                is SigningRequest.Eip1559Transaction -> WalletChain.entries.firstOrNull { it.chainId == parsed.chainId }
            } ?: error("不支持的网络") else selectedChain
            WalletEngine.requireChain(parsed, requestChain)
            if (parsed is SigningRequest.ChainTransaction && parsed.chain == WalletChain.BITCOIN) {
                require(parsed.bitcoinAddressType == bitcoinAddressType) {
                    "交易使用 ${parsed.bitcoinAddressType?.label}，请先选择对应的 Bitcoin 地址类型"
                }
            }
            selectedChain = requestChain
            address = unlockedAddresses[requestChain].orEmpty()
            if (!keepWire) wireRequest = scannedWire
            request = parsed
            requestText = requestJson
            error = ""
            scanError = ""
            reviewReturnScreen = when {
                fromScanner -> Screen.SCAN
                keepWire -> Screen.HOME
                else -> Screen.REQUEST
            }
            if (fromScanner) rejectedScanFrames = scannedFrameTexts.toSet()
            scannedFrameTexts.clear()
            screen = Screen.REVIEW
        } catch (exception: Exception) {
            val message = exception.message ?: "请求格式错误或暂不支持"
            if (fromScanner) {
                rejectedScanFrames = scannedFrameTexts.toSet()
                scannedFrameTexts.clear()
                qrCollector.clear()
                scanProgress = ""
                scanError = message
            } else {
                screen = Screen.REQUEST
                error = message
            }
        }
    }

    private fun signRequest() {
        val walletId = currentWallet?.id ?: return
        val current = request ?: return
        val chain = selectedChain
        val bitcoinType = bitcoinAddressType
        authenticate({ vault.decryptionCipher(walletId) }, "批准交易并签名") { cipher ->
            if (currentWallet?.id != walletId) return@authenticate
            val protected = vault.decryptProtected(cipher, walletId)
            try {
                result = withContext(Dispatchers.Default) {
                    protected.withMnemonic { WalletEngine.sign(current, it, chain, bitcoinType) }
                }
                resultPayload = wireRequest?.let { WireCodec.encode(WireBridge.resultMessage(it, result)) }
                resultFrames = resultPayload?.let(WireCodec::frames) ?: emptyList()
                wireRequest = null
                request = null
                requestText = ""
                screen = Screen.RESULT
            } finally {
                protected.close()
            }
        }
    }

    private fun deleteWallet() {
        if (screen != Screen.DELETE) return
        val walletId = currentWallet?.id ?: return
        authenticate({ vault.decryptionCipher(walletId) }, "确认销毁本机钱包") { cipher ->
            if (currentWallet?.id != walletId) return@authenticate
            vault.decryptProtected(cipher, walletId).close()
            vault.delete(walletId)
            refreshWallets()
            clearWalletSession()
            clearImportInput()
            screen = walletHomeScreen()
        }
    }
}
