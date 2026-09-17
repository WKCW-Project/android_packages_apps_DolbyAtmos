/*
 * Copyright (C) 2026 tranQuila-Project
 *           (C) 2026 DolbyAtmos contributors
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package co.aospa.dolby

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioDeviceInfo
import co.aospa.dolby.DolbyConstants.Companion.dlog

internal class DeviceStateManager(private val context: Context) {

    fun deviceKey(device: AudioDeviceInfo): String {
        return when (device.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST -> {
                val addr = device.address?.takeIf { it.isNotBlank() } ?: "unknown"
                "bt_${addr.replace(":", "_")}"
            }
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE -> "wired_headphones"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "builtin_speaker"
            else -> "device_type_${device.type}"
        }
    }

    fun saveSnapshot(deviceKey: String, controller: DolbyController) {
        val profile = controller.profile
        val prefs = getDevicePrefs(deviceKey)
        val editor = prefs.edit()

        editor.putInt(KEY_VERSION, SNAPSHOT_VERSION)

        editor.putBoolean(KEY_DOLBY_ENABLED, controller.getDolbyEnabled())
        editor.putInt(KEY_PROFILE, profile)

        editor.putInt(KEY_IEQ, controller.getIeqPreset(profile))
        editor.putBoolean(KEY_HP_VIRT, controller.getHeadphoneVirtEnabled(profile))
        editor.putBoolean(KEY_SPK_VIRT, controller.getSpeakerVirtEnabled(profile))

        editor.putBoolean(KEY_DIALOGUE, controller.getDialogueEnhancerEnabled(profile))
        editor.putInt(KEY_DIALOGUE_AMT, controller.getDialogueEnhancerAmount(profile))

        editor.putBoolean(KEY_BASS_ENABLED, controller.getBassEnhancerEnabled(profile))
        editor.putInt(KEY_BASS_LEVEL, controller.getBassLevel(profile))
        editor.putInt(KEY_BASS_CURVE, controller.getBassCurve(profile))

        editor.putBoolean(KEY_TREBLE_ENABLED, controller.getTrebleEnhancerEnabled(profile))
        editor.putInt(KEY_TREBLE_LEVEL, controller.getTrebleLevel(profile))

        editor.putBoolean(KEY_MID_ENABLED, controller.getMidEnhancerEnabled(profile))
        editor.putInt(KEY_MID_LEVEL, controller.getMidLevel(profile))

        if (controller.isVolumeLevelerSupported) {
            editor.putBoolean(KEY_VOLUME, controller.getVolumeLevelerEnabled(profile))
        }
        if (controller.isStereoWideningSupported) {
            editor.putInt(KEY_STEREO, controller.getStereoWideningAmount(profile))
        }

        editor.putString(KEY_EQ_GAINS, controller.getPreset(profile))

        editor.apply()
        dlog(TAG, "Snapshot saved for device=$deviceKey profile=$profile v=$SNAPSHOT_VERSION")
    }

    fun restoreSnapshot(deviceKey: String, controller: DolbyController): Boolean {
        val prefs = getDevicePrefs(deviceKey)

        if (!prefs.contains(KEY_VERSION)) {
            dlog(TAG, "No snapshot for device=$deviceKey")
            return false
        }

        val storedVersion = prefs.getInt(KEY_VERSION, -1)
        if (storedVersion != SNAPSHOT_VERSION) {
            dlog(
                TAG,
                "Snapshot version mismatch for $deviceKey: " +
                    "stored=$storedVersion current=$SNAPSHOT_VERSION — discarding",
            )
            clearSnapshot(deviceKey)
            return false
        }

        return try {
            val enabled = prefs.getBoolean(KEY_DOLBY_ENABLED, true)
            val profile = prefs.getInt(KEY_PROFILE, 0)

            controller.setDolbyEnabled(enabled)
            controller.setCurrentProfilePersist(profile)

            prefs.getString(KEY_EQ_GAINS, null)?.let { gains ->
                controller.setPreset(gains, profile)
            }

            controller.setIeqPreset(prefs.getInt(KEY_IEQ, 0), profile)
            controller.setHeadphoneVirtEnabled(prefs.getBoolean(KEY_HP_VIRT, false), profile)
            controller.setSpeakerVirtEnabled(prefs.getBoolean(KEY_SPK_VIRT, false), profile)

            controller.setDialogueEnhancerAmount(prefs.getInt(KEY_DIALOGUE_AMT, 6), profile)

            controller.setBassEnhancerEnabled(prefs.getBoolean(KEY_BASS_ENABLED, false), profile)
            controller.setBassCurve(prefs.getInt(KEY_BASS_CURVE, 0), profile)
            controller.setBassLevel(prefs.getInt(KEY_BASS_LEVEL, 0), profile)

            controller.setTrebleEnhancerEnabled(prefs.getBoolean(KEY_TREBLE_ENABLED, false), profile)
            controller.setTrebleLevel(prefs.getInt(KEY_TREBLE_LEVEL, 0), profile)

            controller.setMidEnhancerEnabled(prefs.getBoolean(KEY_MID_ENABLED, false), profile)
            controller.setMidLevel(prefs.getInt(KEY_MID_LEVEL, 0), profile)

            if (controller.isVolumeLevelerSupported) {
                controller.setVolumeLevelerEnabled(prefs.getBoolean(KEY_VOLUME, false), profile)
            }
            if (controller.isStereoWideningSupported) {
                controller.setStereoWideningAmount(prefs.getInt(KEY_STEREO, 32), profile)
            }

            dlog(TAG, "Snapshot restored for device=$deviceKey profile=$profile v=$storedVersion")
            true
        } catch (e: Exception) {
            dlog(TAG, "Failed to restore snapshot for $deviceKey: ${e.message} — discarding")
            clearSnapshot(deviceKey)
            false
        }
    }

    fun hasSnapshot(deviceKey: String): Boolean {
        val prefs = getDevicePrefs(deviceKey)
        return prefs.contains(KEY_VERSION) &&
            prefs.getInt(KEY_VERSION, -1) == SNAPSHOT_VERSION
    }

    fun clearSnapshot(deviceKey: String) {
        getDevicePrefs(deviceKey).edit().clear().apply()
        dlog(TAG, "Snapshot cleared for device=$deviceKey")
    }

    private fun getDevicePrefs(deviceKey: String): SharedPreferences =
        context.getSharedPreferences("device_state_$deviceKey", Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "DeviceStateManager"

        const val SNAPSHOT_VERSION = 1

        private const val KEY_VERSION = "snapshot_version"
        private const val KEY_DOLBY_ENABLED = "enabled"
        private const val KEY_PROFILE = "profile"
        private const val KEY_IEQ = "ieq"
        private const val KEY_HP_VIRT = "hp_virt"
        private const val KEY_SPK_VIRT = "spk_virt"
        private const val KEY_DIALOGUE = "dialogue"
        private const val KEY_DIALOGUE_AMT = "dialogue_amt"
        private const val KEY_BASS_ENABLED = "bass_enabled"
        private const val KEY_BASS_LEVEL = "bass_level"
        private const val KEY_BASS_CURVE = "bass_curve"
        private const val KEY_TREBLE_ENABLED = "treble_enabled"
        private const val KEY_TREBLE_LEVEL = "treble_level"
        private const val KEY_MID_ENABLED = "mid_enabled"
        private const val KEY_MID_LEVEL = "mid_level"
        private const val KEY_VOLUME = "volume"
        private const val KEY_STEREO = "stereo"
        private const val KEY_EQ_GAINS = "eq_gains"
    }
}