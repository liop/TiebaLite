package com.huanchengfly.tieba.post.utils

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.edit
import com.huanchengfly.tieba.post.dataStore
import com.huanchengfly.tieba.post.models.database.Account
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.litepal.LitePal
import org.litepal.LitePal.findAll
import java.io.InputStream
import java.io.OutputStream

object BackupManager {
    private const val FORMAT_VERSION = 1
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    @Serializable
    private data class BackupFile(
        val formatVersion: Int,
        val accounts: List<BackupAccount>,
        val currentAccountUid: String?,
        val preferences: BackupPreferences,
        val remarks: Map<String, String>,
        val notes: Map<String, String>,
    )

    @Serializable
    private data class BackupAccount(
        val uid: String, val name: String, val bduss: String, val tbs: String,
        val portrait: String, val sToken: String, val cookie: String,
        val nameShow: String?, val intro: String?, val sex: String?,
        val fansNum: String?, val postNum: String?, val threadNum: String?,
        val concernNum: String?, val tbAge: String?, val age: String?,
        val birthdayShowStatus: String?, val birthdayTime: String?, val constellation: String?,
        val tiebaUid: String?, val loadSuccess: Boolean, val uuid: String?, val zid: String?,
    ) {
        companion object {
            fun from(account: Account) = BackupAccount(account.uid, account.name, account.bduss, account.tbs, account.portrait, account.sToken, account.cookie, account.nameShow, account.intro, account.sex, account.fansNum, account.postNum, account.threadNum, account.concernNum, account.tbAge, account.age, account.birthdayShowStatus, account.birthdayTime, account.constellation, account.tiebaUid, account.loadSuccess, account.uuid, account.zid)
        }
        fun toAccount() = Account(uid, name, bduss, tbs, portrait, sToken, cookie, nameShow, intro, sex, fansNum, postNum, threadNum, concernNum, tbAge, age, birthdayShowStatus, birthdayTime, constellation, tiebaUid, loadSuccess, uuid, zid)
    }

    @Serializable
    private data class BackupPreferences(
        val strings: Map<String, String>, val booleans: Map<String, Boolean>,
        val ints: Map<String, Int>, val longs: Map<String, Long>, val floats: Map<String, Float>,
        val stringSets: Map<String, Set<String>>,
    )

    suspend fun export(context: Context, output: OutputStream) {
        val preferences = context.dataStore.data.first()
        val strings = mutableMapOf<String, String>(); val booleans = mutableMapOf<String, Boolean>()
        val ints = mutableMapOf<String, Int>(); val longs = mutableMapOf<String, Long>(); val floats = mutableMapOf<String, Float>(); val sets = mutableMapOf<String, Set<String>>()
        preferences.asMap().forEach { (key, value) -> when (value) {
            is String -> strings[key.name] = value
            is Boolean -> booleans[key.name] = value
            is Int -> ints[key.name] = value
            is Long -> longs[key.name] = value
            is Float -> floats[key.name] = value
            is Set<*> -> sets[key.name] = value.filterIsInstance<String>().toSet()
        } }
        val accountData = context.getSharedPreferences("accountData", Context.MODE_PRIVATE)
        val backup = BackupFile(FORMAT_VERSION, findAll(Account::class.java).map(BackupAccount::from), AccountUtil.currentAccount?.uid, BackupPreferences(strings, booleans, ints, longs, floats, sets), stringPreferences(context, "user_remarks"), stringPreferences(context, "user_notes"))
        output.bufferedWriter().use { it.write(json.encodeToString(backup)) }
    }

    suspend fun import(context: Context, input: InputStream) {
        val backup = input.bufferedReader().use { json.decodeFromString<BackupFile>(it.readText()) }
        require(backup.formatVersion == FORMAT_VERSION) { "不支持的备份文件版本" }
        context.dataStore.edit { prefs ->
            prefs.clear()
            backup.preferences.strings.forEach { (k,v) -> prefs[stringPreferencesKey(k)] = v }
            backup.preferences.booleans.forEach { (k,v) -> prefs[booleanPreferencesKey(k)] = v }
            backup.preferences.ints.forEach { (k,v) -> prefs[intPreferencesKey(k)] = v }
            backup.preferences.longs.forEach { (k,v) -> prefs[longPreferencesKey(k)] = v }
            backup.preferences.floats.forEach { (k,v) -> prefs[floatPreferencesKey(k)] = v }
            backup.preferences.stringSets.forEach { (k,v) -> prefs[stringSetPreferencesKey(k)] = v }
        }
        LitePal.deleteAll(Account::class.java)
        backup.accounts.forEach { it.toAccount().save() }
        putStringPreferences(context, "user_remarks", backup.remarks)
        putStringPreferences(context, "user_notes", backup.notes)
        AccountUtil.init(context)
        backup.currentAccountUid?.let { uid -> AccountUtil.getAccountInfoByUid(uid)?.let { AccountUtil.switchAccount(context, it.id) } }
    }

    private fun stringPreferences(context: Context, name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE).all.mapNotNull { (key, value) -> (value as? String)?.let { key to it } }.toMap()
    private fun putStringPreferences(context: Context, name: String, values: Map<String, String>) { context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().apply { values.forEach { (k,v) -> putString(k,v) } }.commit() }
}
