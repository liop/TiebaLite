package com.huanchengfly.tieba.post.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.huanchengfly.tieba.post.App

/** Stores private notes that do not affect a user's displayed name. */
object UserNoteManager {
    private const val PREF_NAME = "user_notes"
    private val preferences: SharedPreferences by lazy {
        App.INSTANCE.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    var updateCount by mutableIntStateOf(0)
        private set

    fun getNote(username: String?): String? = username
        ?.takeIf { it.isNotBlank() }
        ?.let { preferences.getString(it, null) }
        ?.takeIf { it.isNotBlank() }

    fun setNote(username: String, note: String) {
        if (username.isBlank()) return
        val normalizedNote = note.trim()
        preferences.edit().apply {
            if (normalizedNote.isEmpty()) remove(username) else putString(username, normalizedNote)
        }.apply()
        updateCount++
    }
}
