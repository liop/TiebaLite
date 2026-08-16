package com.huanchengfly.tieba.post.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.huanchengfly.tieba.post.App

/** Persists local display names for other users. */
object UserRemarkManager {
    private const val PREF_NAME = "user_remarks"
    private val preferences: SharedPreferences by lazy {
        App.INSTANCE.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    var updateCount by mutableIntStateOf(0)
        private set

    fun getRemark(username: String?): String? = username
        ?.takeIf { it.isNotBlank() }
        ?.let { preferences.getString(it, null) }
        ?.takeIf { it.isNotBlank() }

    fun setRemark(username: String, remark: String) {
        if (username.isBlank()) return
        val normalizedRemark = remark.trim()
        preferences.edit().apply {
            if (normalizedRemark.isEmpty()) remove(username)
            else putString(username, normalizedRemark)
        }.apply()
        updateCount++
    }
}
