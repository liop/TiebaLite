package com.huanchengfly.tieba.post.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.huanchengfly.tieba.post.App

object UserGenderCache {
    private const val PREF_NAME = "user_gender_cache"
    private val prefs: SharedPreferences by lazy {
        App.INSTANCE.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    var updateCount by mutableIntStateOf(0)
        private set

    data class GenderInfo(val sex: Int, val isChanged: Boolean)

    /**
     * 更新性别缓存
     * 如果新获取的性别和缓存中的不一样，则标记为 isChanged = true
     */
    fun putSex(username: String?, newSex: Int) {
        if (username.isNullOrBlank() || newSex == 0) return
        
        val storedData = prefs.getString(username, null)
        if (storedData != null) {
            val parts = storedData.split(":")
            val oldSex = parts[0].toIntOrNull() ?: 0
            val alreadyChanged = parts.getOrNull(1)?.toBoolean() ?: false
            
            if (oldSex != 0 && oldSex != newSex) {
                // 发现性别变更！
                prefs.edit().putString(username, "$newSex:true").apply()
                updateCount++
            } else if (alreadyChanged && oldSex == newSex) {
                // 如果之前标记过变更，但现在获取的一致，保持标记（或者你可以选择重置）
                // 这里我们保持标记，直到用户主动发现
            } else if (oldSex == 0) {
                prefs.edit().putString(username, "$newSex:false").apply()
                updateCount++
            }
        } else {
            // 第一次存入
            prefs.edit().putString(username, "$newSex:false").apply()
            updateCount++
        }
    }

    fun getGenderInfo(username: String?): GenderInfo {
        if (username.isNullOrBlank()) return GenderInfo(0, false)
        val storedData = prefs.getString(username, null) ?: return GenderInfo(0, false)
        val parts = storedData.split(":")
        val sex = parts[0].toIntOrNull() ?: 0
        val isChanged = parts.getOrNull(1)?.toBoolean() ?: false
        return GenderInfo(sex, isChanged)
    }
}
