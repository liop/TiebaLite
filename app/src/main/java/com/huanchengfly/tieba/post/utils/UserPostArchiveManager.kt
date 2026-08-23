package com.huanchengfly.tieba.post.utils

import com.huanchengfly.tieba.post.api.TiebaApi
import com.huanchengfly.tieba.post.api.models.protos.PostInfoList
import com.huanchengfly.tieba.post.api.models.protos.abstractText
import com.huanchengfly.tieba.post.models.database.UserPostArchive
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.litepal.LitePal
import java.io.OutputStream

/** 同步个人主页的主题和回复到本地，并导出为便于分析的 JSON。 */
object UserPostArchiveManager {
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    @Serializable
    private data class ExportFile(
        val formatVersion: Int = 1,
        val exportedAt: Long,
        val uid: Long,
        val records: List<ExportRecord>,
    )

    @Serializable
    private data class ExportRecord(
        val threadId: Long,
        val postId: Long,
        val type: String,
        val forumId: Long,
        val forumName: String,
        val title: String,
        val content: String,
        val createTime: Long,
        val isDeleted: Boolean,
        val archivedAt: Long,
        val updatedAt: Long,
    )

    suspend fun syncAndExport(uid: Long, output: OutputStream): Int {
        sync(uid)
        val records = all(uid).map {
            ExportRecord(it.threadId, it.postId, if (it.isThread) "thread" else "reply", it.forumId, it.forumName, it.title, it.content, it.createTime, it.isDeleted, it.archivedAt, it.updatedAt)
        }
        output.bufferedWriter().use { it.write(json.encodeToString(ExportFile(exportedAt = System.currentTimeMillis(), uid = uid, records = records))) }
        return records.size
    }

    fun archive(uid: Long, isThread: Boolean, posts: List<PostInfoList>) {
        posts.flatMap { it.toArchiveRecords(uid, isThread) }.forEach(::saveOrUpdate)
    }

    private suspend fun sync(uid: Long) {
        syncType(uid, true)
        syncType(uid, false)
    }

    private suspend fun syncType(uid: Long, isThread: Boolean) {
        val remoteKeys = mutableSetOf<Long>()
        var page = 1
        var hidden = false
        while (true) {
            val data = checkNotNull(TiebaApi.getInstance().userPostFlow(uid, page, isThread).first().data_)
            hidden = hidden || data.hide_post == 1
            if (data.post_list.isEmpty()) break
            val archives = data.post_list.flatMap { it.toArchiveRecords(uid, isThread) }
            val hasNewRecord = archives.any { it.postId !in remoteKeys }
            archives.forEach {
                remoteKeys += it.postId
                saveOrUpdate(it)
            }
            if (!hasNewRecord) break
            page++
        }
        if (!hidden) markMissingAsDeleted(uid, isThread, remoteKeys)
    }

    private fun PostInfoList.toArchiveRecords(uid: Long, isThread: Boolean): List<UserPostArchive> =
        if (content.isEmpty()) listOf(toArchive(uid, isThread, post_id, create_time.toLong(), abstractText))
        else content.map {
            toArchive(uid, isThread, it.post_id.takeIf { id -> id != 0L } ?: post_id, it.create_time.takeIf { time -> time != 0L } ?: create_time.toLong(), it.post_content.abstractText)
        }

    private fun PostInfoList.toArchive(uid: Long, isThread: Boolean, postId: Long, createTime: Long, content: String) = UserPostArchive(
        uid = uid, userName = user_name, threadId = thread_id, postId = postId, isThread = isThread,
        forumId = forum_id, forumName = forum_name, title = title, content = content, createTime = createTime,
        isDeleted = is_post_deleted == 1, archivedAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis()
    )

    private fun saveOrUpdate(archive: UserPostArchive) {
        val existing = LitePal.where("uid = ?", archive.uid.toString()).find(UserPostArchive::class.java)
            .firstOrNull { it.postId == archive.postId && it.isThread == archive.isThread }
        if (existing == null) archive.save()
        else archive.copy(
            userName = archive.userName.ifEmpty { existing.userName }, forumName = archive.forumName.ifEmpty { existing.forumName },
            title = archive.title.ifEmpty { existing.title }, content = archive.content.ifEmpty { existing.content },
            archivedAt = existing.archivedAt
        ).update(existing.id)
    }

    private fun markMissingAsDeleted(uid: Long, isThread: Boolean, remoteKeys: Set<Long>) {
        LitePal.where("uid = ?", uid.toString()).find(UserPostArchive::class.java).asSequence()
            .filter { it.isThread == isThread && it.postId !in remoteKeys }
            .forEach { it.copy(isDeleted = true, updatedAt = System.currentTimeMillis()).update(it.id) }
    }

    fun deleted(uid: Long, isThread: Boolean): List<UserPostArchive> =
        all(uid).filter { it.isThread == isThread && it.isDeleted }

    private fun all(uid: Long) = LitePal.where("uid = ?", uid.toString()).find(UserPostArchive::class.java)
        .sortedWith(compareByDescending<UserPostArchive> { it.createTime }.thenByDescending { it.postId })
}
