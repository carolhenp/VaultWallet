package com.micro.airvault

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProtectedMnemonicTest {
    @Test
    fun nativeAllocationProtectsRetainedValueAndClearsReadCopies() {
        val source = "abandon ability able about above absent absorb abstract absurd abuse access accident"
        val input = source.toByteArray(Charsets.UTF_8)
        val protected = ProtectedMnemonic.fromBytes(input)
        try {
            var readCopy: ByteArray? = null
            protected.withBytes { readCopy = it }
            assertArrayEquals(ByteArray(input.size), readCopy)
            input.fill(0)
            assertEquals(source, protected.withMnemonic { it })
        } finally {
            protected.close()
            input.fill(0)
        }
        assertThrows(IllegalStateException::class.java) {
            protected.withMnemonic { }
        }
    }

    @Test
    fun sodiumWipesTemporaryKeyBytes() {
        val key = ByteArray(32) { (it + 1).toByte() }
        SodiumMemory.wipe(key)
        assertArrayEquals(ByteArray(32), key)
    }

    @Test
    fun generatingMnemonicUsesNativeEntropyWipe() {
        val entropy = ByteArray(32) { it.toByte() }
        assertEquals(24, WalletEngine.generateMnemonic(entropy).split(' ').size)
        assertArrayEquals(ByteArray(32), entropy)
    }
}
