package com.basdhaweio.marquee

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(nav: NavHostController) {
    val deps = LocalDeps.current
    Loadable(load = { deps.api.overview() }) { o, _ ->
        LazyColumn(contentPadding = ListPadding) {
            item {
                Text("Hi ${deps.session.user ?: ""}", style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp))
            }
            if (o.pendingRequests > 0) item {
                Panel(Modifier.clickable { nav.go("requests") }) {
                    Text("${o.pendingRequests} request${if (o.pendingRequests == 1) "" else "s"} waiting for approval", color = Gold)
                }
            }
            item { SectionTitle("Services") }
            items(o.apps) { a ->
                Panel(Modifier.clickable { nav.go(if (a.app == "prowlarr") "indexers" else "queue") }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dot(if (a.health.any { it.type == "error" }) Color(0xFFFF6B6B) else if (a.health.isNotEmpty()) Gold else Color(0xFF4CAF50))
                        Spacer(Modifier.width(8.dp))
                        AppTag(a.app, a.label)
                        Spacer(Modifier.weight(1f))
                        a.queue?.let { Muted("$it queued") }
                        a.missing?.let { Spacer(Modifier.width(10.dp)); Muted("$it missing") }
                    }
                    a.health.take(2).forEach { h -> Muted("• ${h.message}", maxLines = 3) }
                }
            }
            if (o.playing.isNotEmpty()) {
                item { SectionTitle("Now playing") }
                items(o.playing) { p ->
                    Panel {
                        Text(p.title ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Muted("${p.user} · ${p.state} · ${p.decision}${p.quality?.let { " · $it" } ?: ""}")
                        LinearProgressIndicator(progress = { (p.progress?.toFloatOrNull() ?: 0f) / 100f },
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                    }
                }
            }
            if (o.disks.isNotEmpty()) {
                item { SectionTitle("Storage") }
                items(o.disks) { d ->
                    Panel {
                        Row { Text(d.label ?: d.path ?: ""); Spacer(Modifier.weight(1f)); Muted("${bytes(d.free)} free of ${bytes(d.total)}") }
                        LinearProgressIndicator(progress = { if (d.total > 0) 1f - d.free.toFloat() / d.total else 0f },
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun QueueScreen() {
    val deps = LocalDeps.current
    val scope = rememberCoroutineScope()
    Loadable(load = { deps.api.queue() }) { q, reload ->
        val all = listOf("sonarr", "radarr", "radarr4k").flatMap { q[it].orEmpty() }
        LazyColumn(contentPadding = ListPadding) {
            if (all.isEmpty()) item { Empty("Nothing downloading") }
            listOf("sonarr" to "Sonarr", "radarr" to "Radarr", "radarr4k" to "Radarr 4K").forEach { (app, label) ->
                val list = q[app].orEmpty()
                if (list.isNotEmpty()) {
                    item { SectionTitle("$label · ${list.size}") }
                    items(list, key = { "$app-${it.id}" }) { item ->
                        var menu by remember { mutableStateOf(false) }
                        MediaRow(item.title ?: item.release ?: "", item.sub, item.poster,
                            trailing = {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "actions") }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(text = { Text("Remove, blocklist & search again") }, onClick = {
                                        menu = false
                                        scope.launch {
                                            runCatching { deps.api.removeFromQueue(app, item.id, blocklist = true, search = true) }
                                                .onSuccess { deps.snack.showSnackbar("Removed - searching for another release"); reload() }
                                                .onFailure { deps.snack.showSnackbar("Failed: ${it.message}") }
                                        }
                                    })
                                    DropdownMenuItem(text = { Text("Remove only") }, onClick = {
                                        menu = false
                                        scope.launch {
                                            runCatching { deps.api.removeFromQueue(app, item.id, blocklist = false, search = false) }
                                                .onSuccess { deps.snack.showSnackbar("Removed"); reload() }
                                                .onFailure { deps.snack.showSnackbar("Failed: ${it.message}") }
                                        }
                                    })
                                }
                            },
                            extra = {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    AppTag(app, label)
                                    Muted(listOfNotNull(item.quality, item.state ?: item.status, item.timeleft, bytes(item.size)).joinToString(" · "), 1)
                                }
                                LinearProgressIndicator(progress = { (item.progress / 100).toFloat() },
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                    color = if (item.health == "warning" || item.health == "error") Color(0xFFFF6B6B) else Gold)
                                item.messages.take(2).forEach { Muted(it, 2) }
                            })
                    }
                }
            }
        }
    }
}
