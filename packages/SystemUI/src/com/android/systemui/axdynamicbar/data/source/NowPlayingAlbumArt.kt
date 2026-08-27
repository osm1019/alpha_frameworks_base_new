package com.android.systemui.axdynamicbar.data.source

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.net.Uri
import android.text.TextUtils
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.json.JSONObject

/**
 * Cover for a Now Playing hit. ASI's SHOW almost never carries ALBUM_ART_URI on
 * non-Pixel, so resolve URI → ASI/NP notification largeIcon → iTunes Search.
 *
 * Ported from the dodge Now Playing path (not the pixel-framework pill).
 */
internal object NowPlayingAlbumArt {
    private const val TAG = "NowPlayingAlbumArt"
    private const val PKG_ASI = "com.google.android.as"
    private const val PKG_NOW_PLAYING = "com.google.android.apps.pixel.nowplaying"
    private const val CHANNEL_AMBIENT_MUSIC = "ambientmusic"
    /** The card cover is 92dp; at ~2.8 density that needs 256px to stay sharp. */
    private const val ART_MAX_PX = 256
    private const val ITUNES_SEARCH_URL =
        "https://itunes.apple.com/search?term=%s&media=music&entity=song&limit=1"
    private const val CONNECT_MS = 4000
    private const val READ_MS = 6000
    private const val USER_AGENT = "AlphaDroid-SystemUI-NowPlaying"
    private const val CACHE_DIR = "now_playing_art"
    private const val CACHE_MAX_FILES = 64
    private const val CACHE_MAX_BYTES = 8L * 1024 * 1024

    fun load(
        context: Context,
        albumArtUri: String?,
        title: String,
        artist: String,
    ): Drawable? {
        if (isStatusTitle(title)) return null
        var persist = false
        var bitmap = decodeUri(context, albumArtUri)
        if (bitmap != null) persist = true
        // Disk before the notification sweep: it is the cheaper lookup and it is the only one
        // that still answers once ASI has moved on to the next song.
        if (bitmap == null) bitmap = fromDisk(context, title, artist)
        if (bitmap == null) {
            bitmap = fromActiveNotification(context)
            if (bitmap != null) persist = true
        }
        if (bitmap == null) {
            bitmap = fromItunes(title, artist)
            if (bitmap != null) persist = true
        }
        if (bitmap == null || bitmap.isRecycled) return null
        if (persist) saveToDisk(context, title, artist, bitmap)
        return BitmapDrawable(context.resources, bitmap)
    }

    /**
     * The provider filters these out before a match is ever published, so this is a backstop
     * rather than a path: it stops a status string being spent on a network lookup if that ever
     * changes.
     */
    private fun isStatusTitle(title: String): Boolean {
        val lower = title.trim().lowercase()
        if (lower.isEmpty()) return true
        return lower == "unknown song" ||
            lower == "request failed" ||
            lower.startsWith("service busy") ||
            lower.startsWith("identifying") ||
            lower == "see what's playing" ||
            lower.startsWith("unable to identify") ||
            lower == "no connection" ||
            lower == "busy"
    }

