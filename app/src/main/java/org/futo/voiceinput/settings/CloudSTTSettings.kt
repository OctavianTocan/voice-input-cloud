package org.futo.voiceinput.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * Cloud STT Settings
 */

// Toggle between local Whisper and cloud STT
val USE_CLOUD_STT = SettingDefinition(
    key = booleanPreferencesKey("use_cloud_stt"),
    default = false
)

// Cloud STT endpoint URL
val CLOUD_STT_ENDPOINT = SettingDefinition(
    key = stringPreferencesKey("cloud_stt_endpoint"),
    default = ""  // e.g., "https://api.openai.com/v1/audio/transcriptions"
)

// Cloud STT API key  
val CLOUD_STT_API_KEY = SettingDefinition(
    key = stringPreferencesKey("cloud_stt_api_key"),
    default = ""
)

// Cloud STT provider for UI display
val CLOUD_STT_PROVIDER = SettingDefinition(
    key = stringPreferencesKey("cloud_stt_provider"),
    default = "custom"  // Options: "openai", "google", "azure", "aws", "custom"
)
