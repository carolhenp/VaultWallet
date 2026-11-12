package com.micro.airvault

enum class WalletChain(
    val label: String,
    val symbol: String,
    val chainId: Long?,
    val derivationPath: String,
    val decimals: Int
) {
    ETHEREUM("Ethereum", "ETH", 1, "m/44'/60'/0'/0/0", 18),
    BITCOIN("Bitcoin", "BTC", null, "m/84'/0'/0'/0/0", 8),
    SOLANA("Solana", "SOL", null, "m/44'/501'/0'/0'", 9),
    TRON("TRON", "TRX", null, "m/44'/195'/0'/0/0", 6),
    BNB_CHAIN("BNB Smart Chain", "BNB", 56, "m/44'/60'/0'/0/0", 18),
    POLYGON("Polygon PoS", "POL", 137, "m/44'/60'/0'/0/0", 18),
    ARBITRUM("Arbitrum One", "ETH", 42161, "m/44'/60'/0'/0/0", 18),
    OPTIMISM("OP Mainnet", "ETH", 10, "m/44'/60'/0'/0/0", 18),
    BASE("Base", "ETH", 8453, "m/44'/60'/0'/0/0", 18),
    AVALANCHE("Avalanche C-Chain", "AVAX", 43114, "m/44'/60'/0'/0/0", 18),
    GNOSIS("Gnosis Chain", "xDAI", 100, "m/44'/60'/0'/0/0", 18),
    CRONOS("Cronos", "CRO", 25, "m/44'/60'/0'/0/0", 18),
    SONIC("Sonic", "S", 146, "m/44'/60'/0'/0/0", 18),
    // Append chains to preserve the AV1 wire ordinals of existing networks.
    ZCASH("Zcash", "ZEC", null, "m/44'/133'/0'/0/0", 8);

    val isEvm: Boolean get() = chainId != null
    val supportsSigning: Boolean get() = this != ZCASH
}
