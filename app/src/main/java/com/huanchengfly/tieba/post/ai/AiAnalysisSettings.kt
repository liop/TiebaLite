package com.huanchengfly.tieba.post.ai

import android.content.Context

data class AiAnalysisSettings(
    val provider: AnalysisSource = AnalysisSource.REMOTE,
    val maxPosts: Int = DEFAULT_MAX_POSTS,
    val maxCharsPerPost: Int = DEFAULT_MAX_CHARS_PER_POST,
    val contextTokens: Int = DEFAULT_CONTEXT_TOKENS,
    val maxOutputTokens: Int = DEFAULT_MAX_OUTPUT_TOKENS,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
) {
    val cacheKey: String
        get() = listOf(
            provider.name,
            maxPosts,
            maxCharsPerPost,
            contextTokens,
            maxOutputTokens,
            systemPrompt.hashCode(),
        ).joinToString(":")

    companion object {
        const val DEFAULT_MAX_POSTS = 16
        const val DEFAULT_MAX_CHARS_PER_POST = 300
        const val DEFAULT_CONTEXT_TOKENS = 4096
        const val DEFAULT_MAX_OUTPUT_TOKENS = 900
        const val DEFAULT_SYSTEM_PROMPT =
            "你是中文内容分析助手。充分利用给定的公开内容，直接、具体地完成分析任务，并严格遵守要求的输出格式。"
    }
}

object AiAnalysisSettingsStore {
    private const val PREFERENCES_NAME = "ai_analysis_settings"
    private const val KEY_PROVIDER = "provider"
    private const val KEY_MAX_POSTS = "max_posts"
    private const val KEY_MAX_CHARS_PER_POST = "max_chars_per_post"
    private const val KEY_CONTEXT_TOKENS = "context_tokens"
    private const val KEY_MAX_OUTPUT_TOKENS = "max_output_tokens"
    private const val KEY_SYSTEM_PROMPT = "system_prompt"

    fun load(context: Context): AiAnalysisSettings {
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val contextTokens = preferences.getInt(
            KEY_CONTEXT_TOKENS,
            AiAnalysisSettings.DEFAULT_CONTEXT_TOKENS,
        ).takeIf { it in setOf(2048, 4096, 8192) }
            ?: AiAnalysisSettings.DEFAULT_CONTEXT_TOKENS
        return AiAnalysisSettings(
            provider = preferences.getString(KEY_PROVIDER, AnalysisSource.REMOTE.name)
                ?.let { value ->
                    runCatching { AnalysisSource.valueOf(value) }.getOrDefault(AnalysisSource.REMOTE)
                } ?: AnalysisSource.REMOTE,
            maxPosts = preferences.getInt(KEY_MAX_POSTS, AiAnalysisSettings.DEFAULT_MAX_POSTS)
                .coerceIn(4, 40),
            maxCharsPerPost = preferences.getInt(
                KEY_MAX_CHARS_PER_POST,
                AiAnalysisSettings.DEFAULT_MAX_CHARS_PER_POST,
            ).coerceIn(100, 1000),
            contextTokens = contextTokens,
            maxOutputTokens = preferences.getInt(
                KEY_MAX_OUTPUT_TOKENS,
                AiAnalysisSettings.DEFAULT_MAX_OUTPUT_TOKENS,
            ).coerceIn(256, minOf(2048, contextTokens - 768)),
            systemPrompt = preferences.getString(
                KEY_SYSTEM_PROMPT,
                AiAnalysisSettings.DEFAULT_SYSTEM_PROMPT,
            ).orEmpty().ifBlank { AiAnalysisSettings.DEFAULT_SYSTEM_PROMPT },
        )
    }

    fun save(context: Context, settings: AiAnalysisSettings) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PROVIDER, settings.provider.name)
            .putInt(KEY_MAX_POSTS, settings.maxPosts)
            .putInt(KEY_MAX_CHARS_PER_POST, settings.maxCharsPerPost)
            .putInt(KEY_CONTEXT_TOKENS, settings.contextTokens)
            .putInt(KEY_MAX_OUTPUT_TOKENS, settings.maxOutputTokens)
            .putString(KEY_SYSTEM_PROMPT, settings.systemPrompt)
            .apply()
    }
}
