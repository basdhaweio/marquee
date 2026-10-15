package com.basdhaweio.marquee

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch

private val LibTabs = listOf("Shows", "Movies", "4K")

@Composable
fun LibraryScreen(nav: NavHostController) {
    val deps = LocalDeps.current
    var tab by rememberSaveable { mutableStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    Box(Modifier.fillMaxSize()) {
        Column {
            TabRow(selectedTabIndex = tab) {
                LibTabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
            }
            OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("Filter ${LibTabs[tab].lowercase()}") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp))
            when (tab) {
                0 -> Loadable(key = "shows", load = { deps.api.shows().items }) { shows, _ ->
                    val list = shows.filter { query.isBlank() || it.title?.contains(query, true) == true }
                    LazyColumn(contentPadding = ListPadding) {
                        if (list.isEmpty()) item { Empty("No shows") }
                        items(list, key = { it.id }) { s ->
                            Box(Modifier.clickable { nav.go("show/${s.id}") }) {
                                MediaRow(s.title ?: "", listOfNotNull(s.year?.toString(), s.network,
                                    "${s.episodes}/${s.episodesTotal} eps", bytes(s.sizeOnDisk)).joinToString(" · "), s.poster,
                                    trailing = { if (!s.monitored) Muted("unmonitored") })
                            }
                        }
                    }
                }
                else -> {
                    val app = if (tab == 1) "radarr" else "radarr4k"
                    Loadable(key = app, load = { deps.api.movies(app).items }) { movies, _ ->
                        val list = movies.filter { query.isBlank() || it.title?.contains(query, true) == true }
                        LazyColumn(contentPadding = ListPadding) {
                            if (list.isEmpty()) item { Empty("No movies") }
                            items(list, key = { it.id }) { m ->
                                Box(Modifier.clickable { nav.go("movie/$app/${m.id}") }) {
                                    MediaRow(m.title ?: "", listOfNotNull(m.year?.toString(), m.quality ?: if (m.hasFile) null else "missing",
                                        if (m.sizeOnDisk > 0) bytes(m.sizeOnDisk) else null).joinToString(" · "), m.poster,
                                        trailing = { if (m.hasFile) Icon(Icons.Filled.CheckCircle, "has file", tint = Color(0xFF4CAF50)) })
                                }
                            }
                        }
                    }
                }
            }
        }
        FloatingActionButton(onClick = { nav.go(if (tab == 0) "add/show" else "add/movie") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) { Icon(Icons.Filled.Add, "Add") }
    }
}

