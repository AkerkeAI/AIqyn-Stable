package com.aiqyn.safestudio.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

enum class Mode(val displayName: String) {
    EVERYDAY("Everyday"),
    PARENT("Parent"),
    DEAF("Deaf");

    companion object {
        fun fromStored(value: String): Mode {
            return entries.firstOrNull { it.name == value } ?: EVERYDAY
        }
    }
}

enum class Language(val displayName: String, val code: String) {
    ENGLISH("English", "en"),
    RUSSIAN("Русский", "ru"),
    KAZAKH("Қазақша", "kk"),
    TURKISH("Türkçe", "tr"),
    SPANISH("Español", "es"),
    ARABIC("العربية", "ar"),
    CHINESE("简体中文", "zh"),
    GERMAN("Deutsch", "de");

    companion object {
        fun fromStored(value: String): Language {
            return entries.firstOrNull { it.name == value } ?: ENGLISH
        }

        fun fromSystem(code: String): Language {
            return entries.find { it.code == code } ?: ENGLISH
        }
    }
}

data class UserState(
    val registered: Boolean,
    val name: String,
    val mode: Mode,
    val language: Language,
    val selectedSounds: Set<String>,
    val nameRecognitionEnabled: Boolean = false,
    val namePhoneticVariants: Set<String> = emptySet(),
    val nameEnvelope: List<Float> = emptyList()
)

class UserPreferencesRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun getUserState(): UserState {
        val mode = Mode.fromStored(prefs.getString(KEY_MODE, Mode.EVERYDAY.name) ?: Mode.EVERYDAY.name)
        val defaultSounds = getDefaultSoundsForMode(mode)
        val savedSounds = prefs.getStringSet(KEY_SELECTED_SOUNDS, null)
        
        val envelopeString = prefs.getString(KEY_NAME_ENVELOPE, "") ?: ""
        val envelope = if (envelopeString.isEmpty()) emptyList() 
                      else envelopeString.split(",").mapNotNull { it.toFloatOrNull() }

