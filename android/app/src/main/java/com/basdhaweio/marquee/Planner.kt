package com.basdhaweio.marquee

import android.annotation.SuppressLint
import android.os.Message
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ---------------------------------------------------------------- calendar
@Composable
fun CalendarScreen(nav: NavHostController) {
    val deps = LocalDeps.current
    val scope = rememberCoroutineScope()
    var days by rememberSaveable { mutableStateOf(14) }
    Column {
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(7, 14, 30).forEach { FilterChip(days == it, { days = it }, label = { Text("$it days") }) }
        }
        Loadable(key = days, load = { deps.api.calendar(days, back = 3).items }) { items, reload ->
            val today = LocalDate.now()
            val groups = items.groupBy { localDate(it.time) }.toSortedMap(compareBy { it ?: LocalDate.MAX })
            val state = rememberLazyListState()
            // start at today; the last few days sit above it for catching up on missed episodes
            LaunchedEffect(items) {
                var idx = 0
                for ((d, list) in groups) { if (d != null && d >= today) break; idx += 1 + list.size }
                state.scrollToItem(idx)
            }
            LazyColumn(state = state, contentPadding = ListPadding) {
                if (items.isEmpty()) item { Empty("Nothing scheduled") }
                groups.forEach { (d, list) ->
                    item(key = "h$d") { SectionTitle(dayLabel(d, today)) }
                    items(list, key = { "${it.app}-${it.episodeId ?: it.movieId}-${it.time}" }) { c ->
                        val aired = localDate(c.time)?.let { it <= today } == true
                        val target = c.seriesId?.let { "show/$it" } ?: c.movieId?.let { "movie/${c.app}/$it" }
                        Box(Modifier.clickable(enabled = target != null) { target?.let { nav.go(it) } }) {
                            MediaRow(c.title ?: "", c.sub, c.poster, extra = {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    AppTag(c.app, c.label)
                                    if (c.app == "sonarr") Muted(clock(c.time), 1)
                                    if (c.monitored == false) Muted("unmonitored", 1)
                                }
                            }, trailing = {
                                when {
                                    c.hasFile == true -> Icon(Icons.Filled.CheckCircle, "downloaded", tint = Good)
                                    aired && c.monitored == true && (c.episodeId ?: c.movieId) != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("Missing", color = Bad, style = MaterialTheme.typography.bodySmall)
                                        IconButton(onClick = { scope.launch {
                                            runCatching { deps.api.searchNow(c.app, listOf((c.episodeId ?: c.movieId)!!)) }
                                                .onSuccess { deps.snack.showSnackbar("Searching for ${c.title}") }
                                                .onFailure { deps.snack.showSnackbar("Failed: ${it.message}") }
                                        } }) { Icon(Icons.Filled.Search, "search") }
                                    }
                                }
                            })
                        }
                    }
                }
            }
        }
    }
}

private fun localDate(iso: String?): LocalDate? =
    runCatching { OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).toLocalDate() }.getOrNull()

private fun clock(iso: String?): String =
    runCatching { OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("h:mm a")) }.getOrDefault("")

private fun dayLabel(d: LocalDate?, today: LocalDate): String = when (d) {
    null -> "Unscheduled"
    today -> "Today"
    today.plusDays(1) -> "Tomorrow"
    today.minusDays(1) -> "Yesterday"
    else -> d.format(DateTimeFormatter.ofPattern("EEEE, MMM d"))
}

// ---------------------------------------------------------------- wanted
@Composable
fun WantedScreen(nav: NavHostController) {
    var app by rememberSaveable { mutableStateOf("sonarr") }
    var kind by rememberSaveable { mutableStateOf("missing") }
    Column {
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("sonarr" to "Sonarr", "radarr" to "Radarr", "radarr4k" to "Radarr 4K").forEach { (a, l) ->
                FilterChip(app == a, { app = a }, label = { Text(l) })
            }
        }
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("missing" to "Missing", "cutoff" to "Below cutoff").forEach { (k, l) ->
                FilterChip(kind == k, { kind = k }, label = { Text(l) })
            }
        }
        WantedList(app, kind, nav)
    }
}

