package com.micro.airvault

import org.web3j.crypto.MnemonicUtils
import java.security.SecureRandom

internal object MnemonicBackupVerification {
    const val REQUIRED_WORDS = 10
    private const val LETTERS = "abcdefghijklmnopqrstuvwxyz"

    fun shuffledLetters(previous: String = "", random: SecureRandom = SecureRandom()): String {
        val letters = LETTERS.toCharArray()
        for (index in letters.lastIndex downTo 1) {
            val chosen = random.nextInt(index + 1)
            val old = letters[index]
            letters[index] = letters[chosen]
            letters[chosen] = old
        }
        val shuffled = String(letters)
        return if (shuffled == previous) shuffled.drop(1) + shuffled.first() else shuffled
    }

    fun selectPositions(wordCount: Int, random: SecureRandom = SecureRandom()): List<Int> {
        require(wordCount >= REQUIRED_WORDS) { "助记词数量不足" }
        val positions = (0 until wordCount).toMutableList()
        for (index in positions.lastIndex downTo 1) {
            val chosen = random.nextInt(index + 1)
            val old = positions[index]
            positions[index] = positions[chosen]
            positions[chosen] = old
        }
        return positions.take(REQUIRED_WORDS).sorted()
    }

    fun matches(phrase: String, position: Int, selectedWord: String): Boolean =
        phrase.split(' ').getOrNull(position) == selectedWord

    fun filled(positions: List<Int>, answers: Map<Int, String>): Boolean =
        positions.size == REQUIRED_WORDS && answers.keys == positions.indices.toSet()

    fun incorrectPositions(phrase: String, positions: List<Int>, answers: Map<Int, String>): Set<Int> {
        require(filled(positions, answers)) { "请先填写全部 10 个单词" }
        return positions.indices.filterTo(mutableSetOf()) { index ->
            !matches(phrase, positions[index], answers.getValue(index))
        }
    }

    fun suggestions(prefix: String, limit: Int = 24): List<String> {
        if (prefix.isEmpty() || prefix.any { it !in 'a'..'z' }) return emptyList()
        return MnemonicUtils.getWords().asSequence().filter { it.startsWith(prefix) }.take(limit).toList()
    }

    fun nextLetters(prefix: String): Set<Char> {
        if (prefix.isEmpty()) return ('a'..'z').toSet()
        if (prefix.any { it !in 'a'..'z' }) return emptySet()
        return MnemonicUtils.getWords().asSequence()
            .filter { it.startsWith(prefix) && it.length > prefix.length }
            .map { it[prefix.length] }
            .toSet()
    }
}
