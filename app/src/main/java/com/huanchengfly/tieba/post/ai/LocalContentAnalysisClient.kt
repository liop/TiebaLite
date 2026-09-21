package com.huanchengfly.tieba.post.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object LocalContentAnalysisClient {
    private const val MAX_POSTS = 6
    private const val MAX_CONTENT_LENGTH = 120

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun analyze(context: Context, request: ContentAnalysisRequest): LocalAnalysisResult =
        withContext(Dispatchers.IO) {
            val model = requireNotNull(LocalModelManager.modelFile(context)) { "本地模型路径不可用" }
            check(LocalModelManager.isReady(context)) { "本地模型尚未安装" }

            val compactRequest = request.copy(
                posts = request.posts.take(MAX_POSTS).map { post ->
                    post.copy(
                        title = post.title.take(80),
                        content = post.content.take(MAX_CONTENT_LENGTH),
                    )
                }
            )
            val prompt = buildPrompt(json.encodeToString(compactRequest))
            val result = GemmaLocalInference.generate(
                model.absolutePath,
                LocalModelManager.cacheDir(context).absolutePath,
                SYSTEM_INSTRUCTION,
                prompt,
            )
            Log.i(
                "LocalContentAnalysis",
                "backend=${result.backend}, ttft=${result.timeToFirstTokenSeconds}s, " +
                    "prefill=${result.prefillTokensPerSecond} tok/s, " +
                    "decode=${result.decodeTokensPerSecond} tok/s",
            )
            LocalAnalysisResult(
                response = parseResponse(result.text),
                backend = result.backend,
                timeToFirstTokenSeconds = result.timeToFirstTokenSeconds,
                prefillTokensPerSecond = result.prefillTokensPerSecond,
                decodeTokensPerSecond = result.decodeTokensPerSecond,
            )
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
        只引用输入中真实存在的 post id，不得推断敏感属性、人格诊断或心理疾病。不要输出思考过程。

        输入：$payload
    """.trimIndent()

    private const val SYSTEM_INSTRUCTION =
        "你是谨慎的中文内容分析助手，只基于给定公开文本归纳可观察事实，并严格遵守输出格式。"
}

data class LocalAnalysisResult(
    val response: ContentAnalysisResponse,
    val backend: String,
    val timeToFirstTokenSeconds: Double,
    val prefillTokensPerSecond: Double,
    val decodeTokensPerSecond: Double,
)
