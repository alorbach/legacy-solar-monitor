package com.alorbach.solarmonitor.data.cloud

import org.json.JSONArray
import org.json.JSONObject

data class DriveFileRef(
    val id: String,
    val name: String,
)

internal object DriveJson {
    fun stringField(json: String, field: String): String? {
        fun from(obj: JSONObject): String? {
            if (obj.has(field) && !obj.isNull(field)) {
                val value = obj.opt(field)
                if (value is String) return value.takeIf { it.isNotEmpty() }
            }
            val keys = obj.keys()
            while (keys.hasNext()) {
                val child = obj.optJSONObject(keys.next()) ?: continue
                from(child)?.let { return it }
            }
            return null
        }
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
        return from(obj)
    }

    fun booleanField(json: String, field: String): Boolean? {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (!obj.has(field) || obj.isNull(field)) return null
        return obj.optBoolean(field)
    }

    fun files(json: String): List<DriveFileRef> {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val array: JSONArray? = obj.optJSONArray("files")
        if (array == null) {
            val id = obj.optString("id").takeIf { it.isNotEmpty() } ?: return emptyList()
            return listOf(DriveFileRef(id, obj.optString("name")))
        }
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            DriveFileRef(id = id, name = item.optString("name"))
        }
    }

    fun jsonString(value: String): String = JSONObject.quote(value)

    fun driveQueryLiteral(value: String): String = value.replace("\\", "\\\\").replace("'", "\\'")
}
