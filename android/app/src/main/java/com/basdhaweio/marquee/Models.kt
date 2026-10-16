package com.basdhaweio.marquee

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Shapes mirror Marquee's /hub/api JSON. Everything is nullable/defaulted: one service being down must
// never stop a screen from rendering.

@Serializable data class Health(val type: String? = null, val message: String? = null)
@Serializable data class AppSummary(
    val app: String = "", val label: String = "", val link: String? = null,
    val health: List<Health> = emptyList(), val queue: Int? = null, val missing: Int? = null,
)
@Serializable data class Disk(val path: String? = null, val label: String? = null, val free: Long = 0, val total: Long = 0)
@Serializable data class Playing(
    val user: String? = null, val title: String? = null, val progress: String? = null, val state: String? = null,
    val decision: String? = null, val quality: String? = null, val player: String? = null,
)
@Serializable data class Overview(
    val apps: List<AppSummary> = emptyList(), val disks: List<Disk> = emptyList(),
    val playing: List<Playing> = emptyList(), val pendingRequests: Int = 0,
)

@Serializable data class QueueItem(
    val app: String = "", val label: String = "", val id: Int = 0, val title: String? = null, val sub: String? = null,
    val poster: String? = null, val release: String? = null, val quality: String? = null, val progress: Double = 0.0,
    val size: Long = 0, val timeleft: String? = null, val status: String? = null, val state: String? = null,
    val health: String? = null, val messages: List<String> = emptyList(), val client: String? = null,
    val protocol: String? = null,
)

@Serializable data class Show(
    val id: Int = 0, val title: String? = null, val year: Int? = null, val status: String? = null,
    val network: String? = null, val monitored: Boolean = false, val episodes: Int = 0, val episodesTotal: Int = 0,
    val sizeOnDisk: Long = 0, val poster: String? = null, val tvdbId: Int? = null, val overview: String? = null,
    val seasons: List<Season> = emptyList(),
)
@Serializable data class Season(val season: Int = 0, val monitored: Boolean? = null, val episodes: List<Episode> = emptyList())
@Serializable data class Episode(
    val id: Int = 0, val season: Int = 0, val episode: Int = 0, val title: String? = null, val airDate: String? = null,
    val monitored: Boolean = false, val hasFile: Boolean = false, val quality: String? = null,
    val languages: List<String> = emptyList(), val releaseGroup: String? = null,
)
@Serializable data class Movie(
    val app: String = "radarr", val label: String = "", val id: Int = 0, val title: String? = null, val year: Int? = null,
    val monitored: Boolean = false, val hasFile: Boolean = false, val status: String? = null, val quality: String? = null,
    val sizeOnDisk: Long = 0, val poster: String? = null, val tmdbId: Int? = null, val overview: String? = null,
    val runtime: Int? = null,
)
@Serializable data class LookupItem(
    val kind: String = "", val title: String? = null, val year: Int? = null, val tvdbId: Int? = null, val tmdbId: Int? = null,
    val network: String? = null, val status: String? = null, val overview: String? = null, val inLibrary: Boolean = false,
    val libraryId: Int? = null, val in4k: Boolean = false, val poster: String? = null,
)
@Serializable data class Release(
    val guid: String = "", val indexerId: Int = 0, val title: String? = null, val indexer: String? = null,
    val quality: String? = null, val size: Long = 0, val seeders: Int? = null, val protocol: String? = null,
    val age: Double? = null, val approved: Boolean = false, val rejections: List<String> = emptyList(),
)
@Serializable data class HistoryItem(
    @SerialName("when") val time: String? = null, val event: String? = null, val title: String? = null,
    val sub: String? = null, val release: String? = null, val quality: String? = null,
)

@Serializable data class RequestItem(
    val id: Int = 0, val type: String? = null, val status: String? = null, val mediaStatus: String? = null,
    val is4k: Boolean = false, val by: String? = null, val created: String? = null, val seasons: List<Int?> = emptyList(),
    val title: String? = null, val year: String? = null, val poster: String? = null,
)
@Serializable data class WatchHist(
    val user: String? = null, val title: String? = null, @SerialName("when") val time: Long? = null,
    val watched: Double? = null, val duration: Long? = null,
)
@Serializable data class Activity(val playing: List<Playing> = emptyList(), val history: List<WatchHist> = emptyList())
@Serializable data class StatRow(val title: String? = null, val plays: Int? = null, val duration: Long? = null)

@Serializable data class Indexer(
    val id: Int = 0, val name: String? = null, val enabled: Boolean = false, val protocol: String? = null,
    val priority: Int? = null, val privacy: String? = null, val failing: Boolean = false, val disabledTill: String? = null,
)
@Serializable data class IndexerResult(
    val title: String? = null, val indexer: String? = null, val size: Long = 0, val seeders: Int? = null,
    val protocol: String? = null, val age: Int? = null,
)
@Serializable data class CalendarItem(
    val app: String = "", val label: String = "", @SerialName("when") val time: String? = null, val title: String? = null,
    val sub: String? = null, val hasFile: Boolean? = null, val monitored: Boolean? = null, val poster: String? = null,
)

@Serializable data class ConvMeta(val id: String = "", val title: String = "", val updated: Double? = null, val pending: Boolean = false)
@Serializable data class ConvList(val enabled: Boolean = false, val model: String? = null, val items: List<ConvMeta> = emptyList())
@Serializable data class ChatMsg(val role: String = "", val text: String = "")
@Serializable data class PendingAction(val id: String = "", val tool: String = "", val summary: String = "")
@Serializable data class Conversation(
    val id: String = "", val title: String = "", val display: List<ChatMsg> = emptyList(),
    val pending: List<PendingAction> = emptyList(), val enabled: Boolean = true,
)

@Serializable data class Pin(val id: Long = 0, val authUrl: String = "", val code: String? = null)
@Serializable data class PinStatus(val status: String = "", val user: String? = null, val token: String? = null)
@Serializable data class AppLatest(val versionCode: Int = 0, val versionName: String? = null, val notes: String? = null, val url: String? = null)

@Serializable data class Items<T>(val items: List<T> = emptyList())
