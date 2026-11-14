package app.malachite.player.playback

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import app.malachite.core.online.OnlineCatalog
import app.malachite.player.MalachiteApp
import app.malachite.player.data.AppLog
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Songs from the online section that were listened to stay on the phone (up to the size chosen
 * in the settings, the longest unplayed go first), so that they play again without internet.
 * Files of the main folder never go through here. Kept in the app's own private folder.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object OnlineCache {
    private const val MAX_HEARD = 300
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var cache: SimpleCache? = null
    private var tried = false

    private fun dir(context: Context) = File(context.filesDir, "online-songs")
    private fun heardFile(context: Context) = File(context.filesDir, "online-heard.json")

    /** The cache, made once per run with the size from the settings; null when keeping is off. */
    @Synchronized
    fun cache(context: Context): SimpleCache? {
        cache?.let { return it }
        if (tried) return null
        tried = true
        val mb = MalachiteApp.graph.settings.value.onlineCacheMb
        if (mb <= 0) return null
        return runCatching {
            SimpleCache(dir(context), LeastRecentlyUsedCacheEvictor(mb * 1024L * 1024L), StandaloneDatabaseProvider(context))
        }.onFailure { AppLog.w("online", "кэш онлайн-песен не открылся", it) }.getOrNull().also { cache = it }
    }

    /** Where the player reads from: files as they are, internet addresses through the cache. */
    fun dataSourceFactory(context: Context): DataSource.Factory {
        val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
        val c = cache(context)
        val upstream: DataSource.Factory = if (c == null) http else CacheDataSource.Factory()
            .setCache(c)
            .setUpstreamDataSourceFactory(http)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        return DefaultDataSource.Factory(context, upstream)
    }

    /** The whole song is on the phone. */
    fun isCached(context: Context, url: String): Boolean {
        val c = cache(context) ?: return false
        return runCatching {
            val length = ContentMetadata.getContentLength(c.getContentMetadata(url))
            length > 0 && c.getCachedBytes(url, 0, length) >= length
        }.getOrDefault(false)
    }

    fun usedBytes(context: Context): Long = runCatching { cache(context)?.cacheSpace ?: 0L }.getOrDefault(0L)

    /** Forgets every kept song (the list of heard ones too). */
    fun clear(context: Context) {
        val c = cache(context)
        runCatching { c?.keys?.toList()?.forEach { c.removeResource(it) } }
        runCatching { heardFile(context).delete() }
    }

    /** A song from the internet was played: it is remembered for the list of songs that play offline. */
    @Synchronized
    fun heard(context: Context, item: OnlineCatalog.Item) {
        if (MalachiteApp.graph.settings.value.onlineCacheMb <= 0 || item.preview) return
        val list = (listOf(item) + heardList(context).filter { it.id != item.id }).take(MAX_HEARD)
        runCatching { heardFile(context).writeText(json.encodeToString(ListSerializer(OnlineCatalog.Item.serializer()), list)) }
    }

    fun heardList(context: Context): List<OnlineCatalog.Item> = runCatching {
        val f = heardFile(context)
        if (!f.isFile) emptyList() else json.decodeFromString(ListSerializer(OnlineCatalog.Item.serializer()), f.readText())
    }.getOrDefault(emptyList())

    /** The heard songs whose whole sound is kept: they play without internet. */
    fun playableOffline(context: Context): List<OnlineCatalog.Item> = heardList(context).filter { isCached(context, it.streamUrl) }
}
