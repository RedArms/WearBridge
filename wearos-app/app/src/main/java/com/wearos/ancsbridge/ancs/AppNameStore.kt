package com.wearos.ancsbridge.ancs

import android.content.Context

/**
 * App display names as the iPhone shows them (localized, e.g. "Téléphone"),
 * fetched once per app with ANCS GetAppAttributes and kept across restarts.
 * Until an app's name is known, [NotificationLook.fallbackAppName] is used.
 */
class AppNameStore(context: Context) {

    companion object {
        private const val PREFS = "ancs_app_names"
        private const val MAX_NAME_LENGTH = 64
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val names = HashMap<String, String>()
    /** Apps asked for this session, so a failed request isn't repeated for every notification. */
    private val requested = HashSet<String>()

    init {
        prefs.all.forEach { (appId, name) -> if (name is String) names[appId] = name }
    }

    /** The iPhone's name for the app, or a readable fallback. */
    fun displayName(appIdentifier: String): String =
        names[appIdentifier] ?: NotificationLook.fallbackAppName(appIdentifier)

    /** True the first time an app without a known name shows up in this session. */
    fun shouldRequest(appIdentifier: String): Boolean =
        appIdentifier.isNotEmpty() && appIdentifier !in names && requested.add(appIdentifier)

    fun save(appIdentifier: String, displayName: String) {
        val name = displayName.trim().take(MAX_NAME_LENGTH)
        if (appIdentifier.isEmpty() || name.isEmpty() || names[appIdentifier] == name) return
        names[appIdentifier] = name
        prefs.edit().putString(appIdentifier, name).apply()
    }
}
