package com.huanchengfly.tieba.post.ui.page.user.analysis

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.huanchengfly.tieba.post.ai.AnalysisSource
import com.huanchengfly.tieba.post.ai.AnalysisMetrics
import com.huanchengfly.tieba.post.ai.AiAnalysisSettings
import com.huanchengfly.tieba.post.ai.AiAnalysisSettingsStore
import com.huanchengfly.tieba.post.ai.ContentAnalysisClient
import com.huanchengfly.tieba.post.ai.ContentAnalysisCache
import com.huanchengfly.tieba.post.ai.ContentAnalysisRequest
import com.huanchengfly.tieba.post.ai.ContentAnalysisResponse
import com.huanchengfly.tieba.post.ai.LocalContentAnalysisClient
import com.huanchengfly.tieba.post.ai.LocalModelRuntime
import com.huanchengfly.tieba.post.ai.LocalModelManager
import com.huanchengfly.tieba.post.ai.LocalModelState
import com.huanchengfly.tieba.post.ai.PublicPostSnapshot
import com.huanchengfly.tieba.post.ai.PublicUserSnapshot
import com.huanchengfly.tieba.post.api.TiebaApi
import com.huanchengfly.tieba.post.api.models.protos.abstractText
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class UserContentAnalysisViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private companion object {
        const val TAG = "UserContentAnalysis"
    }

    private val _state = MutableStateFlow<UserContentAnalysisState>(UserContentAnalysisState.Idle)
    val state: StateFlow<UserContentAnalysisState> = _state.asStateFlow()
    private val _localModelState = MutableStateFlow<LocalModelState>(LocalModelState.NotInstalled)
    val localModelState: StateFlow<LocalModelState> = _localModelState.asStateFlow()
    private val _settings = MutableStateFlow(AiAnalysisSettingsStore.load(context))
    val settings: StateFlow<AiAnalysisSettings> = _settings.asStateFlow()
    private var analysisJob: Job? = null

    private var modelStatusJob: Job? = null

    init {
        refreshLocalModelStatus()
    }

    // Only downloads need polling. Never query DownloadManager or the filesystem on Main.
    fun refreshLocalModelStatus() {
        modelStatusJob?.cancel()
        modelStatusJob = viewModelScope.launch {
            do {
                _localModelState.value = withContext(Dispatchers.IO) {
                    LocalModelManager.status(context)
                }
                if (_localModelState.value !is LocalModelState.Downloading) break
                delay(1_000L)
            } while (isActive)
        }
    }

    fun downloadLocalModel() {
        modelStatusJob?.cancel()
        viewModelScope.launch {
            _localModelState.value = runCatching {
                withContext(Dispatchers.IO) { LocalModelManager.enqueueDownload(context) }
                refreshLocalModelStatus()
                LocalModelState.Downloading(0, LocalModelManager.MODEL_SIZE_BYTES)
            }.getOrElse {
                if (it is CancellationException) throw it
                LocalModelState.Failed(it.message ?: "无法开始下载")
            }
        }
    }

    fun importLocalModel(uri: Uri) {
        modelStatusJob?.cancel()
        viewModelScope.launch {
            _localModelState.value = LocalModelState.Importing
            _localModelState.value = runCatching {
                LocalModelManager.importModel(context, uri)
                withContext(Dispatchers.IO) { LocalModelManager.status(context) }
            }.getOrElse {
                if (it is CancellationException) throw it
                LocalModelState.Failed(it.message ?: "模型导入失败")
            }
        }
    }

    fun saveSettings(settings: AiAnalysisSettings) {
        if (_state.value is UserContentAnalysisState.Loading ||
            _state.value is UserContentAnalysisState.Streaming
        ) {
            stopAnalysis()
        }
        val contextTokens = settings.contextTokens.takeIf { it in setOf(2048, 4096, 8192) }
            ?: AiAnalysisSettings.DEFAULT_CONTEXT_TOKENS
        val normalized = settings.copy(
            maxPosts = settings.maxPosts.coerceIn(4, 40),
            maxCharsPerPost = settings.maxCharsPerPost.coerceIn(100, 1000),
            contextTokens = contextTokens,
            maxOutputTokens = settings.maxOutputTokens.coerceIn(
                256,
                minOf(2048, contextTokens - 768),
            ),
            systemPrompt = settings.systemPrompt.ifBlank { AiAnalysisSettings.DEFAULT_SYSTEM_PROMPT },
        )
        AiAnalysisSettingsStore.save(context, normalized)
        _settings.value = normalized
        if (normalized.provider == AnalysisSource.REMOTE) {
            viewModelScope.launch { LocalModelRuntime.release() }
        }
        if (_state.value !is UserContentAnalysisState.Idle) {
            _state.value = UserContentAnalysisState.Idle
        }
    }

    fun stopPageWork() {
        modelStatusJob?.cancel()
        stopAnalysis()
    }

    fun stopAnalysis() {
        analysisJob?.cancel()
        analysisJob = null
        _state.value = UserContentAnalysisState.Idle
    }

    fun analyze(
        uid: Long,
        displayName: String = "",
        focusPost: PublicPostSnapshot? = null,
        forceRefresh: Boolean = false,
    ) {
        if (_state.value is UserContentAnalysisState.Loading ||
            _state.value is UserContentAnalysisState.Streaming
        ) return
        analysisJob = viewModelScope.launch {
            val ownerJob = coroutineContext[Job]
            val currentSettings = _settings.value
            if (!forceRefresh) {
                ContentAnalysisCache.get(uid, focusPost, currentSettings.cacheKey)?.let { cached ->
                    _state.value = UserContentAnalysisState.Success(
                        result = cached.response,
                        fromCache = true,
                        source = cached.source,
                        metrics = null,
                    )
                    return@launch
                }
            }
            _state.value = UserContentAnalysisState.Loading
            _state.value = runCatching {
                val api = TiebaApi.getInstance()
                val profile = runCatching {
                    api.userProfileFlow(uid).first().data_?.user
                }.getOrNull()

                val pagesPerKind = ((currentSettings.maxPosts + 39) / 40).coerceIn(1, 3)
                val requests = buildList {
                    for (isThread in listOf(true, false)) {
                        for (page in 1..pagesPerKind) {
                            add(async {
                                runCatching {
                                    api.userPostFlow(uid, page, isThread).first() to isThread
                                }.getOrNull()
                            })
                        }
                    }
                }
                val sampledPosts = requests.awaitAll().filterNotNull().flatMap { (response, isThread) ->
                    response.data_?.post_list.orEmpty().mapNotNull { post ->
                        val text = post.abstractText.trim()
                        if (text.isBlank() && post.title.isBlank()) return@mapNotNull null
                        PublicPostSnapshot(
                            id = post.post_id.toLong(),
                            threadId = post.thread_id.toLong(),
                            forumName = post.forum_name,
                            title = post.title,
                            content = text.take(currentSettings.maxCharsPerPost),
                            createdAt = post.create_time.toLong(),
                            kind = if (isThread) "thread" else "reply",
                        )
                    }
                }
                val posts = (listOfNotNull(focusPost) + sampledPosts)
                    .distinctBy { "${it.kind}:${it.id}" }
                    .sortedWith(
                        compareByDescending<PublicPostSnapshot> { it.id == focusPost?.id }
                            .thenByDescending { it.createdAt }
                    )
                    .take(currentSettings.maxPosts)
                    .map { post ->
                        post.copy(
                            title = post.title.take(80),
                            content = post.content.take(currentSettings.maxCharsPerPost),
                        )
                    }

                check(posts.isNotEmpty()) { "该用户没有可分析的公开文字内容" }
                val request = ContentAnalysisRequest(
                    user = PublicUserSnapshot(
                        uid = profile?.id ?: uid,
                        displayName = profile?.nameShow?.ifBlank { profile.name }
                            ?.ifBlank { displayName } ?: displayName,
                        intro = profile?.display_intro?.ifBlank { profile.intro }
                            ?.take(500).orEmpty(),
                        accountAge = profile?.tb_age.orEmpty(),
                        threadCount = profile?.thread_num ?: 0,
                        postCount = profile?.post_num ?: 0,
                    ),
                    posts = posts,
                    focusPostId = focusPost?.id,
                )
                when (currentSettings.provider) {
                    AnalysisSource.LOCAL -> {
                        check(LocalModelManager.isReady(context)) { "本地模型尚未安装" }
                        val local = LocalContentAnalysisClient.analyze(
                            context = context,
                            request = request,
                            settings = currentSettings,
                        ) { update ->
                            if (ownerJob?.isActive == true) _state.value = UserContentAnalysisState.Streaming(
                                text = update.text,
                                outputTokens = update.outputTokens,
                                elapsedSeconds = update.elapsedSeconds,
                                tokensPerSecond = update.tokensPerSecond,
                            )
                        }
                        AnalysisResult(
                            response = local.response,
                            source = AnalysisSource.LOCAL,
                            metrics = AnalysisMetrics(
                                inputTokens = local.inputTokens,
                                outputTokens = local.outputTokens,
                                elapsedSeconds = local.elapsedSeconds,
                                timeToFirstTokenSeconds = local.timeToFirstTokenSeconds,
                                prefillTokensPerSecond = local.prefillTokensPerSecond,
                                decodeTokensPerSecond = local.decodeTokensPerSecond,
                                backend = local.backend,
                            ),
                        )
                    }
                    AnalysisSource.REMOTE -> {
                        val startedAt = System.nanoTime()
                        AnalysisResult(
                            response = ContentAnalysisClient.analyze(request),
                            source = AnalysisSource.REMOTE,
                            metrics = AnalysisMetrics(
                                elapsedSeconds = (System.nanoTime() - startedAt) / 1_000_000_000.0,
                            ),
                        )
                    }
                }
            }.fold(
                onSuccess = { analysis ->
                    ContentAnalysisCache.put(
                        uid,
                        focusPost,
                        analysis.response,
                        analysis.source,
                        currentSettings.cacheKey,
                    )
                    UserContentAnalysisState.Success(
                        result = analysis.response,
                        fromCache = false,
                        source = analysis.source,
                        metrics = analysis.metrics,
                    )
                },
                onFailure = { exception ->
                    if (exception is CancellationException) throw exception
                    Log.e(TAG, "Content analysis failed", exception)
                    val causes = generateSequence(exception) { it.cause }.toList()
                    val rootCause = causes.last()
                    val detail = causes
                        .mapNotNull { cause ->
                            cause.message
                                ?.replace(Regex("[\\p{Cc}\\p{Cf}]+"), " ")
                                ?.trim()
                                ?.takeIf(String::isNotEmpty)
                                ?.take(240)
                        }
                        .firstOrNull()
                    UserContentAnalysisState.Error(
                        "${rootCause.javaClass.simpleName.ifBlank { "未知错误" }}：${detail ?: "分析失败"}"
                    )
                },
            )
            analysisJob = null
        }
    }
}

sealed interface UserContentAnalysisState {
    data object Idle : UserContentAnalysisState
    data object Loading : UserContentAnalysisState
    data class Streaming(
        val text: String,
        val outputTokens: Int,
        val elapsedSeconds: Double,
        val tokensPerSecond: Double,
    ) : UserContentAnalysisState
    data class Success(
        val result: ContentAnalysisResponse,
        val fromCache: Boolean,
        val source: AnalysisSource,
        val metrics: AnalysisMetrics?,
    ) : UserContentAnalysisState
    data class Error(val message: String) : UserContentAnalysisState
}

private data class AnalysisResult(
    val response: ContentAnalysisResponse,
    val source: AnalysisSource,
    val metrics: AnalysisMetrics,
)
