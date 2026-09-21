package com.huanchengfly.tieba.post.ai

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object LocalModelManager {
    const val MODEL_NAME = "Gemma 4 E4B"
    const val MODEL_FILE_NAME = "gemma-4-E4B-it.litertlm"
    const val MODEL_SIZE_BYTES = 3_659_530_240L

    private const val PREFERENCES_NAME = "ai_local_model"
    private const val DOWNLOAD_ID = "download_id"
    private const val MODEL_URL =
        "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/" +
            "28299f30ee4d43294517a4ac93abd6163412f07f/gemma-4-E4B-it.litertlm?download=true"

    fun isSupported(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
            Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "x86_64" }

    fun modelFile(context: Context): File? = context
        .getExternalFilesDir("models")
        ?.resolve(MODEL_FILE_NAME)

    fun isReady(context: Context): Boolean = isSupported() && modelFile(context)?.let { file ->
        file.isFile && file.length() >= 1_000_000_000L
    } == true

    fun cacheDir(context: Context): File =
        File(context.cacheDir, "litertlm").apply { mkdirs() }

    fun enqueueDownload(context: Context): Long {
        check(isSupported()) { "当前设备不支持本地模型运行" }
        val target = requireNotNull(modelFile(context)) { "外部存储不可用" }
        target.parentFile?.mkdirs()
        if (target.exists() && !target.delete()) error("无法覆盖现有模型文件")

        val request = DownloadManager.Request(Uri.parse(MODEL_URL))
            .setTitle("正在下载 $MODEL_NAME")
            .setDescription("用于贴吧 Lite 的设备端内容分析")
            .setMimeType("application/octet-stream")
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(target))

        val manager = context.getSystemService(DownloadManager::class.java)
        return manager.enqueue(request).also { id ->
            preferences(context).edit().putLong(DOWNLOAD_ID, id).apply()
        }
    }

    suspend fun importModel(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
        check(isSupported()) { "当前设备不支持本地模型运行" }
        val target = requireNotNull(modelFile(context)) { "外部存储不可用" }
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "$MODEL_FILE_NAME.importing")
        if (temporary.exists()) temporary.delete()

        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "无法读取所选模型" }
                temporary.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            check(temporary.length() >= 1_000_000_000L) { "所选文件不是有效的 LiteRT-LM 模型" }
            if (target.exists() && !target.delete()) error("无法覆盖现有模型文件")
            check(temporary.renameTo(target)) { "无法保存模型文件" }
            preferences(context).edit().remove(DOWNLOAD_ID).apply()
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    fun status(context: Context): LocalModelState {
        if (!isSupported()) return LocalModelState.Unsupported
        val file = modelFile(context)
        if (file != null && file.isFile && file.length() >= 1_000_000_000L) {
            return LocalModelState.Ready(file.length())
        }

        val id = preferences(context).getLong(DOWNLOAD_ID, -1L)
        if (id < 0L) return LocalModelState.NotInstalled
        val manager = context.getSystemService(DownloadManager::class.java)
        manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
            if (!cursor.moveToFirst()) return LocalModelState.NotInstalled
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val downloaded = cursor.getLong(
                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            )
            val total = cursor.getLong(
                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            )
            return when (status) {
                DownloadManager.STATUS_PENDING,
                DownloadManager.STATUS_RUNNING,
                DownloadManager.STATUS_PAUSED -> LocalModelState.Downloading(downloaded, total)
                DownloadManager.STATUS_SUCCESSFUL -> {
                    if (isReady(context)) LocalModelState.Ready(file?.length() ?: 0L)
                    else LocalModelState.Failed("下载完成，但模型文件无效")
                }
                DownloadManager.STATUS_FAILED -> LocalModelState.Failed("模型下载失败，请重试")
                else -> LocalModelState.NotInstalled
            }
        }
        return LocalModelState.NotInstalled
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
}

sealed interface LocalModelState {
    data object Unsupported : LocalModelState
    data object NotInstalled : LocalModelState
    data object Importing : LocalModelState
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : LocalModelState
    data class Ready(val sizeBytes: Long) : LocalModelState
    data class Failed(val message: String) : LocalModelState
}
