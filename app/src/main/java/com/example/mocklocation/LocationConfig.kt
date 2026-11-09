package com.example.mocklocation

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * A single saved mock-location configuration.
 */
data class LocationConfig(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double = 0.0,
    val accuracy: Float = 1.0f
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("lat", latitude)
        put("lng", longitude)
        put("alt", altitude)
        put("acc", accuracy.toDouble())
    }

    companion object {
        fun fromJson(o: JSONObject): LocationConfig = LocationConfig(
            name = o.optString("name", "Unnamed"),
            latitude = o.optDouble("lat", 0.0),
            longitude = o.optDouble("lng", 0.0),
            altitude = o.optDouble("alt", 0.0),
            accuracy = o.optDouble("acc", 1.0).toFloat()
        )
    }
}

/**
 * Persists the list of saved mock-location configurations in SharedPreferences.
 */
class LocationConfigStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): MutableList<LocationConfig> {
        val raw = prefs.getString(KEY_CONFIGS, null) ?: return mutableListOf()
        return runCatching {
            val arr = JSONArray(raw)
            MutableList(arr.length()) { LocationConfig.fromJson(arr.getJSONObject(it)) }
        }.getOrElse { mutableListOf() }
    }

    fun save(configs: List<LocationConfig>) {
        val arr = JSONArray()
        configs.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_CONFIGS, arr.toString()).apply()
    }

    /** Adds or replaces (by name) a configuration and returns the updated list. */
    fun upsert(config: LocationConfig): MutableList<LocationConfig> {
        val list = load()
        val idx = list.indexOfFirst { it.name.equals(config.name, ignoreCase = true) }
        if (idx >= 0) list[idx] = config else list.add(config)
        save(list)
        return list
    }

    fun delete(name: String): MutableList<LocationConfig> {
        val list = load().apply { removeAll { it.name.equals(name, ignoreCase = true) } }
        save(list)
        return list
    }

    companion object {
        private const val PREFS = "mock_location_prefs"
        private const val KEY_CONFIGS = "configs"
    }
}
