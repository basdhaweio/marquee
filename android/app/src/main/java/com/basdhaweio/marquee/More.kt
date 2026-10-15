package com.basdhaweio.marquee

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch

@Composable
fun MoreScreen(nav: NavHostController) {
    val entries = listOf(
        Triple("requests", "Requests", Icons.Filled.Inbox), Triple("activity", "Activity & stats", Icons.Filled.PlayCircle),
        Triple("calendar", "Calendar", Icons.Filled.CalendarMonth), Triple("history", "Download history", Icons.Filled.History),
        Triple("indexers", "Indexers", Icons.AutoMirrored.Filled.List), Triple("settings", "Settings", Icons.Filled.Settings),
    )
    LazyColumn {
        items(entries) { (route, label, icon: ImageVector) ->
            ListItem(headlineContent = { Text(label) }, leadingContent = { Icon(icon, null, tint = Gold) },
                modifier = Modifier.clickable { nav.go(route) })
        }
    }
}

// ---------------------------------------------------------------- Seerr requests
@Composable
fun RequestsScreen() {
    val deps = LocalDeps.current
    val scope = rememberCoroutineScope()
    var filter by rememberSaveable { mutableStateOf("pending") }
    Column {
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("pending", "processing", "available", "all").forEach {
                FilterChip(filter == it, { filter = it }, label = { Text(it.replaceFirstChar(Char::uppercase)) })
            }
        }
        Loadable(key = filter, load = { deps.api.requests(filter).items }) { reqs, reload ->
            LazyColumn(contentPadding = ListPadding) {
                if (reqs.isEmpty()) item { Empty("No $filter requests") }
                items(reqs, key = { it.id }) { r ->
                    MediaRow(r.title ?: "", listOfNotNull(r.year, r.type, if (r.is4k) "4K" else null, "by ${r.by}",
                        ago(r.created)).joinToString(" · "), r.poster,
                        extra = { Muted("${r.status} · ${r.mediaStatus}", 1) },
                        trailing = {
                            if (r.status == "pending") Row {
                                TextButton(onClick = { scope.launch { runCatching { deps.api.decideRequest(r.id, false) }
                                    .onSuccess { deps.snack.showSnackbar("Declined"); reload() } } }) { Text("Decline") }
                                TextButton(onClick = { scope.launch { runCatching { deps.api.decideRequest(r.id, true) }
                                    .onSuccess { deps.snack.showSnackbar("Approved"); reload() } } }) { Text("Approve") }
                            }
                        })
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Tautulli
@Composable
fun ActivityScreen() {
    val deps = LocalDeps.current
    Loadable(load = { deps.api.activity() to runCatching { deps.api.stats(30) }.getOrDefault(emptyMap()) }) { (a, stats), _ ->
        LazyColumn(contentPadding = ListPadding) {
            item { SectionTitle("Now playing") }
            if (a.playing.isEmpty()) item { Empty("Nothing playing") }
            items(a.playing) { p ->
                Panel {
                    Text(p.title ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Muted(listOfNotNull(p.user, p.player, p.decision, p.quality, p.progress?.let { "$it%" }).joinToString(" · "))
                }
            }
            listOf("top_tv" to "Top shows (30 days)", "top_movies" to "Top movies (30 days)", "top_users" to "Most active").forEach { (k, label) ->
                val rows = stats[k].orEmpty()
                if (rows.isNotEmpty()) {
                    item { SectionTitle(label) }
                    items(rows) { r -> Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Text(r.title ?: "", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis); Muted("${r.plays ?: 0} plays") } }
                }
            }
            item { SectionTitle("Recently watched") }
            items(a.history) { h ->
                Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(h.title ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Muted("${h.user} · ${agoEpoch(h.time)}", 1)
                    }
                    if ((h.watched ?: 0.0) >= 1.0) Muted("watched")
                }
            }
        }
    }
}

// ---------------------------------------------------------------- calendar + history
@Composable
fun CalendarScreen() {
    val deps = LocalDeps.current
    Loadable(load = { deps.api.calendar(14).items }) { items, _ ->
        LazyColumn(contentPadding = ListPadding) {
            if (items.isEmpty()) item { Empty("Nothing in the next two weeks") }
            items(items) { c ->
                MediaRow(c.title ?: "", c.sub, c.poster, extra = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AppTag(c.app, c.label); Muted(day(c.time), 1)
                    }
                }, trailing = { if (c.hasFile == true) Muted("✓") })
            }
        }
    }
}

@Composable
fun HistoryScreen() {
    val deps = LocalDeps.current
    var app by rememberSaveable { mutableStateOf("sonarr") }
    Column {
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("sonarr" to "Sonarr", "radarr" to "Radarr", "radarr4k" to "Radarr 4K").forEach { (a, l) ->
                FilterChip(app == a, { app = a }, label = { Text(l) })
            }
        }
        Loadable(key = app, load = { deps.api.history(app).items }) { items, _ ->
            LazyColumn(contentPadding = ListPadding) {
                items(items) { h ->
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Text("${h.title ?: ""} ${h.sub ?: ""}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Muted("${h.event} · ${h.quality ?: ""} · ${ago(h.time)}", 1)
                        Muted(h.release ?: "", 1)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Prowlarr
@Composable
fun IndexersScreen() {
    val deps = LocalDeps.current
    val scope = rememberCoroutineScope()
    var q by rememberSaveable { mutableStateOf("") }
    var searched by rememberSaveable { mutableStateOf("") }
    Column {
        OutlinedTextField(q, { q = it }, singleLine = true, label = { Text("Search all indexers") },
            trailingIcon = { IconButton(onClick = { searched = q }) { Icon(Icons.Filled.Search, "search") } },
            modifier = Modifier.fillMaxWidth().padding(12.dp))
        if (searched.isNotBlank()) {
            Row(Modifier.padding(horizontal = 16.dp)) { Muted("Results for \"$searched\"", 1); Spacer(Modifier.weight(1f))
                TextButton(onClick = { searched = "" }) { Text("Back to indexers") } }
            Loadable(key = searched, load = { deps.api.indexerSearch(searched).items }) { res, _ ->
                LazyColumn(contentPadding = ListPadding) {
                    if (res.isEmpty()) item { Empty("No results") }
                    items(res) { r ->
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                            Text(r.title ?: "", maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Muted(listOfNotNull(r.indexer, bytes(r.size), r.seeders?.let { "$it seeders" }, r.age?.let { "${it}d old" }).joinToString(" · "), 1)
                        }
                    }
                }
            }
        } else Loadable(load = { deps.api.indexers().items }) { list, _ ->
            LazyColumn(contentPadding = ListPadding) {
                items(list, key = { it.id }) { i ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Dot(when { !i.enabled -> Color.Gray; i.failing -> Color(0xFFFF6B6B); else -> Color(0xFF4CAF50) })
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(i.name ?: "")
                            Muted(listOfNotNull(i.protocol, i.privacy, i.priority?.let { "priority $it" },
                                if (!i.enabled) "disabled" else null, if (i.failing) "failing" else null).joinToString(" · "), 1)
                        }
                        OutlinedButton(onClick = { scope.launch {
                            val ok = runCatching { deps.api.testIndexer(i.id) }.getOrDefault(false)
                            deps.snack.showSnackbar(if (ok) "${i.name}: OK" else "${i.name}: test failed")
                        } }) { Text("Test") }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- settings
@Composable
fun SettingsScreen(onSignOut: () -> Unit) {
    val deps = LocalDeps.current
    val ctx = LocalContext.current
    var server by remember { mutableStateOf(deps.session.server) }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Signed in as ${deps.session.user}", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(server, { server = it }, label = { Text("Marquee server") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { deps.session.server = server }) { Text("Save server") }
            OutlinedButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(deps.session.server + "/"))) }) { Text("Open web app") }
        }
        Muted("Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}). Updates are offered automatically when jerry has a newer build.")
        OutlinedButton(onClick = onSignOut) { Text("Sign out") }
    }
}
