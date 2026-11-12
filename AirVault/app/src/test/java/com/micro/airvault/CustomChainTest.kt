package com.micro.airvault

import com.micro.airprotocol.BitcoinAddressType
import org.junit.Assert.*
import org.junit.Test
import org.web3j.crypto.Bip32ECKeyPair
import org.web3j.crypto.Keys
import org.web3j.crypto.MnemonicUtils

class CustomChainTest {
    private val mnemonic = List(11) { "abandon" }.plus("about").joinToString(" ")
    private fun chain(format: CustomAddressFormat, path: String = format.examplePath) =
        CustomChain(format.name, "测试 ${format.label}", path, format, 1,
            if (format == CustomAddressFormat.EVM) 31337 else null)

    @Test fun evmChainIdIsRequiredPositiveDecimalAndCheckedForOverflow() {
        assertEquals(1L, CustomChainValidation.chainId("1", CustomAddressFormat.EVM))
        assertEquals(31337L, CustomChainValidation.chainId(" 031337 ", CustomAddressFormat.EVM))
        assertEquals(Long.MAX_VALUE, CustomChainValidation.chainId(Long.MAX_VALUE.toString(), CustomAddressFormat.EVM))
        listOf("", " ", "0", "0000", "-1", "+1", "1.0", "1e3", "0x89", "１２３", "9223372036854775808").forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { CustomChainValidation.chainId(invalid, CustomAddressFormat.EVM) }
        }
        CustomAddressFormat.entries.filter { it != CustomAddressFormat.EVM }.forEach { format ->
            assertNull(CustomChainValidation.chainId("", format))
            assertNull(CustomChainValidation.chainId("31337", format))
        }
    }

    @Test fun formatsMatchKnownBuiltInAddressesAndKeepSeparateIdentities() {
        val chains = CustomAddressFormat.entries.map { chain(it) }
        val catalog = WalletPublicCatalog.derive(mnemonic, chains)
        chains.forEach { definition ->
            val account = WalletAccount.custom(definition)
            val builtin = WalletAccount(account.chain, account.bitcoinType)
            assertNotEquals(builtin.id, account.id)
            assertEquals(definition.name, account.title)
            assertEquals(catalog.address(builtin).address, catalog.address(account).address)
            assertEquals(definition.derivationPath, catalog.address(account).path)
            for (branch in 0..1) {
                val addresses = catalog.addresses(account, branch)
                assertEquals(100, addresses.size)
                for (index in listOf(0, 1, 99)) {
                    assertEquals(catalog.address(builtin, branch, index).address, addresses[index].address)
                    assertEquals(account.path(branch, index), addresses[index].path)
                }
            }
            assertEquals(200, (catalog.addresses(account, 0) + catalog.addresses(account, 1)).map { it.address }.toSet().size)
            assertFalse(account.supportsSigning)
            PairingWallet.entries.forEach { target ->
                assertFalse(target.supports(account))
                assertThrows(IllegalArgumentException::class.java) { AccountPairing.frames(target, account, catalog) }
            }
        }
        assertEquals(WalletAccount.all.size + chains.size, WalletAccount.including(chains).size)
    }

    @Test fun arbitraryPathsIncludingHardenedTailsUseExactSelectedPathAndIndependentPrivateDerivation() {
        val chains = listOf("m/44'/60'/7'/3/122", "m/44'/60'/2'/0'/8'", "m/2147483647'/10/5/2147483647")
            .mapIndexed { i, path -> chain(CustomAddressFormat.EVM, path).copy(id = "chain-$i") }
        val catalog = WalletPublicCatalog.derive(mnemonic, chains)
        val seed = MnemonicUtils.generateSeed(mnemonic, "")
        try {
            val master = Bip32ECKeyPair.generateKeyPair(seed)
            fun expected(path: String): String {
                val indices = CustomChainValidation.components(path).map { it.i() }.toIntArray()
                return "0x" + Keys.getAddress(Bip32ECKeyPair.deriveKeyPair(master, indices))
            }
            chains.forEach { definition ->
                val account = WalletAccount.custom(definition)
                assertEquals(definition.derivationPath, catalog.address(account).path)
                assertEquals(expected(definition.derivationPath), catalog.address(account).address)
                for (branch in 0..1) for (index in listOf(0, 99)) {
                    assertEquals(expected(account.path(branch, index)), catalog.address(account, branch, index).address)
                }
                assertThrows(IllegalArgumentException::class.java) { catalog.address(account, 0, 100) }
                assertThrows(IllegalArgumentException::class.java) { catalog.address(account, 2, 0) }
            }
        } finally { SodiumMemory.wipe(seed) }
    }

    @Test fun invalidDefinitionsAreRejectedAndHardenedNotationIsNormalized() {
        assertEquals("测试链", CustomChainValidation.name("  测试链  "))
        listOf("", " ", "x".repeat(31), "ethereum", "Bitcoin Legacy").forEach { name ->
            assertThrows(IllegalArgumentException::class.java) { CustomChainValidation.name(name) }
        }
        listOf("", "m", "m/0", "M/44'/60'/0'/0/0", "m/44'//0", "m/-1/0", "m/2147483648/0", "m/1/0/", "m/1.1/0", "m/1/0'junk").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) { CustomChainValidation.path(path, CustomAddressFormat.EVM) }
        }
        assertEquals("m/44'/60'/0'/0/0", CustomChainValidation.path(" m/44h/60H/000'/0/0 ", CustomAddressFormat.EVM))
        assertThrows(IllegalArgumentException::class.java) {
            CustomChainValidation.path("m/44'/501'/0'/0", CustomAddressFormat.SOLANA)
        }
        assertEquals(BitcoinAddressType.TAPROOT, CustomAddressFormat.BITCOIN_TAPROOT.bitcoinType)
    }
}
