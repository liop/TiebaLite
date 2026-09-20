package com.huanchengfly.tieba.post.ui.page.user.analysis

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.huanchengfly.tieba.post.ai.ContentAnalysisClient
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

    fun analyze(uid: Long) {
        if (_state.value is UserContentAnalysisState.Loading) return
        viewModelScope.launch {
            _state.value = UserContentAnalysisState.Loading
            _state.value = runCatching {
                val api = TiebaApi.getInstance()
                val profile = api.userProfileFlow(uid).first().data_?.user
                    ?: error("无法读取用户公开资料")

                // Two pages of threads plus replies gives a useful sample while keeping data transfer bounded.
                val requests = buildList {
                    for (isThread in listOf(true, false)) {
                        for (page in 1..2) {
                            add(async { api.userPostFlow(uid, page, isThread).first() to isThread })
                        }
                    }
                }
                val posts = requests.awaitAll().flatMap { (response, isThread) ->
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
                }.distinctBy { it.id }.take(80)

                check(posts.isNotEmpty()) { "该用户没有可分析的公开文字内容" }
                ContentAnalysisClient.analyze(
                    ContentAnalysisRequest(
                        user = PublicUserSnapshot(
                            uid = profile.id,
                            displayName = profile.nameShow.ifBlank { profile.name },
                            intro = profile.display_intro.ifBlank { profile.intro }.take(500),
                            accountAge = profile.tb_age,
                            threadCount = profile.thread_num,
                            postCount = profile.post_num,
                        ),
                        posts = posts,
                    )
                )
            }.fold(
                onSuccess = { UserContentAnalysisState.Success(it) },
                onFailure = { UserContentAnalysisState.Error(it.message ?: "分析失败") },
            )
        }
    }
}

sealed interface UserContentAnalysisState {
    data object Idle : UserContentAnalysisState
    data object Loading : UserContentAnalysisState
    data class Success(val result: ContentAnalysisResponse) : UserContentAnalysisState
    data class Error(val message: String) : UserContentAnalysisState
}
