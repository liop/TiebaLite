package com.huanchengfly.tieba.post.models.database

import androidx.compose.runtime.Immutable
import org.litepal.crud.LitePalSupport

/** 用户主题/回复的本地归档；远端删除后仅更新 [isDeleted] 状态。 */
@Immutable
data class UserPostArchive(
    val uid: Long = 0,
    val userName: String = "",
    val threadId: Long = 0,
    val postId: Long = 0,
    val isThread: Boolean = false,
    val forumId: Long = 0,
    val forumName: String = "",
    val title: String = "",
    val content: String = "",
    val createTime: Long = 0,
    val isDeleted: Boolean = false,
    val archivedAt: Long = 0,
    val updatedAt: Long = 0,
) : LitePalSupport() {
    val id: Long = 0
}
