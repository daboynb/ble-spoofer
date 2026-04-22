# ble-spoofer

> **⚠️ DEVELOPMENT STATUS:** This project is currently under active development and is not yet considered stable.

Android (Kotlin + Jetpack Compose) app that builds Apple Continuity and Google Fast Pair BLE advertising payloads and broadcasts them from the phone's `BluetoothLeAdvertiser`. A correctly-framed frame is indistinguishable from a real AirPods case, AirTag, or Pixel Buds — both protocols are broadcast-only and unauthenticated.

> **ble-spoofer is for authorized security testing, education, and research on devices you own. Beacon spoofing can DoS targets with notification spam and mislead proximity-based features. Do not use against devices you do not control.**

## What you can pretend to be

Payloads come from `spoof_devices.json`:

- **`apple_action`** — Nearby Action packets (AppleTV setup, Apple Watch sync, AirPlay, HomePod setup, Handoff, "New AirPods nearby"). Fires a system dialog on the target.
- **`apple`** — Device pairing packets (AirPods 1/2/Pro/Max, AirTag, BeatsX, Solo Pro). Triggers the big "pair" UI.
- **`google`** — Fast Pair frames for Pixel Buds / Pixel Stand / partnered devices.

Each entry carries a `modelId` that identifies the pretended product to the target OS.

## Why JSON templates

Apple changes Continuity across iOS releases; Google updates Fast Pair annually. Hardcoded frames would mean a new APK every time a byte renumbers. The JSON template approach lets every device definition reuse a shared structural template, parameterized only by the parts Apple/Google care about (company ID, continuity type, payload length, AirTag vs. normal prefix, status byte, …). New devices = one JSON entry.

## Architecture

- **`BleManager`** — owns the `BluetoothLeAdvertiser`, tracks every `AdvertiseCallback` so `stopAdvertising` is always paired with `startAdvertising`. Android's BLE stack leaks callback refs otherwise and rate-limits itself to zero within seconds.
- **Spam mode** — "fire a new AirPods notification every 500 ms" loop. Each tick: stop previous advertisement, rebuild payload with fresh `SecureRandom` bytes (AirTag serial must vary to defeat on-device deduplication), start, wait, repeat.
- **31-byte budget check** — legacy BLE adv is 31 bytes max. Code computes total payload size before advertising and logs fits/overflow. On devices with LE Extended Advertising (API 26+, `adapter.isLeExtendedAdvertisingSupported`), longer payloads use the extended APIs; otherwise legacy fallback.

## Requirements

- Android **API 26+** (Android 8.0, for LE advertising APIs; extended adv needs API 26+ hardware support).
- A phone with a BLE advertiser. Most do; a few low-end Qualcomm chipsets don't.
- No root required.

## Build

```bash
./gradlew assembleDebug
./gradlew installDebug
```

## Mitigation (target side)

- iOS: disable Handoff + Nearby Sharing to kill the notifications.
- Android: disable Fast Pair in Bluetooth settings.
- Neither is off by default.
