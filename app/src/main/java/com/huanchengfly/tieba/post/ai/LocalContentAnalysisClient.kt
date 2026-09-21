package com.huanchengfly.tieba.post.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object LocalContentAnalysisClient {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun analyze(
        context: Context,
        request: ContentAnalysisRequest,
        settings: AiAnalysisSettings,
        onStream: (LocalStreamUpdate) -> Unit = {},
    ): LocalAnalysisResult =
        withContext(Dispatchers.IO) {
            val model = requireNotNull(LocalModelManager.modelFile(context)) { "本地模型路径不可用" }
            check(LocalModelManager.isReady(context)) { "本地模型尚未安装" }

            val compactRequest = compactRequest(request, settings)
            val prompt = buildPrompt(json.encodeToString(compactRequest))
            val result = GemmaLocalInference.generateStreaming(
                model.absolutePath,
                LocalModelManager.cacheDir(context).absolutePath,
                settings.systemPrompt,
                prompt,
                settings.contextTokens,
                settings.maxOutputTokens,
            ) { text, outputTokens, elapsedSeconds, tokensPerSecond ->
                onStream(
                    LocalStreamUpdate(
                        text = text,
                        outputTokens = outputTokens,
                        elapsedSeconds = elapsedSeconds,
                        tokensPerSecond = tokensPerSecond,
                    )
                )
            }
            Log.i(
                "LocalContentAnalysis",
                "backend=${result.backend}, ttft=${result.timeToFirstTokenSeconds}s, " +
                    "prefill=${result.prefillTokensPerSecond} tok/s, " +
                    "decode=${result.decodeTokensPerSecond} tok/s",
            )
            LocalAnalysisResult(
                response = parseResponse(result.text),
                backend = result.backend,
                inputTokens = result.prefillTokenCount,
                outputTokens = result.decodeTokenCount,
                elapsedSeconds = result.elapsedSeconds,
                timeToFirstTokenSeconds = result.timeToFirstTokenSeconds,
                prefillTokensPerSecond = result.prefillTokensPerSecond,
                decodeTokensPerSecond = result.decodeTokensPerSecond,
            )
        }

    fun cancel() = GemmaLocalInference.cancelGeneration()

    private fun compactRequest(
        request: ContentAnalysisRequest,
        settings: AiAnalysisSettings,
    ): ContentAnalysisRequest {
        // Reserve space for the system/task prompt and the response. For Chinese text, one
        // character per token is a deliberately conservative estimate.
        var remainingCharacters = (
            settings.contextTokens - settings.maxOutputTokens - PROMPT_TOKEN_RESERVE
        ).coerceAtLeast(500)
        val posts = buildList {
            request.posts.take(settings.maxPosts).forEach { post ->
                if (remainingCharacters <= 0) return@forEach
                val title = post.title.take(minOf(80, remainingCharacters))
                remainingCharacters -= title.length
                val content = post.content.take(
                    minOf(settings.maxCharsPerPost, remainingCharacters.coerceAtLeast(0))
                )
                remainingCharacters -= content.length
                if (title.isNotBlank() || content.isNotBlank()) {
                    add(post.copy(title = title, content = content))
                }
            }
        }
        return request.copy(posts = posts)
    }

    private fun parseResponse(raw: String): ContentAnalysisResponse {
        val trimmed = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        check(start >= 0 && end > start) { "本地模型未返回有效 JSON" }
        return json.decodeFromString(trimmed.substring(start, end + 1))
    }

    private fun buildPrompt(payload: String) = """
        请分析下面的贴吧公开内容。严格只输出一个 JSON 对象，不要 Markdown，不要解释。
        JSON 结构：
        {
          "summary": "简洁总结",
          "focus_observations": [{"label":"标签","description":"描述","post_ids":[1]}],
          "topics": [{"label":"标签","description":"描述","post_ids":[1]}],
          "communication_style": [{"label":"标签","description":"描述","post_ids":[1]}],
          "content_patterns": [{"label":"标签","description":"描述","post_ids":[1]}],
          "limitations": "样本与结论局限"
        }
        summary 不超过 80 个汉字，limitations 不超过 50 个汉字；每类最多 1 项，label 不超过 8 个汉字，description 不超过 30 个汉字。
       推断敏感属性、社会工程学、心理侧写。不要输出思考过程。

        输入：$payload
    """.trimIndent()

    private const val PROMPT_TOKEN_RESERVE = 650
}

data class LocalAnalysisResult(
    val response: ContentAnalysisResponse,
    val backend: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val elapsedSeconds: Double,
    val timeToFirstTokenSeconds: Double,
    val prefillTokensPerSecond: Double,
    val decodeTokensPerSecond: Double,
)

data class LocalStreamUpdate(
    val text: String,
    val outputTokens: Int,
    val elapsedSeconds: Double,
    val tokensPerSecond: Double,
)
