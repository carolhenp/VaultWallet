# Trust Wallet 公开地址同步兼容性

核对日期：2026-11-13（Asia/Hong_Kong）。范围为 Android App 的「已有钱包 → 展开 → 只读钱包」地址导入。该入口只导入一个公开地址，不导入私钥、助记词、扩展公钥或整个派生账户，也不能通过 AirVault 签署 Trust Wallet 交易。

## 核对版本和方式

- 从 [Trust Wallet 官网下载入口](https://trustwallet.com/download/apk) 获取 APK，重定向到 `https://apk.trustwallet.com/builds/latest.apk`。
- Android 版本 `26.45.8`，versionCode `1773014970`。
- APK SHA-256：`c4f60cab7e078d373ddb42e469f4be12ae9c0c0c8c38416155319e4ee406589e`。
- 在独立 Android 12 模拟器中打开「只读钱包」，读取并滚动完整网络列表，共 75 项；对项目网络进行名称搜索复核。Optimism 使用列表名称 `OP Mainnet`，Gnosis 使用 `Gnosis Chain`。
- 使用公开 BIP-39 测试助记词派生的 Ethereum 地址 `0x9858effd232b4033e47d90003d41ec34ecaeda94`，确认通过地址校验并进入钱包命名页。其余链确认到网络选择入口；未逐项执行完整导入、摄像头扫码、余额和交易历史查询。

## 项目地址对应关系

| AirVault 链 / 地址类型 | Trust Wallet 只读导入列表名称 | 应用入口 |
| --- | --- | --- |
| Ethereum | Ethereum | 启用 |
| BNB Smart Chain | BNB Smart Chain | 启用 |
| Polygon PoS | Polygon | 启用 |
| Arbitrum One | Arbitrum | 启用 |
| OP Mainnet | OP Mainnet | 启用 |
| Base | Base | 启用 |
| Avalanche C-Chain | Avalanche C-Chain | 启用 |
| Gnosis Chain | Gnosis Chain | 启用 |
| Cronos | Cronos Chain | 启用 |
| Sonic | Sonic | 启用 |
| Solana | Solana | 启用 |
| TRON | Tron | 启用 |
| Bitcoin Legacy（`1…`） | 无 Bitcoin 选项 | 禁用 |
| Bitcoin SegWit（`bc1q…`） | 无 Bitcoin 选项 | 禁用 |
| Bitcoin Taproot（`bc1p…`） | 无 Bitcoin 选项 | 禁用 |
| Zcash 透明地址（`t1…`） | 无 Zcash 选项 | 禁用 |

Bitcoin 本身在 Trust Wallet 中有普通钱包支持，但不在本次版本的只读钱包导入列表中。因此不能将「支持 Bitcoin 网络」「能够向某地址转账」或 wallet-core 的地址编码支持，等同于此处的地址同步可用。此记录只决定 AirVault 中的 TrustWallet 只读同步入口，Bitcoin 地址二维码、派生浏览、签名和 AirWallet 同步继续可用。

本次版本的完整列表保存在 [trust-wallet-view-only-networks-26.45.8.json](trust-wallet-view-only-networks-26.45.8.json)。网络清单可能随 App 版本或服务端配置变化；iOS、浏览器扩展、旧版本、自定义网络及借助其他 EVM 网络间接添加的方法未纳入确认。

## 官方资料

- [How to Add a Watch Only Wallet Address in Trust Wallet](https://support.trustwallet.com/en/articles/717154-how-to-add-a-watch-only-wallet-address-in-trust-wallet-a-step-by-step-guide)：说明先选择网络，再导入地址。
- [How to Import a Watch Address](https://trustwallet.com/blog/product/how-to-import-a-watch-address)：说明可通过地址二维码导入；示例为 BNB Smart Chain。
- [Understanding the Limitations of View-Only Wallets](https://support.trustwallet.com/en/articles/793912-understanding-the-limitations-of-view-only-wallets-in-trust-wallet)：说明只读钱包的用途及限制。
- [2026-09-15 网络下线公告](https://trustwallet.com/blog/product/trust-wallet-to-sunset-support-for-multiple-networks-on-september-15-2026)：本项目上述 12 条链不在该次下线名单；Polygon PoS 与被下线的 Polygon zkEVM 为不同网络。

## AirVault 验证

配对能力使用明确的网络名单；Bitcoin 三种地址及 Zcash 均在生成二维码前被拒绝。单元测试覆盖 16 种地址的支持判定与 12 条可用网络的公开地址内容；设备流程测试验证 Bitcoin / Zcash 的禁用状态，以及 Ethereum 仍可进入 TrustWallet 二维码页。
