package com.micro.airwallet

import com.micro.airwallet.R

enum class WalletChain(val label: String, val symbol: String, val chainId: Long?, val decimals: Int,
                       val icon: Int, val endpoint: String, val explorer: String = "") {
    ETHEREUM("Ethereum", "ETH", 1, 18, R.drawable.chain_ethereum, "https://ethereum-rpc.publicnode.com", "https://eth.blockscout.com"),
    BITCOIN("Bitcoin", "BTC", null, 8, R.drawable.chain_bitcoin, "https://blockstream.info/api"),
    SOLANA("Solana", "SOL", null, 9, R.drawable.chain_solana, "https://api.mainnet-beta.solana.com"),
    TRON("TRON", "TRX", null, 6, R.drawable.chain_tron, "https://api.trongrid.io"),
    BNB_CHAIN("BNB Smart Chain", "BNB", 56, 18, R.drawable.chain_smartchain, "https://bsc-rpc.publicnode.com", "https://bsc.blockscout.com"),
    POLYGON("Polygon PoS", "POL", 137, 18, R.drawable.chain_polygon, "https://polygon-bor-rpc.publicnode.com", "https://polygon.blockscout.com"),
    ARBITRUM("Arbitrum One", "ETH", 42161, 18, R.drawable.chain_arbitrum, "https://arbitrum-one-rpc.publicnode.com", "https://arbitrum.blockscout.com"),
    OPTIMISM("OP Mainnet", "ETH", 10, 18, R.drawable.chain_optimism, "https://optimism-rpc.publicnode.com", "https://optimism.blockscout.com"),
    BASE("Base", "ETH", 8453, 18, R.drawable.chain_base, "https://base-rpc.publicnode.com", "https://base.blockscout.com"),
    AVALANCHE("Avalanche C-Chain", "AVAX", 43114, 18, R.drawable.chain_avalanchec, "https://avalanche-c-chain-rpc.publicnode.com", "https://avax.blockscout.com"),
    GNOSIS("Gnosis Chain", "xDAI", 100, 18, R.drawable.chain_xdai, "https://gnosis-rpc.publicnode.com", "https://gnosis.blockscout.com"),
    CRONOS("Cronos", "CRO", 25, 18, R.drawable.chain_cronos, "https://evm.cronos.org"),
    SONIC("Sonic", "S", 146, 18, R.drawable.chain_sonic, "https://rpc.soniclabs.com"),
    // Append chains to preserve the AV1 wire ordinals of existing networks.
    ZCASH("Zcash", "ZEC", null, 8, R.drawable.chain_zcash, "");

    val isEvm get() = chainId != null
    val isAddressOnly get() = this == ZCASH
}
