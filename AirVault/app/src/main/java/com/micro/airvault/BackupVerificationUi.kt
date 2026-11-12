package com.micro.airvault

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.micro.airvault.ui.theme.Accent
import com.micro.airvault.ui.theme.Muted
import com.micro.airvault.ui.theme.Paper
import com.micro.airvault.ui.theme.Surface
import com.micro.airvault.ui.theme.Warm

@Composable
internal fun BackupVerification(
    positions: List<Int>,
    answers: Map<Int, String>,
    incorrect: Set<Int>,
    passed: Boolean,
    keyboardLetters: String,
    active: Int,
    error: String,
    onSelectPosition: (Int) -> Unit,
    onSelectWord: (String) -> Unit
) {
    var prefix by remember(active) { mutableStateOf("") }
    Column(Modifier.testTag("backup-verification"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("${answers.size} / ${positions.size} 已填写", color = Muted,
            style = MaterialTheme.typography.labelLarge)
        positions.chunked(5).forEachIndexed { rowIndex, row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEachIndexed { columnIndex, position ->
                    val index = rowIndex * 5 + columnIndex
                    val selected = !passed && index == active
                    val color = when {
                        index in incorrect -> Warm
                        selected || index in answers -> Accent
                        else -> Color.White.copy(alpha = 0.16f)
                    }
                    Column(
                        Modifier.weight(1f).height(54.dp)
                            .border(1.dp, color,
                                RoundedCornerShape(4.dp))
                            .clickable(enabled = !passed, role = Role.Button) {
                                prefix = ""
                                onSelectPosition(index)
                            },
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("${position + 1}", color = if (index in incorrect) Warm else if (selected || passed) Accent else Paper,
                            style = MaterialTheme.typography.labelLarge)
                        Text(answers[index] ?: "未填写", color = if (index in incorrect) Warm else Paper,
                            fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        if (passed) {
            Text("10 个单词已验证", color = Accent, style = MaterialTheme.typography.labelLarge)
            return@Column
        }
        val position = positions.getOrNull(active) ?: return@Column
        Text("第 ${position + 1} 个单词", style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold)
        Text(error.ifEmpty { " " }, color = Warm, fontSize = 12.sp,
            modifier = Modifier.height(18.dp))
        MnemonicWordKeyboard(prefix, keyboardLetters, { prefix = it }, {
            prefix = ""
            onSelectWord(it)
        })
    }
}

@Composable
internal fun MnemonicWordKeyboard(
    prefix: String,
    keyboardLetters: String,
    onPrefixChange: (String) -> Unit,
    onSelectWord: (String) -> Unit,
    tagPrefix: String = "backup",
    enabled: Boolean = true
) {
    val enabledLetters = remember(prefix, enabled) {
        if (enabled) MnemonicBackupVerification.nextLetters(prefix) else emptySet()
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.fillMaxWidth().height(42.dp)
            .border(1.dp, Accent.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
            .padding(horizontal = 12.dp).clearAndSetSemantics { },
            contentAlignment = Alignment.CenterStart) {
            Text(prefix.ifEmpty { " " }, color = Paper, fontSize = 16.sp)
        }
        val suggestions = remember(prefix) { MnemonicBackupVerification.suggestions(prefix) }
        Column(Modifier.fillMaxWidth().height(92.dp).verticalScroll(rememberScrollState())
            .testTag("$tagPrefix-candidates"),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            suggestions.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { word ->
                        Box(Modifier.weight(1f).height(42.dp)
                            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(4.dp))
                            .testTag("$tagPrefix-candidate-$word")
                            .clickable(enabled = enabled, role = Role.Button) { onSelectWord(word) }, contentAlignment = Alignment.Center) {
                            Text(word, color = Paper, fontSize = 12.sp, maxLines = 1)
                        }
                    }
                    repeat(3 - row.size) { Box(Modifier.weight(1f)) }
                }
            }
        }
        Column(Modifier.testTag("$tagPrefix-keyboard"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LetterRow(keyboardLetters.take(10), enabledLetters, tagPrefix) { onPrefixChange((prefix + it).take(16)) }
            LetterRow(keyboardLetters.drop(10).take(9), enabledLetters, tagPrefix) { onPrefixChange((prefix + it).take(16)) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                keyboardLetters.drop(19).forEach { letter ->
                    LetterKey(letter, letter in enabledLetters, tagPrefix) { onPrefixChange((prefix + letter).take(16)) }
                }
                IconKey({ Icon(Icons.AutoMirrored.Filled.Backspace, "删除字母") }, enabled, "$tagPrefix-backspace") { onPrefixChange(prefix.dropLast(1)) }
                IconKey({ Icon(Icons.Default.Close, "清空输入") }, enabled, "$tagPrefix-clear") { onPrefixChange("") }
            }
        }
    }
}

@Composable
private fun LetterRow(letters: String, enabledLetters: Set<Char>, tagPrefix: String, onLetter: (Char) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        letters.forEach { letter -> LetterKey(letter, letter in enabledLetters, tagPrefix) { onLetter(letter) } }
    }
}

@Composable
private fun RowScope.LetterKey(letter: Char, enabled: Boolean, tagPrefix: String, onClick: () -> Unit) {
    Box(Modifier.weight(1f).height(42.dp).background(Surface, RoundedCornerShape(4.dp))
        .testTag("$tagPrefix-letter-$letter")
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Text(letter.uppercase(), color = Paper.copy(alpha = if (enabled) 1f else 0.32f), fontSize = 14.sp)
    }
}

@Composable
private fun RowScope.IconKey(icon: @Composable () -> Unit, enabled: Boolean, tag: String, onClick: () -> Unit) {
    Box(Modifier.weight(1f).height(42.dp).background(Surface, RoundedCornerShape(4.dp)).testTag(tag)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        icon()
    }
}