// ---------------------------------------------------------------- show detail
@Composable
fun ShowScreen(nav: NavHostController, id: Int) {
    val deps = LocalDeps.current
    val scope = rememberCoroutineScope()
    var releasesFor by remember { mutableStateOf<Episode?>(null) }
    Loadable(key = id, load = { deps.api.show(id) }) { s, reload ->
        var open by remember { mutableStateOf(setOf<Int>()) }
        fun act(msg: String, block: suspend () -> Unit) = scope.launch {
            runCatching { block() }.onSuccess { deps.snack.showSnackbar(msg); reload() }
                .onFailure { deps.snack.showSnackbar("Failed: ${it.message}") }
        }
        LazyColumn(contentPadding = ListPadding) {
            item {
                Row(Modifier.padding(16.dp)) {
                    Poster(s.poster, 90.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.title ?: "", style = MaterialTheme.typography.titleLarge)
                        Muted(listOfNotNull(s.year?.toString(), s.network, s.status, "${s.episodes}/${s.episodesTotal} eps",
                            bytes(s.sizeOnDisk)).joinToString(" · "))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Monitored", Modifier.weight(1f))
                            Switch(s.monitored, { m -> act(if (m) "Monitoring" else "Unmonitored") { deps.api.setMonitored("sonarr", listOf(s.id), m) } })
                        }
                    }
                }
                if (!s.overview.isNullOrBlank()) Box(Modifier.padding(horizontal = 16.dp)) { Muted(s.overview, 4) }
            }
            s.seasons.sortedByDescending { it.season }.forEach { season ->
                val isOpen = season.season in open
                val have = season.episodes.count { it.hasFile }
                item(key = "s${season.season}") {
                    Row(Modifier.fillMaxWidth().clickable { open = if (isOpen) open - season.season else open + season.season }
                        .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (season.season == 0) "Specials" else "Season ${season.season}", Modifier.weight(1f),
                            style = MaterialTheme.typography.titleSmall)
                        Muted("$have/${season.episodes.size}")
                        val missing = season.episodes.filter { it.monitored && !it.hasFile }.map { it.id }
                        if (missing.isNotEmpty()) IconButton(onClick = {
                            act("Searching ${missing.size} missing episode(s)") { deps.api.searchNow("sonarr", missing) }
                        }) { Icon(Icons.Filled.Search, "search missing") }
                        Icon(if (isOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
                    }
                }
                if (isOpen) items(season.episodes.sortedByDescending { it.episode }, key = { it.id }) { e ->
                    Row(Modifier.fillMaxWidth().clickable { releasesFor = e }.padding(start = 24.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${e.episode}. ${e.title ?: ""}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Muted(listOfNotNull(e.airDate, e.quality ?: if (e.hasFile) null else "missing",
                                e.languages.takeIf { it.isNotEmpty() }?.joinToString("/"), e.releaseGroup).joinToString(" · "), 1)
                        }
                        Switch(e.monitored, { m -> act(if (m) "Monitoring" else "Unmonitored") {
                            deps.api.setMonitored("sonarr", listOf(e.id), m, "episode") } })
                    }
                }
            }
        }
    }
    releasesFor?.let { e -> ReleasesSheet("sonarr", episodeId = e.id, title = "S${e.season}E${e.episode} ${e.title ?: ""}",
        onSearchAuto = { scope.launch { runCatching { deps.api.searchNow("sonarr", listOf(e.id)) }
            .onSuccess { deps.snack.showSnackbar("Searching") } } }, onDismiss = { releasesFor = null }) }
}

// ---------------------------------------------------------------- movie detail
@Composable
fun MovieScreen(app: String, id: Int) {
    val deps = LocalDeps.current
    val scope = rememberCoroutineScope()
    var showReleases by remember { mutableStateOf(false) }
    Loadable(key = "$app$id", load = { deps.api.movie(app, id) }) { m, reload ->
        Column(Modifier.padding(16.dp)) {
            Row {
                Poster(m.poster, 110.dp)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(m.title ?: "", style = MaterialTheme.typography.titleLarge)
                    Muted(listOfNotNull(m.year?.toString(), m.runtime?.let { "${it}m" }, m.status).joinToString(" · "))
                    AppTag(app, if (app == "radarr4k") "Radarr 4K" else "Radarr")
                    Spacer(Modifier.padding(4.dp))
                    Text(if (m.hasFile) "${m.quality} · ${bytes(m.sizeOnDisk)}" else "Not downloaded",
                        color = if (m.hasFile) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                Text("Monitored", Modifier.weight(1f))
                Switch(m.monitored, { v -> scope.launch {
                    runCatching { deps.api.setMonitored(app, listOf(m.id), v) }.onSuccess { reload() }
                        .onFailure { deps.snack.showSnackbar("Failed: ${it.message}") } } })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                OutlinedButton(onClick = { scope.launch { runCatching { deps.api.searchNow(app, listOf(m.id)) }
                    .onSuccess { deps.snack.showSnackbar("Searching") }.onFailure { deps.snack.showSnackbar("Failed: ${it.message}") } } }) {
                    Text("Search now") }
                OutlinedButton(onClick = { showReleases = true }) { Text("Pick a release") }
            }
            m.overview?.let { Muted(it, 12) }
        }
        if (showReleases) ReleasesSheet(app, movieId = m.id, title = m.title ?: "", onSearchAuto = null, onDismiss = { showReleases = false })
    }
}

// ---------------------------------------------------------------- interactive search
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReleasesSheet(app: String, episodeId: Int? = null, movieId: Int? = null, title: String,
                  onSearchAuto: (() -> Unit)?, onDismiss: () -> Unit) {
    val deps = LocalDeps.current
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf<Release?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
        if (onSearchAuto != null) TextButton(onClick = { onSearchAuto(); onDismiss() }, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text("Automatic search") }
        Muted("  Searching indexers can take up to a minute…", 1)
        Box(Modifier.fillMaxWidth().fillMaxSize(0.85f)) {
            Loadable(key = "$app$episodeId$movieId", load = { deps.api.releases(app, episodeId, movieId).items }) { rel, _ ->
                LazyColumn(contentPadding = ListPadding) {
                    if (rel.isEmpty()) item { Empty("No releases found") }
                    items(rel) { r ->
                        Column(Modifier.fillMaxWidth().clickable { confirm = r }.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text(r.title ?: "", maxLines = 2, overflow = TextOverflow.Ellipsis,
                                color = if (r.approved) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                            Muted(listOfNotNull(r.quality, bytes(r.size), r.seeders?.let { "$it seeders" } ?: r.protocol, r.indexer)
                                .joinToString(" · "), 1)
                            if (!r.approved && r.rejections.isNotEmpty()) Text(r.rejections.first(), color = Color(0xFFFF9800),
                                style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        }
                    }
                }
            }
        }
    }
    confirm?.let { r ->
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text("Grab this release?") },
            text = { Text(r.title ?: "") },
            confirmButton = { TextButton(onClick = {
                confirm = null
                scope.launch { runCatching { deps.api.grab(app, r) }.onSuccess { deps.snack.showSnackbar("Sent to download client"); onDismiss() }
                    .onFailure { deps.snack.showSnackbar("Failed: ${it.message}") } }
            }) { Text("Grab") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } })
    }
}

