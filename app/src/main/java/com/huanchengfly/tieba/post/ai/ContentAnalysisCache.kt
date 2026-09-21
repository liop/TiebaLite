package com.huanchengfly.tieba.post.ai

import android.content.Context
import com.huanchengfly.tieba.post.App
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class CachedContentAnalysis(
    val response: ContentAnalysisResponse,
    val cachedAt: Long,
    val source: AnalysisSource,
)

object ContentAnalysisCache {
    private const val PREFERENCES_NAME = "ai_content_analysis_cache"
    private const val CACHE_VERSION = "v3"
    private const val MAX_ENTRIES = 100
    private const val CACHE_TTL_MILLIS = 7L * 24 * 60 * 60 * 1000

    private val json = Json { ignoreUnknownKeys = true }
    private val preferences by lazy {
        App.INSTANCE.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }

    fun get(
        uid: Long,
        focusPost: PublicPostSnapshot?,
        settingsKey: String = "default",
    ): CachedContentAnalysis? {
        val key = key(uid, focusPost, settingsKey)
        val entry = preferences.getString(key, null)
            ?.let { serialized -> runCatching { json.decodeFromString<CacheEntry>(serialized) }.getOrNull() }
            ?: return null
        if (System.currentTimeMillis() - entry.cachedAt > CACHE_TTL_MILLIS) {
            preferences.edit().remove(key).apply()
            return null
        }
        return CachedContentAnalysis(entry.response, entry.cachedAt, entry.source)
    }

    fun put(
        uid: Long,
        focusPost: PublicPostSnapshot?,
        response: ContentAnalysisResponse,
        source: AnalysisSource,
        settingsKey: String = "default",
    ) {
        val entry = CacheEntry(
            cachedAt = System.currentTimeMillis(),
            response = response,
            source = source,
        )
        preferences.edit()
            .putString(key(uid, focusPost, settingsKey), json.encodeToString(CacheEntry.serializer(), entry))
            .apply()
        prune()
    }

    private fun prune() {
        val entries = preferences.all.mapNotNull { (key, value) ->
            val serialized = value as? String ?: return@mapNotNull null
            val cachedAt = runCatching {
                json.decodeFromString<CacheEntry>(serialized).cachedAt
            }.getOrNull() ?: Long.MIN_VALUE
            key to cachedAt
        }
        if (entries.size <= MAX_ENTRIES) return

        val editor = preferences.edit()
        entries.sortedBy { it.second }
            .take(entries.size - MAX_ENTRIES)
            .forEach { (key, _) -> editor.remove(key) }
        editor.apply()
    }

    private fun key(uid: Long, focusPost: PublicPostSnapshot?, settingsKey: String): String = if (focusPost == null) {
        "$CACHE_VERSION:user:$uid:$settingsKey"
    } else {
        "$CACHE_VERSION:post:$uid:${focusPost.kind}:${focusPost.id}:$settingsKey"
    }

    @Serializable
    private data class CacheEntry(
        val cachedAt: Long,
        val response: ContentAnalysisResponse,
        val source: AnalysisSource,
    )
}
