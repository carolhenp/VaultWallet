package com.micro.airvault

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.micro.airprotocol.BitcoinAddressType

internal class PublicAddressSession : ViewModel() {
    var walletId: String? = null
    var selectedChain by mutableStateOf(WalletChain.ETHEREUM)
    var bitcoinAddressType by mutableStateOf(BitcoinAddressType.SEGWIT)
    var bitcoinAccounts: Map<BitcoinAddressType, WalletEngine.BitcoinPublicAccount> = emptyMap()
    var publicKeys: Map<WalletChain, ByteArray> = emptyMap()
    var catalog by mutableStateOf<WalletPublicCatalog?>(null)
    var browserAddresses by mutableStateOf<List<DerivedAddress>>(emptyList())
    var accountRows by mutableStateOf<Map<WalletAccount, DerivedAddress>>(emptyMap())
    var selectedAccount by mutableStateOf(WalletAccount(WalletChain.ETHEREUM))
    var browserBranch by mutableStateOf(0)
    var pairingWallet by mutableStateOf(PairingWallet.AIRWALLET)
    var pairingFrames by mutableStateOf<List<String>>(emptyList())
    var unlockedAddresses: Map<WalletChain, String> = emptyMap()
    var address by mutableStateOf("")
    var addressFrames by mutableStateOf<List<String>>(emptyList())

    fun clear() {
        walletId = null
        selectedChain = WalletChain.ETHEREUM
        bitcoinAddressType = BitcoinAddressType.SEGWIT
        bitcoinAccounts = emptyMap()
        publicKeys = emptyMap()
        catalog = null
        browserAddresses = emptyList()
        accountRows = emptyMap()
        selectedAccount = WalletAccount(WalletChain.ETHEREUM)
        browserBranch = 0
        pairingFrames = emptyList()
        unlockedAddresses = emptyMap()
        address = ""
        addressFrames = emptyList()
    }
}
