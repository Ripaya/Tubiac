package tube.maniac.core.database

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tube.maniac.core.model.AniListUser
import tube.maniac.core.model.AppSettings
import javax.inject.Inject
import javax.inject.Singleton

private val Context.tubiacDataStore: DataStore<Preferences> by preferencesDataStore(name = "tubiac_preferences")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val settingsKey = stringPreferencesKey("app_settings_json")
    private val anilistTokenKey = stringPreferencesKey("anilist_oauth_token")
    private val anilistUserKey = stringPreferencesKey("anilist_user_json")
    private val appPinHashKey = stringPreferencesKey("app_lock_pin_hash")

    val settingsFlow: Flow<AppSettings> = context.tubiacDataStore.data.map { prefs ->
        val raw = prefs[settingsKey]
        if (raw.isNullOrBlank()) {
            AppSettings()
        } else {
            runCatching { json.decodeFromString<AppSettings>(raw) }.getOrDefault(AppSettings())
        }
    }

    val anilistTokenFlow: Flow<String?> = context.tubiacDataStore.data.map { it[anilistTokenKey] }

    val anilistUserFlow: Flow<AniListUser?> = context.tubiacDataStore.data.map { prefs ->
        prefs[anilistUserKey]?.let { raw ->
            runCatching { json.decodeFromString<AniListUser>(raw) }.getOrNull()
        }
    }

    suspend fun currentSettings(): AppSettings = settingsFlow.first()

    suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        context.tubiacDataStore.edit { prefs ->
            val current = prefs[settingsKey]?.let {
                runCatching { json.decodeFromString<AppSettings>(it) }.getOrNull()
            } ?: AppSettings()
            prefs[settingsKey] = json.encodeToString(transform(current))
        }
    }

    suspend fun replaceSettings(settings: AppSettings) {
        context.tubiacDataStore.edit { prefs ->
            prefs[settingsKey] = json.encodeToString(settings)
        }
    }

    suspend fun saveAniListAuth(token: String, user: AniListUser?) {
        context.tubiacDataStore.edit { prefs ->
            prefs[anilistTokenKey] = token
            if (user != null) {
                prefs[anilistUserKey] = json.encodeToString(user)
            }
        }
    }

    suspend fun clearAniListAuth() {
        context.tubiacDataStore.edit { prefs ->
            prefs.remove(anilistTokenKey)
            prefs.remove(anilistUserKey)
        }
    }

    suspend fun savePinHash(pinHash: String?) {
        context.tubiacDataStore.edit { prefs ->
            if (pinHash.isNullOrBlank()) prefs.remove(appPinHashKey) else prefs[appPinHashKey] = pinHash
        }
    }

    suspend fun getPinHash(): String? = context.tubiacDataStore.data.first()[appPinHashKey]
}
