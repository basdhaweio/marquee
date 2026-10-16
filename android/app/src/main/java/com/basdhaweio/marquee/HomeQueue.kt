package com.basdhaweio.marquee

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
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
import androidx.compose.material3.Icon
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
    Loadable(load = { deps.api.overview() to runCatching { deps.api.calendar(2, back = 0).items }.getOrDefault(emptyList()) }) { (o, upcoming), _ ->
        LazyColumn(contentPadding = ListPadding) {
            item {
                Text(deps.session.user?.let { "Hi $it" } ?: "Marquee", style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp))
            }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("calendar" to "Calendar", "wanted" to "Wanted", "portal/seerr" to "Seerr", "portal/tautulli" to "Tautulli")
                        .forEach { (route, label) -> AssistChip(onClick = { nav.go(route) }, label = { Text(label) }) }
                }
            }
            if (o.pendingRequests > 0) item {
                Panel(Modifier.clickable { nav.go("requests") }) {
                    Text("${o.pendingRequests} request${if (o.pendingRequests == 1) "" else "s"} waiting for approval", color = Gold)
                }
            }
            val soon = upcoming.filter { it.hasFile != true }.take(5)
            if (soon.isNotEmpty()) {
                item { SectionTitle("Coming up") }
                items(soon, key = { "soon-${it.app}-${it.episodeId ?: it.movieId}-${it.time}" }) { c ->
                    val target = c.seriesId?.let { "show/$it" } ?: c.movieId?.let { "movie/${c.app}/$it" }
                    Box(Modifier.clickable { nav.go(target ?: "calendar") }) {
                        MediaRow(c.title ?: "", c.sub, c.poster, extra = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                AppTag(c.app, c.label); Muted(day(c.time), 1)
                            }
                        })
                    }
                }
                item { TextButton(onClick = { nav.go("calendar") }, modifier = Modifier.padding(start = 4.dp)) { Text("Full calendar") } }
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
fun QueueScreen(nav: NavHostController) {
    val deps = LocalDeps.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf<QueueItem?>(null) }
    var fixAll by remember { mutableStateOf<List<QueueItem>?>(null) }
    var reloadQueue by remember { mutableStateOf({}) }
    Loadable(load = { deps.api.queue() }, autoRefreshMs = 10_000) { q, reload ->
        reloadQueue = reload
        val all = listOf("sonarr", "radarr", "radarr4k").flatMap { q[it].orEmpty() }
        val stuck = all.filter { it.stuck }
        LazyColumn(contentPadding = ListPadding) {
            if (all.isEmpty()) item { Empty("Nothing downloading") }
            if (stuck.isNotEmpty()) {
                item {
                    Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { SectionTitle("Needs attention · ${stuck.size}") }
                        val fixable = stuck.filter { it.known }
                        if (fixable.isNotEmpty()) TextButton(onClick = { fixAll = fixable }, modifier = Modifier.padding(top = 10.dp)) {
                            Text("Fix all") }
                    }
                }
                items(stuck, key = { "stuck-${it.app}-${it.id}" }) { QueueRow(it, attention = true) { open = it } }
            }
            listOf("sonarr" to "Sonarr", "radarr" to "Radarr", "radarr4k" to "Radarr 4K").forEach { (app, label) ->
                val list = q[app].orEmpty().filter { !it.stuck }
                if (list.isNotEmpty()) {
                    item { SectionTitle("$label · ${list.size}") }
                    items(list, key = { "$app-${it.id}" }) { QueueRow(it, attention = false) { open = it } }
                }
            }
            if (all.isNotEmpty()) item { Box(Modifier.padding(16.dp)) { Muted("Tap an item for details and actions. Updates every 10 seconds.") } }
        }
    }
    open?.let { item ->
        QueueItemSheet(item, nav, onDone = { open = null; reloadQueue() }, onDismiss = { open = null })
    }
    fixAll?.let { list ->
        AlertDialog(onDismissRequest = { fixAll = null },
            title = { Text("Fix ${list.size} stuck download${if (list.size == 1) "" else "s"}?") },
            text = {
                Column {
                    Text("Each one is removed from the download client, its release is blocklisted, and a search starts for another copy.")
                    Spacer(Modifier.height(8.dp))
                    list.take(8).forEach { Muted("• ${it.title}${it.sub?.takeIf { s -> s.isNotBlank() }?.let { s -> " · $s" } ?: ""}", 1) }
                    if (list.size > 8) Muted("…and ${list.size - 8} more", 1)
                }
            },
            confirmButton = { TextButton(onClick = {
                fixAll = null
                scope.launch {
                    val failed = list.count { runCatching { deps.api.removeFromQueue(it.app, it.id, blocklist = true, search = true) }.isFailure }
                    deps.snack.showSnackbar(if (failed == 0) "Fixed ${list.size} - searching for replacements" else "$failed of ${list.size} failed")
                    reloadQueue()
                }
            }) { Text("Fix all") } },
            dismissButton = { TextButton(onClick = { fixAll = null }) { Text("Cancel") } })
    }
}

