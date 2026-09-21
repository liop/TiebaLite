package com.huanchengfly.tieba.post.ui.page.user.analysis

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.huanchengfly.tieba.post.ai.AnalysisSource
import com.huanchengfly.tieba.post.ai.ContentAnalysisClient
import com.huanchengfly.tieba.post.ai.ContentAnalysisCache
import com.huanchengfly.tieba.post.ai.ContentAnalysisRequest
import com.huanchengfly.tieba.post.ai.ContentAnalysisResponse
import com.huanchengfly.tieba.post.ai.LocalContentAnalysisClient
import com.huanchengfly.tieba.post.ai.LocalModelManager
import com.huanchengfly.tieba.post.ai.LocalModelState
import com.huanchengfly.tieba.post.ai.PublicPostSnapshot
import com.huanchengfly.tieba.post.ai.PublicUserSnapshot
import com.huanchengfly.tieba.post.api.TiebaApi
import com.huanchengfly.tieba.post.api.models.protos.abstractText
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
    private val _localModelState = MutableStateFlow<LocalModelState>(LocalModelManager.status(context))
    val localModelState: StateFlow<LocalModelState> = _localModelState.asStateFlow()

    init {
        viewModelScope.launch {
            while (isActive) {
                _localModelState.value = LocalModelManager.status(context)
                delay(if (_localModelState.value is LocalModelState.Downloading) 1_000L else 5_000L)
            }
        }
    }

    fun downloadLocalModel() {
        _localModelState.value = runCatching {
            LocalModelManager.enqueueDownload(context)
            LocalModelManager.status(context)
        }.getOrElse { LocalModelState.Failed(it.message ?: "无法开始下载") }
    }

    fun importLocalModel(uri: Uri) {
        viewModelScope.launch {
            _localModelState.value = LocalModelState.Importing
            _localModelState.value = runCatching {
                LocalModelManager.importModel(context, uri)
                LocalModelManager.status(context)
            }.getOrElse { LocalModelState.Failed(it.message ?: "模型导入失败") }
        }
    }

    fun analyze(
        uid: Long,
        displayName: String = "",
        focusPost: PublicPostSnapshot? = null,
        forceRefresh: Boolean = false,
    ) {
        if (_state.value is UserContentAnalysisState.Loading) return
        viewModelScope.launch {
            if (!forceRefresh) {
                ContentAnalysisCache.get(uid, focusPost)?.let { cached ->
                    _state.value = UserContentAnalysisState.Success(
                        result = cached.response,
                        fromCache = true,
                        source = cached.source,
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

                // Two pages of threads plus replies gives a useful sample while keeping data transfer bounded.
                val requests = buildList {
                    for (isThread in listOf(true, false)) {
                        for (page in 1..2) {
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
                            content = text.take(1200),
                            createdAt = post.create_time.toLong(),
                            kind = if (isThread) "thread" else "reply",
                        )
                    }
                }
                val posts = (listOfNotNull(focusPost) + sampledPosts)
                    .distinctBy { "${it.kind}:${it.id}" }
                    .take(80)

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
                if (LocalModelManager.isReady(context)) {
                    val local = LocalContentAnalysisClient.analyze(context, request)
                    AnalysisResult(local.response, AnalysisSource.LOCAL)
                } else {
                    AnalysisResult(ContentAnalysisClient.analyze(request), AnalysisSource.REMOTE)
                }
            }.fold(
                onSuccess = { analysis ->
                    ContentAnalysisCache.put(uid, focusPost, analysis.response, analysis.source)
                    UserContentAnalysisState.Success(
                        result = analysis.response,
                        fromCache = false,
                        source = analysis.source,
                    )
                },
                onFailure = { exception ->
                    Log.e(TAG, "Content analysis failed", exception)
                    val causes = generateSequence(exception) { it.cause }.toList()
                    val rootCause = causes.last()
                    val detail = causes
                        .mapNotNull { cause ->
                            cause.message
                                ?.replace(Regex("[\\p{Cc}\\p{Cf}]+"), " ")
                                ?.trim()
                                ?.takeIf(String::isNotEmpty)
                        }
                        .firstOrNull()
                    UserContentAnalysisState.Error(
                        "${rootCause.javaClass.simpleName.ifBlank { "未知错误" }}：${detail ?: "分析失败"}"
                    )
                },
            )
        }
    }
}

sealed interface UserContentAnalysisState {
    data object Idle : UserContentAnalysisState
    data object Loading : UserContentAnalysisState
    data class Success(
        val result: ContentAnalysisResponse,
        val fromCache: Boolean,
        val source: AnalysisSource,
    ) : UserContentAnalysisState
    data class Error(val message: String) : UserContentAnalysisState
}

private data class AnalysisResult(
    val response: ContentAnalysisResponse,
    val source: AnalysisSource,
)
