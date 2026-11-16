/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.listentopage.settings

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import mozilla.components.feature.listentopage.voices.VoicePreferences

/**
 * Defines the contract for persisting and retrieving user settings related to the Listen to Page feature.
 *
 * Implementations are responsible for the underlying storage mechanism.
 *
 * @see [ListenSettings.Companion.inMemory] for a simple in-memory implementation suitable for testing or previews.
 */
interface ListenSettings : VoicePreferences {

    /** Whether the embedding app has made the feature available, for example through remote configuration. */
    val available: Boolean

    /** Whether the user has the feature turned on. */
    val enabled: StateFlow<ListenEnabledState>

    /**
     * Persists whether the user has the feature turned on.
     *
     * @param enabled `true` to turn the feature on.
     */
    suspend fun setEnabled(enabled: Boolean)

    companion object {
        /**
         * Creates a simple in-memory implementation of [ListenSettings].
         *
         * This implementation does not persist data across sessions and is intended for use in tests, Compose previews,
         * or other scenarios where a lightweight, non-persistent implementation is needed.
         *
         * @return An in-memory [ListenSettings] instance.
         */
        fun inMemory(
            enginePackageName: String = "",
            voiceIds: Map<String, String> = emptyMap(),
            available: Boolean = true,
            enabled: ListenEnabledState = ListenEnabledState.Enabled,
        ): ListenSettings =
            object : ListenSettings {
                private val savedVoiceIds =
                    voiceIds.mapKeys { (languageTag, _) -> enginePackageName to languageTag }.toMutableMap()
                private val _enabled = MutableStateFlow(enabled)

                override val available: Boolean = available

                override val enabled: StateFlow<ListenEnabledState> = _enabled.asStateFlow()

                override suspend fun setEnabled(enabled: Boolean) {
                    _enabled.value = ListenEnabledState.from(enabled)
                }

                override suspend fun getSelectedVoiceId(engine: String, languageTag: String): String? =
                    savedVoiceIds[engine to languageTag]

                override suspend fun setSelectedVoiceId(engine: String, languageTag: String, voiceId: String) {
                    savedVoiceIds[engine to languageTag] = voiceId
                }
            }

        /**
         * Creates a [DataStore]-backed implementation of [ListenSettings].
         *
         * Data is persisted across sessions using [Preferences].
         *
         * @param context The application [Context] used to access the data store.
         * @param available Whether the feature is available. Availability is not persisted here, so the embedding app
         *   should supply it each time the settings are created.
         * @param scope The scope that keeps [ListenSettings.enabled] up to date.
         * @return A persistent [ListenSettings] instance backed by [DataStore].
         */
        fun dataStore(context: Context, available: Boolean, scope: CoroutineScope): ListenSettings =
            DataStoreBackedSettings(context.dataStore, available, scope)
    }
}

/** Whether the user has turned the Listen to Page feature on. */
enum class ListenEnabledState {
    /** The stored value has not been read yet. */
    Loading,

    /** The user has never turned the feature on or off. */
    Unset,

    /** The user has turned the feature on. */
    Enabled,

    /** The user has turned the feature off. */
    Disabled;

    internal companion object {
        fun from(enabled: Boolean?) =
            when (enabled) {
                null -> Unset
                true -> Enabled
                false -> Disabled
            }
    }
}

/** Whether the feature can be shown to the user right now. It is only `true` once the user has turned it on. */
fun ListenSettings.shouldShow(): Boolean = available && enabled.value == ListenEnabledState.Enabled

internal class DataStoreBackedSettings(
    private val dataStore: DataStore<Preferences>,
    override val available: Boolean,
    scope: CoroutineScope,
) : ListenSettings {
    private val enabledKey = booleanPreferencesKey("enabled_key")

    // The hot flow will start in the Loading state, but then a null read from the preference file on first run
    // will update the state to Unset.
    override val enabled: StateFlow<ListenEnabledState> =
        dataStore.data
            .catch { exception ->
                if (exception is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw exception
                }
            }
            .map { preferences -> ListenEnabledState.from(preferences[enabledKey]) }
            .stateIn(scope, SharingStarted.Eagerly, initialValue = ListenEnabledState.Loading)

    override suspend fun setEnabled(enabled: Boolean) {
        dataStore.updateData {
            it.toMutablePreferences().also { preferences ->
                preferences[enabledKey] = enabled
            }
        }
    }

    override suspend fun getSelectedVoiceId(engine: String, languageTag: String): String? =
        dataStore.data.first()[selectedVoiceKey(engine, languageTag)]

    override suspend fun setSelectedVoiceId(engine: String, languageTag: String, voiceId: String) {
        dataStore.updateData {
            it.toMutablePreferences().also { preferences ->
                preferences[selectedVoiceKey(engine, languageTag)] = voiceId
            }
        }
    }
}

private val Context.dataStore: DataStore<Preferences> by
    preferencesDataStore(
        name = "listen_to_page_settings",
        produceMigrations = { listOf(EngineScopedVoicesMigration) },
    )

/** The key of the voice saved for [languageTag] in [engine]. */
internal fun selectedVoiceKey(engine: String, languageTag: String) =
    stringPreferencesKey("selected_voice/$engine/$languageTag")

/**
 * Moves the saved voice choices to keys that name the engine they were made in.
 *
 * Before, one engine's choices were kept at a time: `selected_voice_<language>`, with the engine they belonged to in
 * `engine_package_name_key`, and every choice was dropped when the engine changed. So the engine recorded there is the
 * engine of every choice still saved, and each one moves under it. Choices saved with no engine recorded cannot be
 * placed, and are dropped, as the next voice load would have dropped them.
 */
internal object EngineScopedVoicesMigration : DataMigration<Preferences> {
    private val oldEngineKey = stringPreferencesKey("engine_package_name_key")
    private const val OLD_PREFIX = "selected_voice_"

    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        currentData.asMap().keys.any { it == oldEngineKey || it.name.startsWith(OLD_PREFIX) }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val preferences = currentData.toMutablePreferences()
        val engine = preferences[oldEngineKey]
        val oldChoices = preferences.asMap().filterKeys { it.name.startsWith(OLD_PREFIX) }

        oldChoices.forEach { (key, voiceId) ->
            preferences.remove(key)
            if (engine != null && voiceId is String) {
                preferences[selectedVoiceKey(engine, key.name.removePrefix(OLD_PREFIX))] = voiceId
            }
        }
        preferences.remove(oldEngineKey)
        return preferences.toPreferences()
    }

    override suspend fun cleanUp() = Unit
}