@Composable
private fun QueueRow(item: QueueItem, attention: Boolean, onClick: () -> Unit) {
    Box(Modifier.clickable(onClick = onClick)) {
        MediaRow(item.title ?: item.release ?: "", item.sub, item.poster,
            trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "details", tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            extra = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AppTag(item.app, item.label)
                    Muted(listOfNotNull(item.quality, item.state ?: item.status, item.timeleft, bytes(item.size)).joinToString(" · "), 1)
                }
                LinearProgressIndicator(progress = { (item.progress / 100).toFloat() },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp), color = if (attention) Bad else Gold)
                if (attention) item.messages.firstOrNull()?.let { Text(it, color = Bad, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
            })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueItemSheet(item: QueueItem, nav: NavHostController, onDone: () -> Unit, onDismiss: () -> Unit) {
    val deps = LocalDeps.current
    val scope = rememberCoroutineScope()
    var releases by remember { mutableStateOf(false) }
    fun act(msg: String, block: suspend () -> Unit) = scope.launch {
        runCatching { block() }.onSuccess { deps.snack.showSnackbar(msg); onDone() }
            .onFailure { deps.snack.showSnackbar("Failed: ${it.message}") }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Poster(item.poster, 56.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.title ?: "", style = MaterialTheme.typography.titleMedium)
                    if (!item.sub.isNullOrBlank()) Muted(item.sub)
                    AppTag(item.app, item.label)
                }
            }
            LinearProgressIndicator(progress = { (item.progress / 100).toFloat() }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                color = if (item.stuck) Bad else Gold)
            Muted(listOfNotNull("${item.progress}%", item.timeleft?.let { "$it left" }, bytes(item.size), item.quality,
                item.client, item.protocol).joinToString(" · "), 2)
            Muted("Status: " + listOfNotNull(item.status, item.state, item.health).joinToString(" · "), 2)
            item.release?.let { SelectionContainer { Text(it, style = MaterialTheme.typography.bodySmall) } }
            item.messages.forEach {
                Text(it, color = if (item.stuck) Bad else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = { act("Blocklisted - searching for another release") {
                deps.api.removeFromQueue(item.app, item.id, blocklist = true, search = true) } },
                modifier = Modifier.fillMaxWidth()) { Text("Blocklist & find another") }
            if (item.known) OutlinedButton(onClick = { releases = true }, modifier = Modifier.fillMaxWidth()) { Text("Pick a different release…") }
            OutlinedButton(onClick = { act("Removed") { deps.api.removeFromQueue(item.app, item.id, blocklist = false, search = false) } },
                modifier = Modifier.fillMaxWidth()) { Text("Remove download") }
            OutlinedButton(onClick = { act("No longer tracked - the download stays in ${item.client ?: "the client"}") {
                deps.api.removeFromQueue(item.app, item.id, blocklist = false, search = false, keep = true) } },
                modifier = Modifier.fillMaxWidth()) { Text("Stop tracking (keep the download)") }
            val target = item.seriesId?.let { "show/$it" } ?: item.movieId?.let { "movie/${item.app}/$it" }
            if (target != null) TextButton(onClick = { onDismiss(); nav.go(target) }) {
                Text(if (item.seriesId != null) "Open show" else "Open movie") }
        }
    }
    if (releases) ReleasesSheet(item.app, episodeId = item.episodeId, movieId = item.movieId,
        title = listOfNotNull(item.title, item.sub).joinToString(" · "), onSearchAuto = null,
        onDismiss = { releases = false; onDone() })
}
