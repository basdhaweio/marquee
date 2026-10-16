package com.basdhaweio.marquee

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

val Gold = Color(0xFFE0B04A)
val Bad = Color(0xFFFF6B6B)
val Good = Color(0xFF4CAF50)
val AppColors = mapOf("sonarr" to Color(0xFF35C5F4), "radarr" to Color(0xFFFFC230), "radarr4k" to Color(0xFFB57CFF),
    "lidarr" to Color(0xFF1DB954), "prowlarr" to Color(0xFFE66000), "seerr" to Color(0xFF6366F1), "tautulli" to Color(0xFFE5A00D))

private val Scheme = darkColorScheme(
    primary = Gold, onPrimary = Color(0xFF1B1405), secondary = Gold,
    secondaryContainer = Color(0xFF3D3016), onSecondaryContainer = Gold,   // nav-bar selection pill
    primaryContainer = Color(0xFF3D3016), onPrimaryContainer = Gold,       // floating action buttons
    background = Color(0xFF121212), surface = Color(0xFF121212),
    surfaceVariant = Color(0xFF1E1E1E), surfaceContainer = Color(0xFF1A1A1A), surfaceContainerHigh = Color(0xFF222222),
    onBackground = Color(0xFFEDEDED), onSurface = Color(0xFFEDEDED), onSurfaceVariant = Color(0xFFB0B0B0),
    error = Color(0xFFFF6B6B),
)

@Composable
fun MarqueeTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = Scheme, content = content)

/** App-wide services for composables. */
class Deps(val session: Session, val api: Api, val snack: SnackbarHostState)
val LocalDeps = compositionLocalOf<Deps> { error("no deps") }

/** Load data, show a spinner / error with retry, and support pull-to-refresh. With [autoRefreshMs] it also reloads
 *  quietly (no spinner, last good data kept on a failed refresh) while the screen is showing. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> Loadable(key: Any? = Unit, load: suspend () -> T, autoRefreshMs: Long? = null,
                 content: @Composable (data: T, reload: () -> Unit) -> Unit) {
    var tick by remember { mutableIntStateOf(0) }
    var quiet by remember { mutableStateOf(false) }
    var data by remember(key) { mutableStateOf<T?>(null) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(key, tick) {
        val silent = quiet; quiet = false
        refreshing = data != null && !silent
        runCatching { load() }.onSuccess { data = it; error = null }
            .onFailure { if (!silent || data == null) error = it.message ?: it.toString() }
        refreshing = false
    }
    if (autoRefreshMs != null) LaunchedEffect(key) {
        while (true) { delay(autoRefreshMs); quiet = true; tick++ }
    }
    val reload: () -> Unit = { tick++ }
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = reload, modifier = Modifier.fillMaxSize()) {
        val d = data
        when {
            d != null -> content(d, reload)
            error != null -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Couldn't load", style = MaterialTheme.typography.titleMedium)
                Text(error!!, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
                Button(onClick = reload) { Text("Retry") }
            }
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
    }
}

@Composable
fun AppTag(app: String, label: String = app) {
    val c = AppColors[app] ?: Gold
    Text(label, color = c, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(c.copy(alpha = 0.15f)).padding(horizontal = 6.dp, vertical = 1.dp))
}

@Composable
fun Poster(url: String?, w: Dp = 46.dp) {
    val mod = Modifier.width(w).height(w * 1.5f).clip(RoundedCornerShape(6.dp)).background(Color(0xFF2A2A2A))
    if (url != null) AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = mod)
    else Box(mod)
}

@Composable
fun SectionTitle(text: String) =
    Text(text, style = MaterialTheme.typography.titleSmall, color = Gold, modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 6.dp))

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) =
    Card(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(12.dp)) { content() }
    }

@Composable
fun Muted(text: String, maxLines: Int = 2) =
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
        maxLines = maxLines, overflow = TextOverflow.Ellipsis)

@Composable
fun MediaRow(title: String, sub: String?, poster: String?, trailing: (@Composable () -> Unit)? = null,
             extra: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Poster(poster)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!sub.isNullOrBlank()) Muted(sub)
            extra?.invoke()
        }
        trailing?.invoke()
    }
}

@Composable
fun Empty(text: String) = Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Muted(text) }

val ListPadding = PaddingValues(bottom = 24.dp)

// ---------------------------------------------------------------- formatting
fun bytes(b: Long): String = when {
    b >= 1L shl 40 -> "%.1f TB".format(b / (1L shl 40).toDouble())
    b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.0f MB".format(b / (1L shl 20).toDouble())
    else -> "$b B"
}

fun ago(iso: String?): String = runCatching {
    val t = OffsetDateTime.parse(iso).toInstant()
    val d = Duration.between(t, Instant.now())
    when {
        d.isNegative -> "in " + span(d.negated())
        else -> span(d) + " ago"
    }
}.getOrDefault(iso ?: "")

fun agoEpoch(sec: Long?): String = if (sec == null) "" else ago(Instant.ofEpochSecond(sec).atZone(ZoneId.systemDefault()).toOffsetDateTime().toString())

private fun span(d: Duration) = when {
    d.toMinutes() < 60 -> "${d.toMinutes()}m"
    d.toHours() < 48 -> "${d.toHours()}h"
    else -> "${d.toDays()}d"
}

fun day(iso: String?): String = runCatching {
    OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE MMM d, h:mm a"))
}.getOrDefault(iso ?: "")

val BadgeSize = 8.dp
@Composable fun Dot(c: Color) = Box(Modifier.size(BadgeSize).clip(RoundedCornerShape(50)).background(c))
