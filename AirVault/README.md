# AirVault

AirVault 是优先通过二维码交换数据的 Android 多链离线签名应用，也支持手动输入请求。它不会请求网络权限。首次创建会生成 24 词 BIP-39 助记词，也可恢复 12/15/18/21/24 词英文助记词。默认网络是 Ethereum，可在首页和请求页切换。

支持 Ethereum、BNB Smart Chain、Polygon PoS、Arbitrum One、OP Mainnet、Base、Avalanche C-Chain、Gnosis Chain、Cronos、Sonic，以及 Bitcoin、Solana、TRON。EVM 网络共用 `m/44'/60'/0'/0/0` 地址，并校验请求 `chainId`；Bitcoin 使用 `m/84'/0'/0'/0/0` 的主网 P2WPKH 地址，Solana 使用 `m/44'/501'/0'/0'`，TRON 使用 `m/44'/195'/0'/0/0`。当前每条链只派生第一个账户。

创建钱包时依次采集 `java.security.SecureRandom` 输出的 4096 字节、每秒一帧的三帧相机画面（三帧各归一化为 480×640 亮度字节）、与相机同步采集约三秒的陀螺仪事件（时间戳和三个轴的原始浮点值，仅在设备支持且采样成功时使用）、16 kHz 单声道 PCM 麦克风数据三秒，以及用户确认的手绘轨迹。各份输入分别计算 SHA3-256，按上述顺序拼接六个或七个 32 字节摘要，再计算 SHA3-256 得到 32 字节 BIP-39 熵。原始相机帧、陀螺仪事件和音频不写入磁盘；退出流程会丢弃本轮摘要。设备没有陀螺仪或采样不足时会跳过该来源，继续创建钱包。这里使用 Java 的密码学安全随机数生成器，不使用 `java.util.Random`。

相机和麦克风采集前分别显示提示，用户确认后才申请对应权限并开始采集。相机在预览就绪后的第 1、2、3 秒各取一帧，陀螺仪在同一时间窗口采样；麦克风读取三秒 PCM 数据。解锁地址后，应用只缓存各链派生出的公开地址，切换网络无需再次进行生物识别；查看助记词和每次签名仍要求生物识别。

助记词由 Android Keystore 中的 AES-GCM 密钥加密保存。创建、恢复、查看助记词和每次签名都需要设备强生物识别。优先尝试 StrongBox，设备没有 StrongBox 时要求普通 Android Keystore 密钥由安全硬件承载；不支持硬件密钥的设备不能创建钱包。请先在设备系统设置中录入强生物识别。关闭应用或切到后台时，助记词和签名结果页面会清空。备份和设备迁移已禁用；卸载应用前必须保留纸质助记词。

Android Keystore 只保护加密助记词的 AES 密钥。BIP-32 派生出的 secp256k1 私钥需在应用内存中短暂参与签名，不能宣称私钥本身不可从安全硬件导出；因此仍应使用专门的离线设备，并避免安装来路不明的软件。

助记词网格不暴露给 Android 辅助功能语义树，避免常规无障碍读取接口直接取得词语；这也意味着屏幕朗读无法朗读助记词。请在可信设备上使用，勿安装可读取屏幕内容的辅助功能服务。

新建和恢复助记词页面默认以 `***` 遮盖词语，点击网格上的“查看助记词”后才显示。备份确认在揭示前不可操作。应用使用 `FLAG_SECURE` 禁止系统截屏和最近任务预览，并在切到后台时清空助记词页面。

## 离线请求格式

联网端将 UTF-8 JSON 编成一个二维码；也可以在 AirVault 手动输入。EVM 金额、Gas 和 nonce 使用十进制字符串表示链上整数，也接受 `0x` 十六进制字符串。`data` 是十六进制字节串，未提供时为 `0x`。代币转账和合约调用使用目标链的原生交易数据；AirVault 展示原始调用数据，但不验证合约执行后的资产变化。

```json
{"type":"eth_personal_sign","chainId":1,"message":"Hello AirVault"}
```

```json
{"type":"eth_transaction_1559","chainId":1,"nonce":"0","to":"0x000000000000000000000000000000000000dEaD","value":"1000000000000000000","gasLimit":"21000","maxPriorityFeePerGas":"1000000000","maxFeePerGas":"2000000000","data":"0x"}
```

旧格式交易使用 `type: "eth_transaction"` 和 `gasPrice`，其余字段相同。旧消息请求若省略 `chainId`，按 Ethereum 主网处理。EVM 的 ERC-20 转账、授权和其他合约调用可放入 `data`，签署前应核对合约地址与调用数据。

Bitcoin 使用以下格式。`unsignedTransaction` 是完整的未签名原始交易十六进制；`previousTransactions` 按输入顺序提供各 UTXO 所在的完整原始交易，AirVault 会核对交易 ID、输出金额与 P2WPKH 脚本，计算全部输出和费用。只签本钱包首个 BIP-84 地址的输入，并固定使用 `SIGHASH_ALL`。交易输出可以承载 OP_RETURN 等代币协议数据，但当前不解析代币语义；不支持 Taproot、脚本路径、PSBT 和其他输入脚本类型。

```json
{"type":"btc_transaction","unsignedTransaction":"<原始交易十六进制>","previousTransactions":["<输入 1 的完整前序交易十六进制>"]}
```

Solana 使用单签名、无地址查找表的 legacy 或 v0 已编译交易，`transaction` 是 64 字节零签名加消息的 base64 序列化交易。签名账户须是第一个账户（手续费支付账户）且属于本钱包。原生币、SPL 代币和合约指令均可签署；页面逐条展示程序地址、账户和原始指令数据。多签交易和地址查找表交易会拒签。

```json
{"type":"sol_transaction","transaction":"<未签名交易的 base64>"}
```

TRON 使用 protobuf 序列化的未签名交易十六进制，支持 `TransferContract`（TRX）、`TransferAssetContract`（TRC10）和 `TriggerSmartContract`（TRC20 及其他合约调用）。交易须只含一个合约，发起地址须属于本钱包。页面显示合约地址、附带 TRX、原始调用数据和费用上限。

```json
{"type":"tron_transaction","transaction":"<未签名交易的 protobuf 十六进制>"}
```

签名后 AirVault 显示返回 JSON 的二维码：消息签名包含 `signature`，EVM、Bitcoin、Solana、TRON 交易签名包含可广播的 `signedTransaction`，结果均包含 `from`。Bitcoin 与 TRON 的签名交易是十六进制，Solana 是 base64。过长的结果无法显示在单张二维码中，此时页面会显示完整文本。AirVault 不广播交易，也不查询余额、UTXO、区块哈希或 nonce。

目前采用 AirGap Vault 式的离线扫码流程，但此 JSON **不是 AirGap 的可互操作协议**。Bitcoin 前序交易虽可验证内容与交易 ID，但离线设备无法验证 UTXO 是否仍未花费。Solana 与 TRON 的合约调用无法仅凭原始数据推断完整执行效果；签名前必须自行核对。此项目尚未经过独立安全审计，不应直接用于存储大额资产。

## 构建

在 `AirVault` 目录执行 `./gradlew :app:assembleDebug :app:testDebugUnitTest`。需要 Android SDK 37 和 JDK 17 或更新版本。
