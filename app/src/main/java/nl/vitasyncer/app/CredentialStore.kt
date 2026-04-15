package nl.vitasyncer.app

import android.content.Context

class CredentialStore(context: Context) {
    private val prefs = context.getSharedPreferences("vitasyncer_prefs", Context.MODE_PRIVATE)

    var username: String
        get() = prefs.getString("vg_username", "") ?: ""
        set(value) { prefs.edit().putString("vg_username", value).apply() }

    var password: String
        get() = prefs.getString("vg_password", "") ?: ""
        set(value) { prefs.edit().putString("vg_password", value).apply() }

    var apiKey: String
        get() = prefs.getString("vg_api_key", "") ?: ""
        set(value) { prefs.edit().putString("vg_api_key", value).apply() }

    // Unix timestamp (seconden) van de laatste succesvolle sync
    var lastSyncTimestamp: Long
        get() = prefs.getLong("last_sync_ts", 0L)
        set(value) { prefs.edit().putLong("last_sync_ts", value).apply() }

    // Leesbaar statusbericht voor in de UI
    var lastSyncStatus: String
        get() = prefs.getString("last_sync_status", "Nog niet gesynchroniseerd") ?: "Nog niet gesynchroniseerd"
        set(value) { prefs.edit().putString("last_sync_status", value).apply() }

    // Auto-sync aan/uit
    var autoSyncEnabled: Boolean
        get() = prefs.getBoolean("auto_sync", false)
        set(value) { prefs.edit().putBoolean("auto_sync", value).apply() }

    // Mapping van bodymetric definition IDs (instelbaar via UI)
    var idWeight: Int
        get() = prefs.getInt("id_weight", 1)
        set(value) { prefs.edit().putInt("id_weight", value).apply() }

    var idBodyFat: Int
        get() = prefs.getInt("id_body_fat", 2)
        set(value) { prefs.edit().putInt("id_body_fat", value).apply() }

    var idMuscleMass: Int
        get() = prefs.getInt("id_muscle", 3)
        set(value) { prefs.edit().putInt("id_muscle", value).apply() }

    var idWater: Int
        get() = prefs.getInt("id_water", 4)
        set(value) { prefs.edit().putInt("id_water", value).apply() }

    var idBoneMass: Int
        get() = prefs.getInt("id_bone", 6)
        set(value) { prefs.edit().putInt("id_bone", value).apply() }
}
