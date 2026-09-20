package com.huanchengfly.tieba.post.ai

import com.huanchengfly.tieba.post.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

object ContentAnalysisClient {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    suspend fun analyze(payload: ContentAnalysisRequest): ContentAnalysisResponse =
        withContext(Dispatchers.IO) {
            val baseUrl = BuildConfig.AI_ANALYSIS_BASE_URL.trim().trimEnd('/')
            require(baseUrl.startsWith("https://")) {
                "AI_ANALYSIS_BASE_URL_NOT_CONFIGURED"
            }
            val request = Request.Builder()
                .url("$baseUrl/v1/content-analysis")
                .post(
                    json.encodeToString(payload)
                        .toRequestBody("application/json; charset=utf-8".toMediaType())
                )
                .apply {
                    BuildConfig.AI_ANALYSIS_TOKEN.takeIf { it.isNotBlank() }?.let {
                        header("Authorization", "Bearer $it")
                    }
                }
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                check(response.isSuccessful) {
                    "AI service returned ${response.code}: ${body.take(240)}"
                }
                json.decodeFromString<ContentAnalysisResponse>(body)
            }
        }
}
