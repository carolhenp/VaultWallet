package com.micro.airvault

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.micro.airvault.ui.theme.Accent
import com.micro.airvault.ui.theme.Muted
import com.micro.airvault.ui.theme.Paper
import com.micro.airvault.ui.theme.Warm

@Composable
internal fun MnemonicImportContent(
    state: MnemonicImportState,
    keyboardLetters: String,
    inputEpoch: Int,
    busy: Boolean,
    error: String,
    onWordCount: (Int) -> Unit,
    onPosition: (Int) -> Unit,
    onWord: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    // Keep unfinished input in memory only; clear it with the Activity's import session.
    var prefix by remember(state.active, inputEpoch) { mutableStateOf("") }
    val gridState = rememberLazyListState()
    LaunchedEffect(state.active, state.wordCount, inputEpoch) { gridState.animateScrollToItem(state.active / 3) }
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 12.dp).testTag("mnemonic-import"),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("import-word-count")) {
                Text("助记词数量", modifier = Modifier.weight(1f))
                Text("${state.wordCount} 词")
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Default.KeyboardArrowDown, null)
            }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                MnemonicImportState.WORD_COUNTS.forEach { count ->
                    DropdownMenuItem(text = { Text("$count 词") }, modifier = Modifier.testTag("import-count-$count"),
                        onClick = { expanded = false; prefix = ""; onWordCount(count) })
                }
            }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("import-word-grid"), state = gridState,
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(state.words.indices.toList().chunked(3), key = { it.first() }) { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { index ->
                        val word = state.words[index]
                        Row(Modifier.weight(1f).height(38.dp)
                            .border(1.dp, if (state.active == index) Accent else Color.White.copy(alpha = 0.16f), RoundedCornerShape(6.dp))
                            .clickable(enabled = !busy, role = Role.Button) { prefix = ""; onPosition(index) }
                            .semantics {
                                contentDescription = "第 ${index + 1} 个单词"
                                stateDescription = if (word.isEmpty()) "未填写" else "已填写"
                            }.testTag("import-word-$index"), verticalAlignment = Alignment.CenterVertically) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 5.dp).clearAndSetSemantics { },
                                verticalAlignment = Alignment.CenterVertically) {
                                Text("${index + 1}.", color = Accent, modifier = Modifier.width(22.dp), fontSize = 11.sp)
                                Text(word.ifEmpty { "未填写" }, color = if (word.isEmpty()) Muted else Paper,
                                    fontWeight = FontWeight.Medium, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("第 ${state.active + 1} 个单词", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text("${state.filled} / ${state.wordCount} 已填写", color = Muted, style = MaterialTheme.typography.labelMedium)
        }
        if (error.isNotEmpty()) Text(error, color = Warm, style = MaterialTheme.typography.bodySmall)
        MnemonicWordKeyboard(prefix, keyboardLetters, { prefix = it }, {
            prefix = ""
            onWord(it)
        }, tagPrefix = "import", enabled = !busy)
    }
}
