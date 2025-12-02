package com.example.booxreader.reader

import org.json.JSONObject
import org.readium.r2.shared.publication.Locator

object LocatorJsonHelper {

    fun toJson(locator: Locator?): String? {
        locator ?: return null
        return locator.toJSON().toString()
    }

    fun fromJson(json: String?): Locator? {
        if (json.isNullOrBlank()) return null
        return try {
            Locator.fromJSON(JSONObject(json))
        } catch (_: Exception) {
            null
        }
    }
}
