package com.huanchengfly.tieba.post.ai

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.huanchengfly.tieba.post.MainActivityV2
import com.huanchengfly.tieba.post.R
import com.huanchengfly.tieba.post.dataStore
import com.huanchengfly.tieba.post.pendingIntentFlagImmutable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object LocalModelRuntime {
    const val AUTO_PRELOAD_KEY = "ai_local_model_auto_preload"

    private const val TAG = "LocalModelRuntime"
    private const val NOTIFICATION_CHANNEL_ID = "local_ai_model"
    private const val NOTIFICATION_ID = 4601
    private val preloadPreferenceKey = booleanPreferencesKey(AUTO_PRELOAD_KEY)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val _state = MutableStateFlow<LocalModelRuntimeState>(LocalModelRuntimeState.Idle)

    val state = _state.asStateFlow()

    fun preloadOnAppStart(context: Context) {
        val applicationContext = context.applicationContext
        scope.launch {
            val enabled = shouldKeepEngine(applicationContext)
            if (enabled && preload(applicationContext)) {
                notifyPreloadComplete(applicationContext)
            }
        }
    }

    suspend fun preload(context: Context): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (GemmaLocalInference.isInitialized()) {
                _state.value = LocalModelRuntimeState.Ready(GemmaLocalInference.getBackendName())
                return@withLock true
            }
            if (!LocalModelManager.isReady(context)) {
                _state.value = LocalModelRuntimeState.Failed("本地模型尚未安装")
                return@withLock false
            }

            _state.value = LocalModelRuntimeState.Loading
            runCatching {
                val model = requireNotNull(LocalModelManager.modelFile(context))
                val settings = AiAnalysisSettingsStore.load(context)
                GemmaLocalInference.initialize(
                    model.absolutePath,
                    settings.contextTokens,
                    LocalModelManager.cacheDir(context).absolutePath,
                )
            }.onSuccess {
                val backend = GemmaLocalInference.getBackendName()
                Log.i(TAG, "Gemma 4 E4B engine is ready with backend=$backend")
                _state.value = LocalModelRuntimeState.Ready(backend)
            }.onFailure {
                if (it is CancellationException) throw it
                Log.e(TAG, "Failed to preload Gemma 4 E4B GPU engine", it)
                _state.value = LocalModelRuntimeState.Failed(it.message ?: "模型加载失败")
            }.isSuccess
        }
    }

    private suspend fun shouldKeepEngine(context: Context): Boolean =
        (context.dataStore.data.first()[preloadPreferenceKey] ?: false) &&
            AiAnalysisSettingsStore.load(context).provider == AnalysisSource.LOCAL

    // Share the lock with preload/release so disabling preload cannot close an active engine.
    // Interrupt the blocking JNI wait on cancellation, then release after its conversation closes.
    suspend fun <T> withEngine(context: Context, block: () -> T): T = mutex.withLock {
        try {
            runInterruptible(Dispatchers.IO) { block() }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                if (!shouldKeepEngine(context)) {
                    GemmaLocalInference.closeEngine()
                    _state.value = LocalModelRuntimeState.Idle
                }
            }
        }
    }

    private fun notifyPreloadComplete(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(
                NOTIFICATION_CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_DEFAULT,
            )
                .setName(context.getString(R.string.ai_local_model_notification_channel))
                .setDescription(context.getString(R.string.ai_local_model_notification_channel_desc))
                .setShowBadge(false)
                .build()
        )
        val backend = (_state.value as? LocalModelRuntimeState.Ready)?.backend.orEmpty()
        val contentIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(context, MainActivityV2::class.java),
            pendingIntentFlagImmutable(),
        )
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_info_black_24)
                .setContentTitle(context.getString(R.string.ai_local_model_preload_complete))
                .setContentText(
                    context.getString(
                        R.string.ai_local_model_preload_complete_desc,
                        backend.ifBlank { context.getString(R.string.ai_local_model_backend_unknown) },
                    )
                )
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .build()
        )
    }

    suspend fun release() = mutex.withLock {
        withContext(Dispatchers.IO) { GemmaLocalInference.closeEngine() }
        _state.value = LocalModelRuntimeState.Idle
        Log.i(TAG, "Gemma 4 E4B GPU engine released")
    }
}

sealed interface LocalModelRuntimeState {
    data object Idle : LocalModelRuntimeState
    data object Loading : LocalModelRuntimeState
    data class Ready(val backend: String) : LocalModelRuntimeState
    data class Failed(val message: String) : LocalModelRuntimeState
}
