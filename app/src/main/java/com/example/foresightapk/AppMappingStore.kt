package com.example.foresightapk

import android.content.Context

class AppMappingStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getOverrideLabel(packageName: String): String? {
        return preferences.getString(packageName, null)
    }

    fun saveOverride(packageName: String, vocabLabel: String) {
        preferences.edit().putString(packageName, vocabLabel).apply()
        ForeSightLog.info("Saved mapping override: $packageName -> $vocabLabel")
    }

    fun clearOverride(packageName: String) {
        preferences.edit().remove(packageName).apply()
        ForeSightLog.info("Cleared mapping override for package=$packageName")
    }

    fun getAllOverrides(): Map<String, String> {
        return preferences.all.mapNotNull { (packageName, value) ->
            val label = value as? String ?: return@mapNotNull null
            packageName to label
        }.toMap()
    }

    companion object {
        private const val PREFS_NAME = "app_mapping_overrides"
    }
}