        return UserState(
            registered = prefs.getBoolean(KEY_REGISTERED, false),
            name = prefs.getString(KEY_NAME, "") ?: "",
            mode = mode,
            language = Language.fromStored(prefs.getString(KEY_LANGUAGE, Language.ENGLISH.name) ?: Language.ENGLISH.name),
            selectedSounds = savedSounds ?: defaultSounds,
            nameRecognitionEnabled = prefs.getBoolean(KEY_NAME_RECOGNITION, false),
            namePhoneticVariants = prefs.getStringSet(KEY_NAME_VARIANTS, emptySet()) ?: emptySet(),
            nameEnvelope = envelope
        )
    }

    fun getDefaultSoundsForMode(mode: Mode): Set<String> {
        return when (mode) {
            Mode.EVERYDAY -> setOf("SIREN", "VEHICLE_HORN", "EMERGENCY_ALARM")
            Mode.PARENT -> setOf("SIREN", "VEHICLE_HORN", "EMERGENCY_ALARM", "CHILD_CRYING", "BABY_CRY")
            Mode.DEAF -> setOf("SIREN", "VEHICLE_HORN", "EMERGENCY_ALARM", "DOORBELL", "FIRE_ALARM")
        }
    }

    fun isTtsCheckDone(): Boolean = prefs.getBoolean(KEY_TTS_CHECK, false)

    fun setTtsCheckDone() {
        prefs.edit().putBoolean(KEY_TTS_CHECK, true).apply()
    }

    fun saveRegistration(name: String, mode: Mode, language: Language, sounds: Set<String>? = null, nameRecognition: Boolean = false, variants: Set<String> = emptySet(), envelope: List<Float> = emptyList()) {
        val finalSounds = sounds ?: getDefaultSoundsForMode(mode)
        prefs.edit()
            .putBoolean(KEY_REGISTERED, true)
            .putString(KEY_NAME, name)
            .putString(KEY_MODE, mode.name)
            .putString(KEY_LANGUAGE, language.name)
            .putStringSet(KEY_SELECTED_SOUNDS, finalSounds)
            .putBoolean(KEY_NAME_RECOGNITION, nameRecognition)
            .putStringSet(KEY_NAME_VARIANTS, variants)
            .putString(KEY_NAME_ENVELOPE, envelope.joinToString(","))
            .apply()
    }

    fun saveSelectedSounds(sounds: Set<String>) {
        prefs.edit().putStringSet(KEY_SELECTED_SOUNDS, sounds).apply()
    }

    fun clearProfile() {
        prefs.edit().clear().apply()
    }

    // Context Reminders methods
    fun getContextReminders(): List<ContextReminder> {
        val remindersJson = prefs.getString(KEY_CONTEXT_REMINDERS, "[]") ?: "[]"
        return parseReminders(remindersJson)
    }

    fun saveContextReminder(reminder: ContextReminder) {
        val reminders = getContextReminders().toMutableList()
        val existingIndex = reminders.indexOfFirst { it.id == reminder.id }
        if (existingIndex >= 0) {
            reminders[existingIndex] = reminder
        } else {
            reminders.add(reminder)
        }
        saveReminders(reminders)
    }

    fun deleteContextReminder(id: String) {
        val reminders = getContextReminders().filter { it.id != id }
        saveReminders(reminders)
    }

    fun toggleContextReminder(id: String) {
        val reminders = getContextReminders().map {
            if (it.id == id) it.copy(enabled = !it.enabled) else it
        }
        saveReminders(reminders)
    }

    private fun saveReminders(reminders: List<ContextReminder>) {
        val jsonArray = JSONArray()
        reminders.forEach { reminder ->
            val json = JSONObject()
            json.put("id", reminder.id)
            json.put("event", reminder.event)
            json.put("time", reminder.time)
            json.put("location", reminder.location)
            json.put("destination", reminder.destination)
            json.put("keywords", JSONArray(reminder.keywords.toList()))
            json.put("description", reminder.description)
            json.put("enabled", reminder.enabled)
            jsonArray.put(json)
        }
        prefs.edit().putString(KEY_CONTEXT_REMINDERS, jsonArray.toString()).apply()
    }

    private fun parseReminders(jsonString: String): List<ContextReminder> {
        try {
            val jsonArray = JSONArray(jsonString)
            val reminders = mutableListOf<ContextReminder>()
            for (i in 0 until jsonArray.length()) {
                val json = jsonArray.getJSONObject(i)
                val keywordsArray = json.optJSONArray("keywords")
                val keywords = mutableSetOf<String>()
                if (keywordsArray != null) {
                    for (j in 0 until keywordsArray.length()) {
                        keywords.add(keywordsArray.getString(j))
                    }
                }
                reminders.add(
                    ContextReminder(
                        id = json.getString("id"),
                        event = json.getString("event"),
                        time = json.optString("time", ""),
                        location = json.optString("location", ""),
                        destination = json.optString("destination", ""),
                        keywords = keywords,
                        description = json.optString("description", ""),
                        enabled = json.optBoolean("enabled", true)
                    )
                )
            }
            return reminders
        } catch (e: Exception) {
            return emptyList()
        }
    }

    companion object {
        private const val PREF_NAME = "aiqyn_prefs"
        private const val KEY_REGISTERED = "registered"
        private const val KEY_NAME = "name"
        private const val KEY_MODE = "mode"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_TTS_CHECK = "tts_check_done"
        private const val KEY_SELECTED_SOUNDS = "selected_sounds"
        private const val KEY_NAME_RECOGNITION = "name_recognition"
        private const val KEY_NAME_VARIANTS = "name_variants"
        private const val KEY_NAME_ENVELOPE = "name_envelope"
        private const val KEY_CONTEXT_REMINDERS = "context_reminders"
    }
}