// ---------------------------------------------------------------- add
@Composable
fun AddScreen(nav: NavHostController, kind: String) {
    val deps = LocalDeps.current
    val scope = rememberCoroutineScope()
    var term by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf("") }
    var to4k by rememberSaveable { mutableStateOf(false) }
    Column {
        OutlinedTextField(term, { term = it }, singleLine = true, label = { Text(if (kind == "show") "Find a show" else "Find a movie") },
            trailingIcon = { IconButton(onClick = { submitted = term }) { Icon(Icons.Filled.Search, "search") } },
            modifier = Modifier.fillMaxWidth().padding(12.dp))
        if (kind == "movie") Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Add to Radarr 4K instead", Modifier.weight(1f)); Switch(to4k, { to4k = it })
        }
        if (submitted.isNotBlank()) Loadable(key = "$kind$submitted", load = { deps.api.lookup(kind, submitted).items }) { res, _ ->
            LazyColumn(contentPadding = ListPadding) {
                if (res.isEmpty()) item { Empty("Nothing found") }
                items(res) { r ->
                    val already = if (kind == "movie" && to4k) r.in4k else r.inLibrary
                    MediaRow(r.title ?: "", listOfNotNull(r.year?.toString(), r.network, r.status).joinToString(" · "), r.poster,
                        extra = { r.overview?.let { Muted(it, 2) } },
                        trailing = {
                            if (already) AssistChip(onClick = {
                                if (kind == "show") r.libraryId?.let { nav.go("show/$it") }
                            }, label = { Text("In library") })
                            else TextButton(onClick = {
                                scope.launch {
                                    runCatching {
                                        if (kind == "show") deps.api.addShow(r.tvdbId!!) else deps.api.addMovie(if (to4k) "radarr4k" else "radarr", r.tmdbId!!)
                                    }.onSuccess { deps.snack.showSnackbar("Added ${r.title} - searching"); nav.popBackStack() }
                                        .onFailure { deps.snack.showSnackbar("Failed: ${it.message}") }
                                }
                            }) { Text("Add") }
                        })
                }
            }
        }
    }
}