@Composable
private fun WantedList(app: String, kind: String, nav: NavHostController) {
    val deps = LocalDeps.current
    val scope = rememberCoroutineScope()
    var releasesFor by remember { mutableStateOf<WantedItem?>(null) }
    var confirmSearch by remember { mutableStateOf<List<WantedItem>?>(null) }
    Loadable(key = "$app/$kind", load = { deps.api.wanted(app, kind, 1) }) { first, _ ->
        var extra by remember(first) { mutableStateOf(listOf<WantedItem>()) }
        var page by remember(first) { mutableStateOf(1) }
        var loadingMore by remember(first) { mutableStateOf(false) }
        var hidden by remember(first) { mutableStateOf(setOf<Int>()) }
        val all = (first.items + extra).filter { it.id !in hidden }
        val searchable = all.filter { it.released }.take(50)
        LazyColumn(contentPadding = ListPadding) {
            item {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Muted("${first.total} ${if (kind == "missing") "missing" else "below cutoff"} (monitored only)", 1)
                    Spacer(Modifier.weight(1f))
                    if (searchable.isNotEmpty()) TextButton(onClick = { confirmSearch = searchable }) { Text("Search ${searchable.size}") }
                }
            }
            if (all.isEmpty()) item { Empty("Nothing wanted - all caught up") }
            items(all, key = { it.id }) { w ->
                var menu by remember { mutableStateOf(false) }
                val target = w.seriesId?.let { "show/$it" } ?: if (app != "sonarr") "movie/$app/${w.id}" else null
                Box(Modifier.clickable(enabled = target != null) { target?.let { nav.go(it) } }) {
                    MediaRow(w.title ?: "", w.sub, w.poster, extra = {
                        Muted(listOfNotNull(
                            if (!w.released) "not released yet" else w.time?.let { if (app == "sonarr") "aired ${ago(it)}" else "released ${it.take(10)}" },
                            w.quality?.let { "have $it" },
                            w.lastSearch?.let { "searched ${ago(it)}" }).joinToString(" · "), 1)
                    }, trailing = {
                        if (w.released) IconButton(onClick = { scope.launch {
                            runCatching { deps.api.searchNow(app, listOf(w.id)) }
                                .onSuccess { deps.snack.showSnackbar("Searching for ${w.title}") }
                                .onFailure { deps.snack.showSnackbar("Failed: ${it.message}") }
                        } }) { Icon(Icons.Filled.Search, "search") }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "more") }
                            DropdownMenu(menu, { menu = false }) {
                                if (w.released) DropdownMenuItem(text = { Text("Pick a release…") }, onClick = { menu = false; releasesFor = w })
                                DropdownMenuItem(text = { Text("Unmonitor") }, onClick = {
                                    menu = false
                                    scope.launch {
                                        runCatching { deps.api.setMonitored(app, listOf(w.id), false, if (app == "sonarr") "episode" else "item") }
                                            .onSuccess { hidden = hidden + w.id; deps.snack.showSnackbar("Unmonitored ${w.title}") }
                                            .onFailure { deps.snack.showSnackbar("Failed: ${it.message}") }
                                    }
                                })
                            }
                        }
                    })
                }
            }
            if (first.items.size + extra.size < first.total) item {
                Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                    OutlinedButton(enabled = !loadingMore, onClick = {
                        loadingMore = true
                        scope.launch {
                            runCatching { deps.api.wanted(app, kind, page + 1) }
                                .onSuccess { extra = extra + it.items; page += 1 }
                                .onFailure { deps.snack.showSnackbar("Failed: ${it.message}") }
                            loadingMore = false
                        }
                    }) { Text(if (loadingMore) "Loading…" else "Load more") }
                }
            }
        }
    }
    confirmSearch?.let { list ->
        AlertDialog(onDismissRequest = { confirmSearch = null },
            title = { Text("Search for ${list.size} item${if (list.size == 1) "" else "s"}?") },
            text = { Text("${appLabel(app)} searches your indexers for each one and grabs anything that matches your quality profile. It runs in the background.") },
            confirmButton = { TextButton(onClick = {
                confirmSearch = null
                scope.launch {
                    runCatching { deps.api.searchNow(app, list.map { it.id }) }
                        .onSuccess { deps.snack.showSnackbar("Searching ${list.size} - check the Queue in a few minutes") }
                        .onFailure { deps.snack.showSnackbar("Failed: ${it.message}") }
                }
            }) { Text("Search") } },
            dismissButton = { TextButton(onClick = { confirmSearch = null }) { Text("Cancel") } })
    }
    releasesFor?.let { w ->
        ReleasesSheet(app, episodeId = if (app == "sonarr") w.id else null, movieId = if (app == "sonarr") null else w.id,
            title = listOfNotNull(w.title, w.sub).joinToString(" · "), onSearchAuto = null, onDismiss = { releasesFor = null })
    }
}

