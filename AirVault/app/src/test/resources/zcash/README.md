# Zcash transparent address vectors

`bip44.json` was generated independently of the Android implementation with
`@scure/bip32` 1.7.0, `@scure/bip39` 1.6.0 and `@noble/hashes` 1.8.0.
The mnemonic is the public BIP-39 test mnemonic, with an empty passphrase.

For branches 0 and 1 and indices 0, 1 and 99:

1. Derive the compressed public key at `m/44'/133'/0'/branch/index`.
2. Compute `RIPEMD160(SHA256(publicKey))`.
3. Encode `0x1c || 0xb8 || hash160` using `@scure/base`'s `base58check(sha256)`.

The separate key/address encoding vectors in `ZcashWalletTest` come from
[Trust Wallet Core's Zcash address tests](https://github.com/trustwallet/wallet-core/blob/master/tests/chains/Zcash/AddressTests.cpp).
The mainnet P2PKH prefix is also specified in
[Zcash chain parameters](https://github.com/zcash/zcash/blob/master/src/chainparams.cpp).
