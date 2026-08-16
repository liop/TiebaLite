package com.huanchengfly.tieba.post.components

import android.util.LruCache
import com.huanchengfly.tieba.post.api.models.protos.ThreadInfo
import com.ramcosta.composedestinations.navargs.DestinationsNavTypeSerializer
import com.ramcosta.composedestinations.navargs.NavTypeSerializer

object ThreadNavBridge {
    private const val MAX_CACHE_SIZE = 4
    private val cache = LruCache<Long, ThreadInfo>(MAX_CACHE_SIZE)

    fun put(data: ThreadInfo): String {
        cache.put(data.threadId, data)
        return data.threadId.toString()
    }

    fun get(key: String): ThreadInfo? = key.toLongOrNull()?.let(cache::get)
}

@NavTypeSerializer
class ThreadInfoSerializer : DestinationsNavTypeSerializer<ThreadInfo> {
    override fun toRouteString(value: ThreadInfo): String = ThreadNavBridge.put(value)

    override fun fromRouteString(routeStr: String): ThreadInfo =
        ThreadNavBridge.get(routeStr) ?: ThreadInfo()
}
