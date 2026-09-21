package com.huanchengfly.tieba.post.ai

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.huanchengfly.tieba.post.dataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    private val preloadPreferenceKey = booleanPreferencesKey(AUTO_PRELOAD_KEY)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val _state = MutableStateFlow<LocalModelRuntimeState>(LocalModelRuntimeState.Idle)

    val state = _state.asStateFlow()

    fun preloadOnAppStart(context: Context) {
        val applicationContext = context.applicationContext
        scope.launch {
            val enabled = applicationContext.dataStore.data.first()[preloadPreferenceKey] ?: false
            if (enabled) preload(applicationContext)
        }
    }

    suspend fun preload(context: Context) = mutex.withLock {
        if (GemmaLocalInference.isInitialized()) {
            _state.value = LocalModelRuntimeState.Ready(GemmaLocalInference.getBackendName())
            return@withLock
        }
        if (!LocalModelManager.isReady(context)) {
            _state.value = LocalModelRuntimeState.Failed("本地模型尚未安装")
            return@withLock
        }

        _state.value = LocalModelRuntimeState.Loading
        runCatching {
            val model = requireNotNull(LocalModelManager.modelFile(context))
            withContext(Dispatchers.IO) { GemmaLocalInference.initialize(model.absolutePath) }
        }.onSuccess {
            val backend = GemmaLocalInference.getBackendName()
            Log.i(TAG, "Gemma 4 E4B engine is ready with backend=$backend")
            _state.value = LocalModelRuntimeState.Ready(backend)
        }.onFailure {
            Log.e(TAG, "Failed to preload Gemma 4 E4B GPU engine", it)
            _state.value = LocalModelRuntimeState.Failed(it.message ?: "模型加载失败")
        }
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