private fun appLabel(app: String) = when (app) { "radarr" -> "Radarr"; "radarr4k" -> "Radarr 4K"; else -> "Sonarr" }

// ---------------------------------------------------------------- portals: the full Seerr / Tautulli sites, in-app
// Their own logins (including "Sign in with Plex", which opens a popup window) happen inside; links never leave the app.
@Composable
fun PortalScreen(nav: NavHostController, svc: String) {
    val deps = LocalDeps.current
    val link by produceState<Result<ServiceLink>?>(null, svc) {
        value = runCatching { deps.api.links()[svc] ?: error("No link is configured for $svc") }
    }
    when (val l = link) {
        null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Muted("Opening…") }
        else -> l.fold(onSuccess = { PortalWeb(nav, it.url, it.label) },
            onFailure = { Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Muted(it.message ?: "Couldn't open") } })
    }
}

/** [secureHost]: Tautulli builds http:// redirects behind the Tailscale HTTPS proxy (and Android blocks cleartext),
 *  so http links to that host are upgraded to https. */
@SuppressLint("SetJavaScriptEnabled")
private fun WebView.setUp(secureHost: String?) {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.setSupportMultipleWindows(true)
    settings.javaScriptCanOpenWindowsAutomatically = true
    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
    webViewClient = object : WebViewClient() {
        // keep web pages in here (plex.tv links would otherwise open the Plex app); drop app-launch schemes
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val u = request.url
            if (u.scheme == "http" && u.host == secureHost) {
                view.loadUrl(u.buildUpon().scheme("https").build().toString()); return true
            }
            return u.scheme != "http" && u.scheme != "https"
        }
    }
}

@Composable
private fun PortalWeb(nav: NavHostController, url: String, title: String) {
    val ctx = LocalContext.current
    var progress by remember { mutableStateOf(0) }
    var popup by remember { mutableStateOf<WebView?>(null) }
    val host = remember(url) { android.net.Uri.parse(url).host }
    val web = remember(url) {
        WebView(ctx).apply {
            setUp(host)
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress }
                override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                    val child = WebView(view.context).apply {
                        setUp(host)
                        webChromeClient = object : WebChromeClient() {
                            override fun onCloseWindow(window: WebView) { popup = null }
                        }
                    }
                    (resultMsg.obj as WebView.WebViewTransport).webView = child
                    resultMsg.sendToTarget()
                    popup = child
                    return true
                }
            }
            loadUrl(url)
        }
    }
    DisposableEffect(web) { onDispose { CookieManager.getInstance().flush() } }
    BackHandler { if (web.canGoBack()) web.goBack() else nav.popBackStack() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (web.canGoBack()) web.goBack() else nav.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "back") }
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { web.reload() }) { Icon(Icons.Filled.Refresh, "reload") }
        }
        if (progress in 1..99) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
        AndroidView(factory = { web }, modifier = Modifier.fillMaxSize(), onRelease = { it.destroy() })
    }
    popup?.let { p ->
        Dialog(onDismissRequest = { popup = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize()) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { popup = null }) { Text("Close") }
                        Spacer(Modifier.width(4.dp))
                        Text("Sign in", style = MaterialTheme.typography.titleMedium)
                    }
                    AndroidView(factory = { p }, modifier = Modifier.fillMaxSize(), onRelease = { it.destroy() })
                }
            }
        }
    }
}
