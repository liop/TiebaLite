package com.huanchengfly.tieba.post.ui.page.user.analysis

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.huanchengfly.tieba.post.ai.ContentAnalysisClient
import com.huanchengfly.tieba.post.ai.ContentAnalysisCache
import com.huanchengfly.tieba.post.ai.ContentAnalysisRequest
import com.huanchengfly.tieba.post.ai.ContentAnalysisResponse
import com.huanchengfly.tieba.post.ai.PublicPostSnapshot
import com.huanchengfly.tieba.post.ai.PublicUserSnapshot
import com.huanchengfly.tieba.post.api.TiebaApi
import com.huanchengfly.tieba.post.api.models.protos.abstractText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class UserContentAnalysisViewModel @Inject constructor() : ViewModel() {
    private val _state = MutableStateFlow<UserContentAnalysisState>(UserContentAnalysisState.Idle)
    val state: StateFlow<UserContentAnalysisState> = _state.asStateFlow()

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
                    _state.value = UserContentAnalysisState.Success(cached.response, fromCache = true)
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
                ContentAnalysisClient.analyze(
                    ContentAnalysisRequest(
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
                )
            }.fold(
                onSuccess = {
                    ContentAnalysisCache.put(uid, focusPost, it)
                    UserContentAnalysisState.Success(it, fromCache = false)
                },
                onFailure = { UserContentAnalysisState.Error(it.message ?: "分析失败") },
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
    ) : UserContentAnalysisState
    data class Error(val message: String) : UserContentAnalysisState
}
