package com.micro.airvault

import org.web3j.crypto.MnemonicUtils

internal data class MnemonicImportState(
    val wordCount: Int = 12,
    val words: List<String> = List(wordCount) { "" },
    val active: Int = 0
) {
    init {
        require(wordCount in WORD_COUNTS && words.size == wordCount && active in words.indices)
    }

    val complete: Boolean get() = words.all { it.isNotEmpty() }
    val filled: Int get() = words.count { it.isNotEmpty() }

    fun resize(count: Int): MnemonicImportState {
        require(count in WORD_COUNTS)
        val resized = List(count) { words.getOrNull(it).orEmpty() }
        return copy(wordCount = count, words = resized,
            active = resized.indexOfFirst { it.isEmpty() }.takeIf { it >= 0 } ?: active.coerceAtMost(count - 1))
    }

    fun select(index: Int): MnemonicImportState {
        require(index in words.indices)
        return copy(active = index)
    }

    fun enter(word: String): MnemonicImportState {
        require(word in MnemonicUtils.getWords()) { "请选择 BIP-39 英文单词" }
        val updated = words.toMutableList().also { it[active] = word }
        val next = (1..wordCount).asSequence().map { (active + it) % wordCount }
            .firstOrNull { updated[it].isEmpty() } ?: active
        return copy(words = updated, active = next)
    }

    fun mnemonic(): String {
        require(complete) { "请先填写全部 $wordCount 个单词" }
        return words.joinToString(" ")
    }

    companion object {
        val WORD_COUNTS = listOf(12, 15, 18, 21, 24)
    }
}
