package com.harish.heymate.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "heymate_prefs")

/** App settings + bound-device persistence. */
class Prefs(private val context: Context) {

    private object Keys {
        val HERMES_ENDPOINT = stringPreferencesKey("hermes_endpoint")
        val GEMINI_API_KEY = stringPreferencesKey("gemini_api_key")
        val SPEAK_REPLIES = booleanPreferencesKey("speak_replies")
        val AUTO_RECONNECT = booleanPreferencesKey("auto_reconnect")
        val SEND_PHOTOS_TO_AGENT = booleanPreferencesKey("send_photos_to_agent")
        val NOTIFY_REPLIES = booleanPreferencesKey("notify_replies")
        val VOICE_NAME = stringPreferencesKey("voice_name")
        val VOICE_LANGUAGE = stringPreferencesKey("voice_language")
        val BOUND_MAC = stringPreferencesKey("bound_mac")
        val BOUND_NAME = stringPreferencesKey("bound_name")
        val IMPORTED_FILES = stringSetPreferencesKey("imported_files")
    }

    val hermesEndpoint: Flow<String> = context.dataStore.data.map { it[Keys.HERMES_ENDPOINT] ?: "" }
    val geminiApiKey: Flow<String> = context.dataStore.data.map { it[Keys.GEMINI_API_KEY] ?: "" }
    val speakReplies: Flow<Boolean> = context.dataStore.data.map { it[Keys.SPEAK_REPLIES] ?: true }
    val autoReconnect: Flow<Boolean> = context.dataStore.data.map { it[Keys.AUTO_RECONNECT] ?: true }
    val sendPhotosToAgent: Flow<Boolean> = context.dataStore.data.map { it[Keys.SEND_PHOTOS_TO_AGENT] ?: false }
    val notifyReplies: Flow<Boolean> = context.dataStore.data.map { it[Keys.NOTIFY_REPLIES] ?: true }
    val voiceName: Flow<String> = context.dataStore.data.map { it[Keys.VOICE_NAME] ?: "" }
    val voiceLanguage: Flow<String> = context.dataStore.data.map { it[Keys.VOICE_LANGUAGE] ?: "" }
    val boundMac: Flow<String?> = context.dataStore.data.map { it[Keys.BOUND_MAC] }
    val boundName: Flow<String?> = context.dataStore.data.map { it[Keys.BOUND_NAME] }

    /** Filenames already imported to the phone's Gallery, used to compute "new" items. */
    val importedFiles: Flow<Set<String>> = context.dataStore.data.map { it[Keys.IMPORTED_FILES] ?: emptySet() }

    suspend fun setHermesEndpoint(url: String) = context.dataStore.edit { it[Keys.HERMES_ENDPOINT] = url.trim() }
    suspend fun setGeminiApiKey(key: String) = context.dataStore.edit { it[Keys.GEMINI_API_KEY] = key.trim() }
    suspend fun setSpeakReplies(v: Boolean) = context.dataStore.edit { it[Keys.SPEAK_REPLIES] = v }
    suspend fun setAutoReconnect(v: Boolean) = context.dataStore.edit { it[Keys.AUTO_RECONNECT] = v }
    suspend fun setSendPhotosToAgent(v: Boolean) = context.dataStore.edit { it[Keys.SEND_PHOTOS_TO_AGENT] = v }
    suspend fun setNotifyReplies(v: Boolean) = context.dataStore.edit { it[Keys.NOTIFY_REPLIES] = v }
    suspend fun setVoiceName(name: String) = context.dataStore.edit { it[Keys.VOICE_NAME] = name }
    suspend fun setVoiceLanguage(tag: String) = context.dataStore.edit { it[Keys.VOICE_LANGUAGE] = tag }

    /** Record [names] as imported so they no longer count as "new". */
    suspend fun addImportedFiles(names: Collection<String>) = context.dataStore.edit {
        it[Keys.IMPORTED_FILES] = (it[Keys.IMPORTED_FILES] ?: emptySet()) + names
    }

    /** Forget [name] as imported (e.g. after deleting the app's copy), so it can count as new again. */
    suspend fun removeImportedFile(name: String) = context.dataStore.edit {
        it[Keys.IMPORTED_FILES] = (it[Keys.IMPORTED_FILES] ?: emptySet()) - name
    }

    suspend fun importedFilesNow(): Set<String> = importedFiles.first()

    suspend fun saveBoundDevice(mac: String, name: String?) = context.dataStore.edit {
        it[Keys.BOUND_MAC] = mac
        it[Keys.BOUND_NAME] = name ?: ""
    }

    suspend fun clearBoundDevice() = context.dataStore.edit {
        it.remove(Keys.BOUND_MAC)
        it.remove(Keys.BOUND_NAME)
    }

    suspend fun hermesEndpointNow(): String = hermesEndpoint.first()
    suspend fun geminiApiKeyNow(): String = geminiApiKey.first()
    suspend fun boundMacNow(): String? = boundMac.first()
}
