package com.zs.gallery.viewer

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri

object VideoRestriction {
    const val DEFAULT_MAX_MS = 5 * 60 * 1000L
    const val PREFS = "gallery_guard"

    data class Result(val allowed: Boolean, val durationMs: Long, val limitMs: Long)

    fun maxDurationMs(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong("max_ms", DEFAULT_MAX_MS)
            .coerceAtLeast(60_000L)

    fun check(context: Context, uri: Uri): Result {
        val limit = maxDurationMs(context)
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val raw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val duration = raw?.toLongOrNull() ?: -1L
            Result(duration > 0L && duration <= limit, duration, limit)
        } catch (_: Exception) {
            Result(false, -1L, limit)
        } finally {
            runCatching { retriever.release() }
        }
    }

    fun format(ms: Long): String {
        if (ms <= 0L) return "the configured limit"
        val totalSeconds = ms / 1000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return if (seconds == 0L) {
            minutes.toString() + " minute" + if (minutes == 1L) "" else "s"
        } else {
            minutes.toString() + ":" + seconds.toString().padStart(2, '0')
        }
    }
}
