package com.bletoy.spoof

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class ManufacturerEntry(
    val id: Int,
    val name: String
)

data class SpoofDevice(
    val name: String,
    val category: String,
    val modelId: ByteArray,
    val description: String
)

data class AdvTemplate(
    val company_id: String? = null,
    val payload_header: String? = null,
    val random_len: Int = 0,
    val connectable: Boolean = false,
    val continuity_type: String? = null,
    val full_payload_len: Int = 0,
    val trim_payload_len: Int = 0,
    val prefix_normal: String? = null,
    val prefix_airtag: String? = null,
    val airtag_model_check: String? = null,
    val status: String? = null,
    val lid_open: String? = null,
    val encrypted_len: Int = 0,
    val pad: String? = null,
    val service_uuid: String? = null
)

private data class RawSpoofAction(val name: String, val action: String)
private data class RawSpoofApple(val name: String, val model: String)
private data class RawSpoofGoogle(val name: String, val data: String)

object DeviceProfiles {

    fun loadManufacturers(context: Context): Map<Int, String> {
        return try {
            val json = context.assets.open("manufacturers.json")
                .bufferedReader().use { it.readText() }
            val type = object : TypeToken<List<ManufacturerEntry>>() {}.type
            val list: List<ManufacturerEntry> = Gson().fromJson(json, type)
            list.associate { it.id to it.name }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun loadSpoofDevices(context: Context): List<SpoofDevice> {
        return try {
            val json = context.assets.open("spoof_devices.json")
                .bufferedReader().use { it.readText() }
            val root = Gson().fromJson(json, Map::class.java)
            val devices = mutableListOf<SpoofDevice>()

            // Apple Nearby Action: 1-byte action code
            val actions = Gson().fromJson<List<RawSpoofAction>>(
                Gson().toJson(root["apple_action"]),
                object : TypeToken<List<RawSpoofAction>>() {}.type
            )
            actions?.forEach { a ->
                val byte = a.action.removePrefix("0x").toInt(16).toByte()
                devices.add(SpoofDevice(a.name, "apple_action", byteArrayOf(byte), a.name))
            }

            // Apple Proximity Pairing: 2-byte model ID
            val apples = Gson().fromJson<List<RawSpoofApple>>(
                Gson().toJson(root["apple"]),
                object : TypeToken<List<RawSpoofApple>>() {}.type
            )
            apples?.forEach { a ->
                val hex = a.model.removePrefix("0x")
                val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                devices.add(SpoofDevice(a.name, "apple", bytes, a.name))
            }

            // Google Fast Pair: 3-byte model ID
            val googles = Gson().fromJson<List<RawSpoofGoogle>>(
                Gson().toJson(root["google"]),
                object : TypeToken<List<RawSpoofGoogle>>() {}.type
            )
            googles?.forEach { g ->
                val bytes = g.data.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                devices.add(SpoofDevice(g.name, "google", bytes, g.name))
            }

            devices
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun loadAdvTemplates(context: Context): Map<String, AdvTemplate> {
        return try {
            val json = context.assets.open("adv_templates.json")
                .bufferedReader().use { it.readText() }
            val type = object : TypeToken<Map<String, AdvTemplate>>() {}.type
            Gson().fromJson(json, type)
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
