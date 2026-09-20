package com.huanchengfly.tieba.post.ui.page.user.analysis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Button
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.huanchengfly.tieba.post.BuildConfig
import com.huanchengfly.tieba.post.R
import com.huanchengfly.tieba.post.ai.AnalysisFinding
import com.huanchengfly.tieba.post.ai.PublicPostSnapshot
import com.huanchengfly.tieba.post.ui.widgets.compose.BackNavigationIcon
import com.huanchengfly.tieba.post.ui.widgets.compose.MyScaffold
import com.huanchengfly.tieba.post.ui.widgets.compose.Toolbar
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator

@Destination
@Composable
fun UserContentAnalysisPage(
    uid: Long,
    focusPostId: Long = 0L,
    focusThreadId: Long = 0L,
    focusForumName: String = "",
    focusTitle: String = "",
    focusContent: String = "",
    focusCreatedAt: Long = 0L,
    focusKind: String = "",
    displayName: String = "",
    navigator: DestinationsNavigator,
    viewModel: UserContentAnalysisViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val focusPost = if (focusPostId > 0L) {
        PublicPostSnapshot(
            id = focusPostId,
            threadId = focusThreadId,
            forumName = focusForumName,
            title = focusTitle,
            content = focusContent,
            createdAt = focusCreatedAt,
            kind = focusKind.ifBlank { "reply" },
        )
    } else {
        null
    }
    LaunchedEffect(uid, focusPostId) {
        if (BuildConfig.AI_ANALYSIS_BASE_URL.isNotBlank()) {
            viewModel.analyze(uid, displayName, focusPost)
        }
    }

    MyScaffold(
        topBar = {
            Toolbar(
                title = {
                    Text(
                        stringResource(
                            if (focusPost == null) R.string.title_ai_content_analysis
                            else R.string.title_ai_speech_analysis
                        )
                    )
                },
                navigationIcon = { BackNavigationIcon { navigator.navigateUp() } },
            )
        }
    ) { padding ->
        when (val current = state) {
            UserContentAnalysisState.Idle -> AnalysisMessage(
                message = stringResource(R.string.ai_analysis_not_configured),
                button = stringResource(R.string.ai_analysis_start),
                onClick = { viewModel.analyze(uid, displayName, focusPost) },
                modifier = Modifier.padding(padding),
            )
            UserContentAnalysisState.Loading -> Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
                Text(
                    stringResource(R.string.ai_analysis_collecting),
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
            is UserContentAnalysisState.Error -> AnalysisMessage(
                message = current.message,
                button = stringResource(R.string.ai_analysis_retry),
                onClick = { viewModel.analyze(uid, displayName, focusPost) },
                modifier = Modifier.padding(padding),
            )
            is UserContentAnalysisState.Success -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (current.fromCache) {
                    item {
                        Text(
                            stringResource(R.string.ai_analysis_cached),
                            color = MaterialTheme.colors.primary,
                            style = MaterialTheme.typography.caption,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                    }
                }
                item {
                    Text(
                        current.result.summary,
                        style = MaterialTheme.typography.body1,
                        modifier = Modifier.padding(top = if (current.fromCache) 0.dp else 16.dp),
                    )
                }
                findingSection(R.string.ai_analysis_focus, current.result.focusObservations)
                findingSection(R.string.ai_analysis_topics, current.result.topics)
                findingSection(R.string.ai_analysis_style, current.result.communicationStyle)
                findingSection(R.string.ai_analysis_patterns, current.result.contentPatterns)
                item {
                    Text(
                        current.result.limitations,
                        style = MaterialTheme.typography.caption,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    Text(
                        stringResource(R.string.ai_analysis_notice),
                        style = MaterialTheme.typography.caption,
                    )
                    Button(
                        onClick = {
                            viewModel.analyze(
                                uid = uid,
                                displayName = displayName,
                                focusPost = focusPost,
                                forceRefresh = true,
                            )
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 24.dp),
                    ) {
                        Text(stringResource(R.string.ai_analysis_refresh))
                    }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.findingSection(
    titleRes: Int,
    findings: List<AnalysisFinding>,
) {
    if (findings.isEmpty()) return
    item { Text(stringResource(titleRes), style = MaterialTheme.typography.h6) }
    items(findings) { finding ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(finding.label, style = MaterialTheme.typography.subtitle1)
            Text(finding.description, style = MaterialTheme.typography.body2)
            if (finding.postIds.isNotEmpty()) {
                Text(
                    "${stringResource(R.string.ai_analysis_evidence)}：${finding.postIds.joinToString()}",
                    style = MaterialTheme.typography.caption,
                )
            }
        }
    }
}

@Composable
private fun AnalysisMessage(
    message: String,
    button: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(message)
        Text(
            stringResource(R.string.ai_analysis_notice),
            style = MaterialTheme.typography.caption,
            modifier = Modifier.padding(top = 12.dp),
        )
        Button(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) {
            Text(button)
        }
    }
}
