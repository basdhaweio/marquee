package com.basdhaweio.marquee

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // always-dark UI: light status/nav bar icons
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        val session = Session(this)
        val api = Api(session)
        setContent {
            MarqueeTheme {
                val snack = remember { SnackbarHostState() }
                var signedIn by remember { mutableStateOf(session.signedIn) }
                api.onSignedOut = { signedIn = false }
                CompositionLocalProvider(LocalDeps provides Deps(session, api, snack)) {
                    if (signedIn) MainScaffold(onSignOut = { session.token = null; signedIn = false })
                    else SignInScreen(onSignedIn = { signedIn = true })
                }
            }
        }
    }

    // Plex sends us back via marquee://signed-in; the sign-in screen's poll does the rest.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}

// ---------------------------------------------------------------- sign in
@Composable
fun SignInScreen(onSignedIn: () -> Unit) {
    val deps = LocalDeps.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Marquee", style = MaterialTheme.typography.displaySmall, color = Gold)
        Text("Your media server, in one place", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(32.dp))
        Button(enabled = !busy, onClick = {
            busy = true; status = "Opening Plex…"
            scope.launch {
                try {
                    val pin = deps.api.newPin()
                    openInBrowser(ctx, Uri.parse(pin.authUrl))
                    status = "Waiting for Plex…"
                    repeat(150) {
                        delay(2000)
                        val r = runCatching { deps.api.checkPin(pin.id) }.getOrNull() ?: return@repeat
                        when (r.status) {
                            "ok" -> { deps.session.token = r.token; deps.session.user = r.user; onSignedIn(); return@launch }
                            "denied" -> { status = "That Plex account isn't allowed here."; busy = false; return@launch }
                        }
                    }
                    status = "Timed out - try again."
                } catch (e: Exception) {
                    status = e.message
                }
                busy = false
            }
        }) { Text("Sign in with Plex") }
        status?.let { Spacer(Modifier.height(16.dp)); Text(it, textAlign = TextAlign.Center) }
        Spacer(Modifier.height(24.dp))
        Muted("Server: ${deps.session.server}")
    }
}

/** The Plex app claims app.plex.tv links, so an unpinned intent opens it instead of the sign-in page.
 *  Pin the tab to a real browser (the default one if it does Custom Tabs) and never hand off to other apps. */
fun openInBrowser(ctx: Context, uri: Uri) {
    val pm = ctx.packageManager
    val probe = Intent(Intent.ACTION_VIEW, Uri.parse("http://example.com"))   // what plain web links resolve to
    val browsers = pm.queryIntentActivities(probe, PackageManager.MATCH_ALL).map { it.activityInfo.packageName }
    val pkg = CustomTabsClient.getPackageName(ctx, browsers) ?: browsers.firstOrNull()
    val tab = CustomTabsIntent.Builder().setSendToExternalDefaultHandlerEnabled(false).build()
    if (pkg != null) tab.intent.setPackage(pkg)
    tab.launchUrl(ctx, uri)
}

// ---------------------------------------------------------------- shell
private data class Tab(val route: String, val label: String, val icon: ImageVector)
private val Tabs = listOf(
    Tab("home", "Home", Icons.Filled.Home), Tab("queue", "Queue", Icons.Filled.CloudDownload),
    Tab("library", "Library", Icons.Filled.VideoLibrary), Tab("assistant", "Claude", Icons.Filled.AutoAwesome),
    Tab("more", "More", Icons.Filled.Menu),
)

@Composable
fun MainScaffold(onSignOut: () -> Unit) {
    val deps = LocalDeps.current
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val current = entry?.destination?.route
    UpdateCheck()
    Scaffold(
        snackbarHost = { SnackbarHost(deps.snack) },
        bottomBar = {
            NavigationBar {
                Tabs.forEach { t ->
                    NavigationBarItem(
                        selected = current?.substringBefore("/") == t.route ||
                            (t.route == "more" && current in MoreRoutes) || (t.route == "library" && current?.startsWith("show") == true),
                        onClick = { nav.navigate(t.route) { popUpTo("home") { saveState = true }; launchSingleTop = true; restoreState = true } },
                        icon = { Icon(t.icon, t.label) }, label = { Text(t.label) })
                }
            }
        }) { pad ->
        NavHost(nav, startDestination = "home", modifier = Modifier.padding(pad)) {
            composable("home") { HomeScreen(nav) }
            composable("queue") { QueueScreen() }
            composable("library") { LibraryScreen(nav) }
            composable("show/{id}") { ShowScreen(nav, it.arguments!!.getString("id")!!.toInt()) }
            composable("movie/{app}/{id}") { MovieScreen(it.arguments!!.getString("app")!!, it.arguments!!.getString("id")!!.toInt()) }
            composable("add/{kind}") { AddScreen(nav, it.arguments!!.getString("kind")!!) }
            composable("assistant") { AssistantListScreen(nav) }
            composable("chat/{id}") { ChatScreen(it.arguments!!.getString("id")!!.takeIf { id -> id != "new" }) }
            composable("more") { MoreScreen(nav) }
            composable("requests") { RequestsScreen() }
            composable("activity") { ActivityScreen() }
            composable("indexers") { IndexersScreen() }
            composable("calendar") { CalendarScreen() }
            composable("history") { HistoryScreen() }
            composable("settings") { SettingsScreen(onSignOut) }
        }
    }
}

val MoreRoutes = setOf("more", "requests", "activity", "indexers", "calendar", "history", "settings")

fun NavHostController.go(route: String) = navigate(route) { launchSingleTop = true }

// ---------------------------------------------------------------- self-update
@Composable
fun UpdateCheck() {
    val deps = LocalDeps.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var offer by remember { mutableStateOf<AppLatest?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        runCatching { deps.api.latest() }.getOrNull()?.let { if (it.versionCode > BuildConfig.VERSION_CODE) offer = it }
    }
    val o = offer ?: return
    AlertDialog(
        onDismissRequest = { offer = null },
        title = { Text("Update available") },
        text = { Text("Marquee ${o.versionName ?: o.versionCode} is ready (you have ${BuildConfig.VERSION_NAME}).\n\n${o.notes ?: ""}") },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    try {
                        val apk = deps.api.download(o.url ?: "/hub/app/marquee.apk", File(ctx.cacheDir, "updates/marquee.apk"))
                        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", apk)
                        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
                        offer = null
                    } catch (e: Exception) {
                        deps.snack.showSnackbar("Update failed: ${e.message}")
                    }
                    busy = false
                }
            }) { Text(if (busy) "Downloading…" else "Install") }
        },
        dismissButton = { TextButton(onClick = { offer = null }) { Text("Later") } })
}
