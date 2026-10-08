package com.walkbuddy.ui.components

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.walkbuddy.domain.SlippyTiles
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The OPTIONAL OpenStreetMap tile layer. Off by default: fetching tiles tells the tile server (and your network) roughly which
 * area you are looking at. Follows the OSM tile usage policy: an identifying User-Agent, a small in-memory cache, at most two
 * requests at a time, and no bulk downloading. Attribution is drawn by the map whenever this layer is on.
 */
object TileLoader {
    private const val USER_AGENT = "WalkBuddy/1.3 (Android; +https://github.com/Dante3750/walk-buddy)"
    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
    }
    private val cache = LruCache<String, ImageBitmap>(96)
    private val failedAt = HashMap<String, Long>()
    private val gate = Semaphore(2)

    fun key(z: Int, x: Int, y: Int) = "$z/$x/$y"

    fun cached(key: String): ImageBitmap? = synchronized(cache) { cache.get(key) }

    /** Downloads one tile (or returns the cached one). Null on failure; a failed tile is not retried for 30 seconds. */
    suspend fun load(z: Int, x: Int, y: Int): ImageBitmap? {
        val k = key(z, x, y)
        cached(k)?.let { return it }
        val failed = synchronized(failedAt) { failedAt[k] }
        if (failed != null && System.currentTimeMillis() - failed < 30_000) return null
        return gate.withPermit {
            cached(k) ?: withContext(Dispatchers.IO) {
                runCatching {
                    val req = Request.Builder().url(SlippyTiles.url(z, x, y)).header("User-Agent", USER_AGENT).build()
                    client.newCall(req).execute().use { res ->
                        val bytes = if (res.isSuccessful) res.body?.bytes() else null
                        bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }?.asImageBitmap()
                    }
                }.getOrNull()
            }.also { img ->
                if (img != null) synchronized(cache) { cache.put(k, img) } else synchronized(failedAt) { failedAt[k] = System.currentTimeMillis() }
            }
        }
    }
}
