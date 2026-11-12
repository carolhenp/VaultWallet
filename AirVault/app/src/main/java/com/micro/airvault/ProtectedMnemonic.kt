package com.micro.airvault

import android.os.Build
import com.goterl.lazysodium.SodiumAndroid
import com.sun.jna.Pointer
import java.io.Closeable

internal object SodiumMemory {
    val library by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { SodiumAndroid() }

    fun wipe(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        // Android's local JVM unit tests use a stub SDK value and have no Android native libraries.
        if (Build.VERSION.SDK_INT == 0) {
            bytes.fill(0)
            return
        }
        try {
            library.sodium_memzero(bytes, bytes.size)
        } finally {
            bytes.fill(0)
        }
    }
}

/** Keeps the retained mnemonic in libsodium's guarded native allocation. */
internal class ProtectedMnemonic private constructor(
    private var pointer: Pointer?,
    private val length: Int
) : Closeable {
    @Synchronized
    fun <T> withBytes(block: (ByteArray) -> T): T {
        val current = checkNotNull(pointer) { "助记词已清除" }
        check(SodiumMemory.library.sodium_mprotect_readonly(current) == 0) { "无法读取受保护的助记词" }
        var bytes: ByteArray? = null
        var guarded = false
        try {
            bytes = current.getByteArray(0, length)
            check(SodiumMemory.library.sodium_mprotect_noaccess(current) == 0) { "无法保护助记词内存" }
            guarded = true
            return block(bytes)
        } finally {
            if (!guarded) close()
            bytes?.let(SodiumMemory::wipe)
        }
    }

    fun <T> withMnemonic(block: (String) -> T): T =
        withBytes { block(String(it, Charsets.UTF_8)) }

    @Synchronized
    override fun close() {
        pointer?.let { SodiumMemory.library.sodium_free(it) }
        pointer = null
    }

    companion object {
        fun fromString(mnemonic: String): ProtectedMnemonic {
            val bytes = mnemonic.toByteArray(Charsets.UTF_8)
            return try {
                fromBytes(bytes)
            } finally {
                SodiumMemory.wipe(bytes)
            }
        }

        fun fromBytes(bytes: ByteArray): ProtectedMnemonic {
            require(bytes.isNotEmpty() && bytes.size <= 1024) { "助记词长度无效" }
            val allocation = checkNotNull(SodiumMemory.library.sodium_malloc(bytes.size)) { "无法分配安全内存" }
            try {
                allocation.write(0, bytes, 0, bytes.size)
                check(SodiumMemory.library.sodium_mprotect_noaccess(allocation) == 0) { "无法保护助记词内存" }
                return ProtectedMnemonic(allocation, bytes.size)
            } catch (exception: Throwable) {
                SodiumMemory.library.sodium_free(allocation)
                throw exception
            }
        }
    }
}
