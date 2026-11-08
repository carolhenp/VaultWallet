package com.micro.airvault

import android.hardware.biometrics.BiometricPrompt
import android.os.Bundle
import android.os.CancellationSignal
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import com.micro.airvault.ui.theme.AirVaultTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.crypto.Cipher

internal enum class Screen {
    WELCOME, ENTROPY_CAMERA_INFO, ENTROPY_CAMERA, ENTROPY_AUDIO_INFO, ENTROPY_AUDIO, ENTROPY_DRAW, BACKUP, IMPORT,
    HOME, REQUEST, SCAN, REVIEW, RESULT, RECOVERY, DELETE
}

class MainActivity : ComponentActivity() {
    private lateinit var vault: VaultStore
    private var screen by mutableStateOf(Screen.WELCOME)
    private var phrase by mutableStateOf("")
    private var inputPhrase by mutableStateOf("")
    private var confirmed by mutableStateOf(false)
    private var address by mutableStateOf("")
    private var requestText by mutableStateOf("")
    private var request by mutableStateOf<SigningRequest?>(null)
    private var result by mutableStateOf("")
    private var error by mutableStateOf("")
    private var busy by mutableStateOf(false)
    private var cameraFrames by mutableIntStateOf(0)
    private var selectedChain by mutableStateOf(WalletChain.ETHEREUM)
    private var unlockedAddresses: Map<WalletChain, String> = emptyMap()
    private var entropyCollector: EntropyCollector? = null
    private var biometricPending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        vault = VaultStore(this)
        screen = if (vault.exists) Screen.HOME else Screen.WELCOME
        setContent {
            AirVaultTheme {
                BackHandler(screen !in setOf(Screen.WELCOME, Screen.HOME)) { goBack() }
                VaultUi(
                    screen = screen,
                    phrase = phrase,
                    inputPhrase = inputPhrase,
                    onInputPhrase = { inputPhrase = it },
                    confirmed = confirmed,
                    onConfirm = { confirmed = it },
                    address = address,
                    requestText = requestText,
                    onRequestText = { requestText = it },
                    request = request,
                    result = result,
                    error = error,
                    busy = busy,
                    cameraFrames = cameraFrames,
                    selectedChain = selectedChain,
                    onSelectChain = ::selectChain,
                    onBack = ::goBack,
                    onCreate = ::startCreation,
                    onBeginCamera = { if (screen == Screen.ENTROPY_CAMERA_INFO) screen = Screen.ENTROPY_CAMERA },
                    onCameraFrame = ::acceptCameraFrame,
                    onGyroscopeDigest = ::acceptGyroscopeDigest,
                    onBeginAudio = { if (screen == Screen.ENTROPY_AUDIO_INFO) screen = Screen.ENTROPY_AUDIO },
                    onAudioDigest = ::acceptAudioDigest,
                    onDrawingConfirm = ::finishCreation,
                    onCollectionError = ::failCreation,
                    onImport = { screen = Screen.IMPORT },
                    onSave = ::saveWallet,
                    onRestore = ::restoreWallet,
                    onUnlock = ::unlockAddress,
                    onScan = { screen = Screen.SCAN },
                    onRequest = { screen = Screen.REQUEST },
                    onPayload = ::acceptPayload,
                    onSign = ::signRequest,
                    onRecovery = ::revealRecovery,
                    onDelete = { screen = Screen.DELETE },
                    onConfirmDelete = ::deleteWallet
                )
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (biometricPending) return
        clearTransientSecrets()
    }

    private fun clearTransientSecrets() {
        if (screen in setOf(Screen.ENTROPY_CAMERA_INFO, Screen.ENTROPY_CAMERA, Screen.ENTROPY_AUDIO_INFO, Screen.ENTROPY_AUDIO, Screen.ENTROPY_DRAW)) {
            abandonCreation()
        }
        if (screen == Screen.BACKUP || screen == Screen.RECOVERY) {
            phrase = ""
            confirmed = false
            screen = if (vault.exists) Screen.HOME else Screen.WELCOME
        }
        inputPhrase = ""
        result = ""
        if (screen == Screen.RESULT) screen = Screen.HOME
    }

    private fun goBack() {
        error = ""
        when (screen) {
            Screen.ENTROPY_CAMERA_INFO, Screen.ENTROPY_CAMERA, Screen.ENTROPY_AUDIO_INFO,
            Screen.ENTROPY_AUDIO, Screen.ENTROPY_DRAW -> abandonCreation()
            Screen.BACKUP -> {
                phrase = ""
                screen = Screen.WELCOME
            }
            Screen.IMPORT -> {
                inputPhrase = ""
                screen = Screen.WELCOME
            }
            Screen.RECOVERY -> {
                phrase = ""
                screen = Screen.HOME
            }
            Screen.SCAN -> screen = Screen.REQUEST
            Screen.REVIEW -> screen = Screen.REQUEST
            Screen.DELETE, Screen.REQUEST, Screen.RESULT -> {
                result = ""
                screen = Screen.HOME
            }
            else -> Unit
        }
    }

    private fun startCreation() {
        entropyCollector?.clear()
        try {
            entropyCollector = EntropyCollector.create()
            cameraFrames = 0
            error = ""
            screen = Screen.ENTROPY_CAMERA_INFO
        } catch (exception: Exception) {
            failCreation(exception.message ?: "随机数生成失败")
        }
    }

    private fun acceptCameraFrame(frame: ByteArray) {
        val collector = entropyCollector
        if (screen != Screen.ENTROPY_CAMERA || collector == null) {
            frame.fill(0)
            return
        }
        try {
            collector.addCameraFrame(frame)
            cameraFrames = collector.cameraFrames
        } catch (exception: Exception) {
            failCreation(exception.message ?: "相机采集失败")
        }
    }

    private fun acceptGyroscopeDigest(digest: ByteArray?) {
        val collector = entropyCollector
        if (screen != Screen.ENTROPY_CAMERA || collector == null) {
            digest?.fill(0)
            return
        }
        try {
            collector.addGyroscopeDigest(digest)
            screen = Screen.ENTROPY_AUDIO_INFO
        } catch (exception: Exception) {
            failCreation(exception.message ?: "陀螺仪采集失败")
        }
    }

    private fun acceptAudioDigest(digest: ByteArray) {
        val collector = entropyCollector
        if (screen != Screen.ENTROPY_AUDIO || collector == null) {
            digest.fill(0)
            return
        }
        try {
            collector.addMicrophoneDigest(digest)
            screen = Screen.ENTROPY_DRAW
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
            phrase = WalletEngine.generateMnemonic(entropy)
            confirmed = false
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
        cameraFrames = 0
        phrase = ""
        confirmed = false
        screen = Screen.WELCOME
    }

    private fun selectChain(chain: WalletChain) {
        if (selectedChain == chain) return
        selectedChain = chain
        address = unlockedAddresses[chain].orEmpty()
        request = null
        requestText = ""
        result = ""
        error = ""
    }

    private fun authenticate(cipher: Cipher, title: String, onSuccess: (Cipher) -> Unit) {
        try {
            error = ""
            biometricPending = true
            BiometricPrompt.Builder(this)
                .setTitle(title)
                .setSubtitle("AirVault 设备密钥授权")
                .setNegativeButton("取消", mainExecutor) { _, _ ->
                    biometricPending = false
                    if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) clearTransientSecrets()
                }
                .build()
                .authenticate(BiometricPrompt.CryptoObject(cipher), CancellationSignal(), mainExecutor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            biometricPending = false
                            val authorized = result.cryptoObject?.cipher ?: return
                            try {
                                onSuccess(authorized)
                            } catch (exception: Exception) {
                                error = exception.message ?: "密钥操作失败"
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
            error = "请先在设备设置中录入强生物识别：${exception.message ?: "不可用"}"
        }
    }

    private fun saveWallet() {
        if (!confirmed || phrase.isEmpty()) return
        val mnemonic = phrase
        error = ""
        try {
            authenticate(vault.encryptionCipher(), "加密并保存钱包") { cipher ->
                if (screen != Screen.BACKUP) return@authenticate
                val addresses = WalletEngine.addresses(mnemonic)
                vault.save(mnemonic, cipher)
                unlockedAddresses = addresses
                address = addresses.getValue(selectedChain)
                phrase = ""
                confirmed = false
                screen = Screen.HOME
            }
        } catch (exception: Exception) {
            error = exception.message ?: "无法创建设备密钥"
        }
    }

    private fun restoreWallet() {
        val normalized = try {
            WalletEngine.normalizeMnemonic(inputPhrase)
        } catch (exception: Exception) {
            error = exception.message ?: "助记词无效"
            return
        }
        error = ""
        try {
            authenticate(vault.encryptionCipher(), "恢复并加密钱包") { cipher ->
                val addresses = WalletEngine.addresses(normalized)
                vault.save(normalized, cipher)
                unlockedAddresses = addresses
                address = addresses.getValue(selectedChain)
                inputPhrase = ""
                screen = Screen.HOME
            }
        } catch (exception: Exception) {
            error = exception.message ?: "无法创建设备密钥"
        }
    }

    private fun unlockAddress() {
        if (unlockedAddresses.isNotEmpty()) {
            address = unlockedAddresses[selectedChain].orEmpty()
            return
        }
        try {
            authenticate(vault.decryptionCipher(), "查看钱包地址") { cipher ->
                val mnemonic = vault.decrypt(cipher)
                busy = true
                lifecycleScope.launch {
                    try {
                        val addresses = withContext(Dispatchers.Default) { WalletEngine.addresses(mnemonic) }
                        if (vault.exists) {
                            unlockedAddresses = addresses
                            address = addresses.getValue(selectedChain)
                        }
                    } catch (exception: Exception) {
                        error = exception.message ?: "无法派生地址"
                    } finally {
                        busy = false
                    }
                }
            }
        } catch (exception: Exception) {
            error = exception.message ?: "无法解锁钱包"
        }
    }

    private fun revealRecovery() {
        try {
            authenticate(vault.decryptionCipher(), "查看恢复助记词") { cipher ->
                phrase = vault.decrypt(cipher)
                screen = Screen.RECOVERY
            }
        } catch (exception: Exception) {
            error = exception.message ?: "无法解锁钱包"
        }
    }

    private fun acceptPayload(payload: String) {
        try {
            val parsed = WalletEngine.parseRequest(payload.trim())
            WalletEngine.requireChain(parsed, selectedChain)
            request = parsed
            requestText = payload.trim()
            error = ""
            screen = Screen.REVIEW
        } catch (exception: Exception) {
            screen = Screen.REQUEST
            error = exception.message ?: "请求格式无效"
        }
    }

    private fun signRequest() {
        val current = request ?: return
        val chain = selectedChain
        try {
            authenticate(vault.decryptionCipher(), "确认离线签名") { cipher ->
                val mnemonic = vault.decrypt(cipher)
                busy = true
                lifecycleScope.launch {
                    try {
                        result = withContext(Dispatchers.Default) { WalletEngine.sign(current, mnemonic, chain) }
                        request = null
                        requestText = ""
                        screen = Screen.RESULT
                    } catch (exception: Exception) {
                        error = exception.message ?: "签名失败"
                    } finally {
                        busy = false
                    }
                }
            }
        } catch (exception: Exception) {
            error = exception.message ?: "无法解锁钱包"
        }
    }

    private fun deleteWallet() {
        try {
            authenticate(vault.decryptionCipher(), "确认销毁本机钱包") { cipher ->
                vault.delete()
                phrase = ""
                inputPhrase = ""
                result = ""
                address = ""
                unlockedAddresses = emptyMap()
                request = null
                screen = Screen.WELCOME
            }
        } catch (exception: Exception) {
            error = exception.message ?: "删除失败"
        }
    }
}
