package com.basdhaweio.marquee

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch

@Composable
fun AssistantListScreen(nav: NavHostController) {
    val deps = LocalDeps.current
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize()) {
        Loadable(load = { deps.api.conversations() }) { list, reload ->
            LazyColumn(contentPadding = ListPadding) {
                item {
                    Column(Modifier.padding(16.dp)) {
                        Text("Claude", style = MaterialTheme.typography.headlineSmall)
                        Muted(if (list.enabled) "Ask about or change anything on the server. Changes wait for your approval."
                              else "Not set up yet: add ANTHROPIC_API_KEY to Marquee's .env on jerry.", 3)
                    }
                }
                items(list.items, key = { it.id }) { c ->
                    ListItem(headlineContent = { Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Muted(if (c.pending) "Waiting for your approval" else agoEpoch(c.updated?.toLong()), 1) },
                        trailingContent = { IconButton(onClick = { scope.launch { runCatching { deps.api.deleteConversation(c.id) }; reload() } }) {
                            Icon(Icons.Filled.Delete, "delete") } },
                        modifier = Modifier.clickable { nav.go("chat/${c.id}") })
                }
            }
        }
        ExtendedFloatingActionButton(onClick = { nav.go("chat/new") }, icon = { Icon(Icons.Filled.Add, null) },
            text = { Text("New chat") }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp))
    }
}

@Composable
fun ChatScreen(initialId: String?) {
    val deps = LocalDeps.current
    val scope = rememberCoroutineScope()
    var id by rememberSaveable { mutableStateOf(initialId) }
    var convo by remember { mutableStateOf<Conversation?>(null) }
    var input by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    LaunchedEffect(initialId) {
        if (initialId != null) runCatching { deps.api.conversation(initialId) }.onSuccess { convo = it }.onFailure { error = it.message }
    }
    LaunchedEffect(convo?.display?.size, busy) {
        val n = (convo?.display?.size ?: 0) + (convo?.pending?.size ?: 0) + if (busy) 1 else 0
        if (n > 0) listState.animateScrollToItem(n - 1)
    }
    fun run(block: suspend () -> Conversation) {
        busy = true; error = null
        scope.launch {
            runCatching { block() }.onSuccess { convo = it; id = it.id }.onFailure { error = it.message }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(Modifier.weight(1f), state = listState, contentPadding = ListPadding) {
            val msgs = convo?.display.orEmpty()
            if (msgs.isEmpty() && !busy) item {
                Column(Modifier.padding(24.dp)) {
                    Text("What do you need?", style = MaterialTheme.typography.titleMedium)
                    Muted("e.g. \"What's stuck in the queue?\", \"Add the Dune films in 4K\", \"Which Iron Chef episodes are missing?\"", 4)
                }
            }
            items(msgs) { m -> Bubble(m) }
            convo?.pending?.let { pending ->
                if (pending.isNotEmpty()) item {
                    Column(Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(12.dp))
                        .background(Gold.copy(alpha = 0.12f)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Claude wants to:", color = Gold, style = MaterialTheme.typography.titleSmall)
                        pending.forEach { Text("• ${it.summary}") }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(enabled = !busy, onClick = { run { deps.api.decide(convo!!.id, true) } }) { Text("Approve") }
                            OutlinedButton(enabled = !busy, onClick = { run { deps.api.decide(convo!!.id, false) } }) { Text("Decline") }
                        }
                    }
                }
            }
            if (busy) item { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.padding(end = 10.dp)); Muted("Claude is working…") } }
            error?.let { e -> item { Text(e, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) } }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("Message Claude") }, maxLines = 5)
            IconButton(enabled = !busy && input.isNotBlank() && convo?.pending.isNullOrEmpty(), onClick = {
                val text = input.trim(); input = ""
                convo = (convo ?: Conversation()).let { it.copy(display = it.display + ChatMsg("user", text)) }
                run { deps.api.chat(id, text) }
            }) { Icon(Icons.AutoMirrored.Filled.Send, "send", tint = Gold) }
        }
    }
}

@Composable
private fun Bubble(m: ChatMsg) {
    when (m.role) {
        "user" -> Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.End) {
            Text(m.text, Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(12.dp))
        }
        "assistant" -> Text(m.text, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp))
        "tool" -> Muted("  ${m.text}", 1)
        "action" -> Text(m.text, color = if (m.text.startsWith("Approved")) Color(0xFF4CAF50) else Color(0xFFFF9800),
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        else -> Text(m.text, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
    }
}
