/*
 * SPDX-FileCopyrightText: 2026 kenway214
 * SPDX-License-Identifier: Apache-2.0
 */

package co.aospa.dolby

import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.IBinder
import androidx.preference.PreferenceManager
import co.aospa.dolby.DolbyConstants.Companion.PREF_DEVICE_STATE_MEMORY
import co.aospa.dolby.DolbyConstants.Companion.dlog

class DolbyEffectService : Service() {

    private val audioManager by lazy { getSystemService(AudioManager::class.java)!! }
    private val handler = Handler()
    private val controller by lazy { DolbyController.getInstance(this) }
    private val deviceStateManager by lazy { DeviceStateManager(this) }
    private var previousActiveDevice: AudioDeviceInfo? = null

    private val isDeviceStateMemoryEnabled: Boolean
        get() =
            PreferenceManager.getDefaultSharedPreferences(this)
                .getBoolean(PREF_DEVICE_STATE_MEMORY, false)

    private val audioDeviceCallback =
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
                dlog(TAG, "Devices added: ${addedDevices.map { it.productName }}")
                handleDeviceChange()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
                dlog(TAG, "Devices removed: ${removedDevices.map { it.productName }}")
                if (isDeviceStateMemoryEnabled) {
                    removedDevices.forEach { device ->
                        val key = deviceStateManager.deviceKey(device)
                        dlog(TAG, "Snapshotting state for removed device: $key")
                        deviceStateManager.saveSnapshot(key, controller)
                    }
                }
                handleDeviceChange()
            }
        }

    private val playbackCallback =
        object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: List<AudioPlaybackConfiguration>) {
                val isPlaying =
                    configs.any {
                        it.playerState == AudioPlaybackConfiguration.PLAYER_STATE_STARTED
                    }
                if (isPlaying) {
                    controller.applySavedState()
                }
            }
        }

    override fun onCreate() {
        super.onCreate()

        val currentDevice = getCurrentOutputDevice()
        if (currentDevice != null) {
            previousActiveDevice = currentDevice
            if (isDeviceStateMemoryEnabled) {
                val key = deviceStateManager.deviceKey(currentDevice)
                val restored = deviceStateManager.restoreSnapshot(key, controller)
                if (!restored) controller.applySavedState()
            } else {
                controller.applySavedState()
            }
        } else {
            controller.applySavedState()
        }

        audioManager.registerAudioDeviceCallback(audioDeviceCallback, handler)
        audioManager.registerAudioPlaybackCallback(playbackCallback, handler)
        dlog(TAG, "Dolby effect service created")
    }

    private fun handleDeviceChange() {
        val newDevice = getCurrentOutputDevice()
        val oldDevice = previousActiveDevice

        if (oldDevice != null && isDeviceStateMemoryEnabled) {
            val oldKey = deviceStateManager.deviceKey(oldDevice)
            dlog(TAG, "Saving snapshot for previous device: $oldKey")
            deviceStateManager.saveSnapshot(oldKey, controller)
        }

        if (newDevice != null) {
            if (isDeviceStateMemoryEnabled) {
                val newKey = deviceStateManager.deviceKey(newDevice)
                dlog(TAG, "Restoring snapshot for new device: $newKey")
                val restored = deviceStateManager.restoreSnapshot(newKey, controller)
                if (!restored) {
                    dlog(TAG, "First time device, applying saved state as base")
                    controller.applySavedState()
                }
            } else {
                dlog(TAG, "Device state memory disabled, applying saved state")
                controller.applySavedState()
            }
            previousActiveDevice = newDevice
        } else {
            controller.applySavedState()
            previousActiveDevice = null
        }
    }

    private fun getCurrentOutputDevice(): AudioDeviceInfo? {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

        val priorityOrder =
            listOf(
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_BLE_SPEAKER,
                AudioDeviceInfo.TYPE_BLE_BROADCAST,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            )

        for (type in priorityOrder) {
            devices.firstOrNull { it.type == type }?.let {
                return it
            }
        }
        return devices.firstOrNull()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        controller.applySavedState()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isDeviceStateMemoryEnabled) {
            previousActiveDevice?.let { device ->
                val key = deviceStateManager.deviceKey(device)
                deviceStateManager.saveSnapshot(key, controller)
            }
        }
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        audioManager.unregisterAudioPlaybackCallback(playbackCallback)
        handler.removeCallbacksAndMessages(null)
        dlog(TAG, "Dolby effect service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "DolbyEffectService"

        fun start(context: Context) {
            val intent = Intent(context, DolbyEffectService::class.java)
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, DolbyEffectService::class.java)
            context.stopService(intent)
        }
    }
}