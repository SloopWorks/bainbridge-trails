package com.bainbridgetrails.ui.play.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.common_cancel
import com.bainbridgetrails.resources.play_riddle_answer
import com.bainbridgetrails.resources.play_riddle_list_search
import com.bainbridgetrails.ui.components.BtIcons
import com.bainbridgetrails.ui.components.MapIconButton
import com.bainbridgetrails.ui.theme.BtTheme
import org.jetbrains.compose.resources.stringResource

/**
 * RiddleAnswerPicker (ux.md §2.4, PF8-5): full screen over the map, "Answer" + search field + alphabetical 56dp rows
 * (trails / roads and landmarks). Tap = answer. The screen-reader default path.
 */
@Composable
fun RiddleAnswerPicker(options: List<RiddleOption>, onPick: (RiddlePick) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    var query by remember { mutableStateOf("") }
    val shown = remember(options, query) { RiddleLogic.filter(options, query) }
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                MapIconButton(BtIcons.ArrowBack, stringResource(Res.string.common_cancel), onClose)
                Text(stringResource(Res.string.play_riddle_answer), Modifier.semantics { heading() }.padding(start = 8.dp), style = MaterialTheme.typography.titleLarge)
            }
            OutlinedTextField(
                query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = BtTheme.spacing.screenH),
                placeholder = { Text(stringResource(Res.string.play_riddle_list_search)) }, singleLine = true,
            )
            LazyColumn(Modifier.fillMaxSize()) {
                items(shown, key = { it.name }) { o ->
                    Text(
                        o.name,
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { onPick(o.pick) }.padding(horizontal = BtTheme.spacing.screenH, vertical = 16.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}
