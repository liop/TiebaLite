package com.huanchengfly.tieba.post.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ContentAnalysisRequest(
    val user: PublicUserSnapshot,
    val posts: List<PublicPostSnapshot>,
)

@Serializable
data class PublicUserSnapshot(
    val uid: Long,
    val displayName: String,
    val intro: String,
    val accountAge: String,
    val threadCount: Int,
    val postCount: Int,
)

@Serializable
data class PublicPostSnapshot(
    val id: Long,
    val threadId: Long,
    val forumName: String,
    val title: String,
    val content: String,
    val createdAt: Long,
    val kind: String,
)

@Serializable
data class ContentAnalysisResponse(
    val summary: String,
    val topics: List<AnalysisFinding> = emptyList(),
    @SerialName("communication_style") val communicationStyle: List<AnalysisFinding> = emptyList(),
    @SerialName("content_patterns") val contentPatterns: List<AnalysisFinding> = emptyList(),
    val limitations: String,
)

@Serializable
data class AnalysisFinding(
    val label: String,
    val description: String,
    @SerialName("post_ids") val postIds: List<Long> = emptyList(),
)