    private fun decodeUri(context: Context, uriString: String?): Bitmap? {
        if (uriString.isNullOrBlank()) return null
        val uri = try {
            Uri.parse(uriString.trim())
        } catch (_: RuntimeException) {
            return null
        }
        val scheme = uri.scheme
        if ("http".equals(scheme, ignoreCase = true) || "https".equals(scheme, ignoreCase = true)) {
            return downloadBitmap(uri.toString())
        }
        try {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
                decoder.setTargetSize(
                    minOf(info.size.width, ART_MAX_PX),
                    minOf(info.size.height, ART_MAX_PX),
                )
            }
            if (decoded != null) {
                Log.i(TAG, "loaded album art via ContentResolver")
                return decoded
            }
        } catch (e: Exception) {
            Log.w(TAG, "ContentResolver art decode failed: $uri", e)
        }
        return null
    }

    private fun fromActiveNotification(context: Context): Bitmap? {
        return try {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return null
            val notifs = nm.activeNotifications ?: return null
            var best: Bitmap? = null
            for (sbn in notifs) {
                val pkg = sbn?.packageName ?: continue
                if (pkg != PKG_ASI && pkg != PKG_NOW_PLAYING) continue
                val n = sbn.notification ?: continue
                val ambient =
                    n.channelId?.contains(CHANNEL_AMBIENT_MUSIC, ignoreCase = true) == true
                val bmp = iconToBitmap(context, n.getLargeIcon())
                    ?: extraIconBitmap(context, n)
                if (bmp != null) {
                    if (ambient) return scaleIfNeeded(bmp)
                    if (best == null) best = bmp
                }
            }
            best?.let { scaleIfNeeded(it) }
        } catch (e: RuntimeException) {
            Log.w(TAG, "notif art fallback failed", e)
            null
        }
    }

    private fun extraIconBitmap(context: Context, n: Notification): Bitmap? {
        val extras = n.extras ?: return null
        fun from(value: Any?): Bitmap? = when (value) {
            is Bitmap -> value
            is Icon -> iconToBitmap(context, value)
            else -> null
        }
        return from(extras.get(Notification.EXTRA_LARGE_ICON))
            ?: from(extras.get(Notification.EXTRA_LARGE_ICON_BIG))
    }

    private fun fromItunes(title: String, artist: String): Bitmap? {
        val term = listOf(title, artist).map { it.trim() }.filter { it.isNotEmpty() }
            .joinToString(" ")
            .replace('•', ' ')
            .trim()
        if (term.isEmpty() || artist.isBlank()) return null
        var conn: HttpURLConnection? = null
        return try {
            val encoded = URLEncoder.encode(term, StandardCharsets.UTF_8.name())
            conn = (URL(ITUNES_SEARCH_URL.format(encoded)).openConnection() as HttpURLConnection)
                .apply {
                    connectTimeout = CONNECT_MS
                    readTimeout = READ_MS
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", USER_AGENT)
                }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            val json = BufferedReader(InputStreamReader(conn.inputStream, StandardCharsets.UTF_8))
                .use { it.readText() }
            val results = JSONObject(json).optJSONArray("results") ?: return null
            if (results.length() == 0) return null
            val artUrl = results.getJSONObject(0).optString("artworkUrl100", null)
            if (TextUtils.isEmpty(artUrl)) return null
            val bmp = downloadBitmap(artUrl)
            if (bmp != null) Log.i(TAG, "iTunes album art for: $term")
            bmp
        } catch (e: Exception) {
            Log.w(TAG, "iTunes album art lookup failed for: $term", e)
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun downloadBitmap(urlString: String): Bitmap? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_MS
                readTimeout = READ_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            conn.inputStream.use { stream ->
                BitmapFactory.decodeStream(stream)?.let { scaleIfNeeded(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "downloadBitmap failed: $urlString", e)
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun iconToBitmap(context: Context, icon: Icon?): Bitmap? {
        if (icon == null) return null
        if (icon.type == Icon.TYPE_RESOURCE) return null
        return try {
            val d = icon.loadDrawable(context) ?: return null
            val w = (if (d.intrinsicWidth > 0) d.intrinsicWidth else ART_MAX_PX)
                .coerceIn(1, ART_MAX_PX * 2)
            val h = (if (d.intrinsicHeight > 0) d.intrinsicHeight else ART_MAX_PX)
                .coerceIn(1, ART_MAX_PX * 2)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            d.setBounds(0, 0, canvas.width, canvas.height)
            d.draw(canvas)
            bmp
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun scaleIfNeeded(src: Bitmap): Bitmap {
        if (src.width <= ART_MAX_PX && src.height <= ART_MAX_PX) return src
        val scale = minOf(ART_MAX_PX.toFloat() / src.width, ART_MAX_PX.toFloat() / src.height)
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    private fun cacheDir(context: Context): File =
        File(context.cacheDir, CACHE_DIR).also { it.mkdirs() }

    private fun cacheFile(context: Context, title: String, artist: String): File {
        val key = "${title.trim().lowercase()}\u0000${artist.trim().lowercase()}"
        val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray(StandardCharsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }.take(16)
        return File(cacheDir(context), "$hex.png")
    }

    private fun fromDisk(context: Context, title: String, artist: String): Bitmap? {
        val file = cacheFile(context, title, artist)
        if (!file.isFile || file.length() == 0L) {
            Log.d(TAG, "disk art miss for: $title $artist (${file.name})")
            return null
        }
        return try {
            BitmapFactory.decodeFile(file.absolutePath)?.let { scaleIfNeeded(it) }?.also {
                // The only lookup with no other trace; without this a cache hit and a total
                // miss are indistinguishable in a log.
                Log.i(TAG, "disk art hit for: $title $artist (${file.name})")
            }
        } catch (e: Exception) {
            Log.w(TAG, "disk art decode failed", e)
            null
        }
    }

    private fun saveToDisk(context: Context, title: String, artist: String, bitmap: Bitmap) {
        try {
            val file = cacheFile(context, title, artist)
            file.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
            }
            pruneCache(cacheDir(context))
        } catch (e: Exception) {
            Log.w(TAG, "disk art save failed", e)
        }
    }

    private fun pruneCache(dir: File) {
        val files = dir.listFiles()?.sortedBy { it.lastModified() }?.toMutableList() ?: return
        var total = files.sumOf { it.length() }
        while (files.size > CACHE_MAX_FILES || total > CACHE_MAX_BYTES) {
            val file = files.removeAt(0)
            val len = file.length()
            if (!file.delete()) break
            total -= len
        }
    }
}
