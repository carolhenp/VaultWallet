# AirWallet

AirWallet 是与 [AirVault](../AirVault/README.md) 配套的 Android 在线观察钱包。它只保存公开地址、公钥、余额缓存、交易记录和待签交易，不保存助记词或私钥。首次使用时，在 AirVault 点击钱包卡片，解锁后进入地址详情，点击 AirWallet 同步账号按钮，再用 AirWallet 扫描配对二维码。导入后默认选择 Ethereum，可在底部弹出的网络列表中切换 14 条链。也可手工导入单条 EVM 地址；非 EVM 地址须扫码导入公钥。

Zcash 当前仅同步主网透明 P2PKH 地址（`t1…`）和压缩公钥，导入时校验两者匹配；可查看公开地址，暂不支持屏蔽或统一地址、余额查询和交易签名。该链不会启动链上同步，也不显示发送、刷新或节点设置入口。

AirWallet 查询链上余额和最近交易，支持用户自定义 HTTPS 节点。EVM 余额、Nonce、Gas 来自 JSON-RPC；近期交易来自 Blockscout（可用链），Cronos 和 Sonic 暂只查询余额。Bitcoin 使用 Blockstream Esplora API；Solana 使用 JSON-RPC；TRON 使用 TronGrid REST API。公开节点可能限流、不可用或返回过时数据，交易签名前应以 AirVault 显示的内容为准。

历史记录按链与观察地址保存在本机 SQLite 中，已有 SharedPreferences 记录会在首次读取时迁移。打开应用或切换网络时先显示本地历史，随后由 WorkManager 在有网络时异步同步链上数据；同步失败会保留旧记录。新记录按交易哈希更新或追加，不会清空之前保存的交易。页面显示最近一次成功同步时间，数据库保留完整记录，列表最多展示最近 200 条。

## 交易流程

1. 在 AirWallet 选择网络与交易类型，填写收款地址、金额和费用。EVM 生成 EIP-1559 或 legacy 交易，ERC-20 转账从链上读取 `decimals()`；合约调用输入原始 calldata。Bitcoin 从 Esplora 读取 UTXO 与完整前序交易，生成 BIP-174 v0 PSBT；Solana 可生成 SOL 与 SPL/Token-2022 转账；TRON 可生成 TRX、TRC-20 与自定义智能合约调用。
2. AirWallet 显示待签交易的分帧二维码。AirVault 扫描、核对详情并经生物识别签名。两应用安装在同一设备时，也可点击“在本机 AirVault 中打开”。
3. AirVault 显示已签名交易的分帧二维码。AirWallet 扫描后核对请求 ID、链、交易字段及签名公钥，然后由用户点击“广播交易”。同设备可通过“在本机 AirWallet 中打开”回传。未广播时可返回首页继续查看待签二维码或重新扫描结果。

Bitcoin 支持 AirVault 导出的 Legacy（BIP-44 P2PKH，`1…`）、原生 SegWit（BIP-84 P2WPKH，`bc1q…`）和 Taproot（BIP-86 P2TR，`bc1p…`）首个地址。先在 AirVault 选择地址类型，再重新导出并扫码导入观察账户；AirWallet 根据地址识别类型、验证公钥，并按对应输入大小估算手续费。每次导入只观察当前选中的一种 Bitcoin 地址。

每个 Bitcoin 输入需要完整前序交易。PSBT 同时包含 non-witness UTXO 和 witness UTXO，Vault 会重新核对交易 ID、输出金额、脚本、全部收款输出和手续费。Legacy/SegWit 使用 ECDSA `SIGHASH_ALL`，Taproot 使用 key-path Schnorr `SIGHASH_DEFAULT`；AirWallet 验证签名及全部未签名交易字段，并允许 Legacy 的 scriptSig 导致交易 ID 改变。P2SH、多签、Taproot 脚本路径和 Ordinals/BRC-20 的自动构建与语义解析尚未实现；可导入符合当前签名器约束的原始交易或 PSBT。

Solana 的自定义合约指令需要外部协议库编译为单签名、无地址查找表的 legacy/v0 未签名交易后导入 Base64；AirVault 会展示每条指令的程序、账户和原始数据。TRON 自定义合约调用填写原始十六进制数据，AirWallet 会对照用户输入检查节点返回的交易。未知合约执行效果无法由二维码协议推断，Vault 只对常见代币方法做有限解码。

## 二维码协议 v1

两端使用相同的 `WireCodec.kt` 和 `PsbtV0.kt` 实现，分别保存在应用源码中，Bitcoin 地址及签名逻辑共享根目录 `AirProtocol` 中的 `BitcoinSupport.kt`。消息主体为二进制：`AV` 魔数、版本、链序号、消息类型、16 字节随机请求 ID、字段数，以及逐个长度前缀的字节字段。大整数使用无符号大端最短编码；地址和交易按原始字节传输。Bitcoin 导出的压缩公钥是所选路径的内部公钥，Taproot 输出公钥由它执行 BIP-86 tweak 得到，无需更改协议版本或链序号。交易类型覆盖 EVM legacy/1559、Bitcoin PSBT、Solana 编译交易、TRON protobuf，以及观察账户导出和签名结果。签名结果沿用请求 ID。

二维码帧使用 `AV1:` 前缀与无填充 Base64URL，包含帧版本、总片数、片序、请求 ID 前 8 字节、整包 SHA-256 前 8 字节和二进制片段。公开账户配对使用单张静态二维码，允许最多 2000 字节的二进制消息；交易请求和签名结果继续按最多 520 字节分片。新版仍可扫描旧版分帧公开账户二维码，旧版扫码器不支持超过 520 字节的单帧配对包，需要更新 AirWallet。扫描可乱序和重复，全部帧到齐后校验整包摘要，再按消息类型解析。单包上限 131072 字节，最多 255 帧。Base64URL 仅是二维码文本封装，核心消息与交易字段为二进制；这不是 AirGap 的互操作协议。

地址详情配对导出使用 `kind = 0`，`chain` 为当前链的固定序号，字段仅包含该链的地址与公钥。导入后仅更新该链并切换至该链，其他账户保留。Bitcoin 地址类型由地址及公钥校验确定，只导入当前配对类型。新版仍兼容 `chain = 255` 的旧版多链包，按原链顺序包含 26 或 28 个字段；Zcash 为链序号 13。旧版扫码器不支持单链配对包，需要更新 AirWallet。

## 构建

在 `AirWallet` 目录执行 `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`。需要 Android SDK 37、JDK 17 或更新版本。调试包位于 `app/build/outputs/apk/debug/app-debug.apk`。

本项目尚未经过独立安全审计。首次使用应先以小额交易在目标网络验证完整扫码、签名和广播路径。
