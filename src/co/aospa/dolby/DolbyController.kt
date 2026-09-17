/*
 * Copyright (C) 2023-24 Paranoid Android
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package co.aospa.dolby

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioManager.AudioPlaybackCallback
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.util.Log
import androidx.preference.PreferenceManager
import co.aospa.dolby.DolbyConstants.Companion.dlog
import co.aospa.dolby.DolbyConstants.DsParam

internal class DolbyController private constructor(private val context: Context) {
    private var dolbyEffect = DolbyAudioEffect(EFFECT_PRIORITY, audioSession = 0)
    private val audioManager = context.getSystemService(AudioManager::class.java)!!
    private val handler = Handler(context.mainLooper)
    private val stereoWideningSupported =
        context.getResources().getBoolean(R.bool.dolby_stereo_widening_supported)
    private val volumeLevelerSupported =
        context.getResources().getBoolean(R.bool.dolby_volume_leveler_supported)

    // Restore current profile on every media session
    private val playbackCallback =
        object : AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: List<AudioPlaybackConfiguration>) {
                val isPlaying =
                    configs.any {
                        it.playerState == AudioPlaybackConfiguration.PLAYER_STATE_STARTED
                    }
                dlog(TAG, "onPlaybackConfigChanged: isPlaying=$isPlaying")
                if (isPlaying) setCurrentProfile()
            }
        }

    // Restore current profile on audio device change
    private val audioDeviceCallback =
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
                dlog(TAG, "onAudioDevicesAdded")
                setCurrentProfile()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
                dlog(TAG, "onAudioDevicesRemoved")
                setCurrentProfile()
            }
        }

    private var registerCallbacks = false
        set(value) {
            if (field == value) return
            field = value
            dlog(TAG, "setRegisterCallbacks($value)")
            if (value) {
                audioManager.registerAudioPlaybackCallback(playbackCallback, handler)
                audioManager.registerAudioDeviceCallback(audioDeviceCallback, handler)
            } else {
                audioManager.unregisterAudioPlaybackCallback(playbackCallback)
                audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
            }
        }

    var dsOn: Boolean
        get() = dolbyEffect.dsOn.also { dlog(TAG, "getDsOn: $it") }
        set(value) {
            dlog(TAG, "setDsOn: $value")
            checkEffect()
            dolbyEffect.dsOn = value
            registerCallbacks = value
            if (value) setCurrentProfile()
        }

    var profile: Int
        get() = dolbyEffect.profile.also { dlog(TAG, "getProfile: $it") }
        set(value) {
            dlog(TAG, "setProfile: $value")
            checkEffect()
            dolbyEffect.profile = value
        }

    init {
        dlog(TAG, "initialized")
    }

    fun onBootCompleted() {
        dlog(TAG, "onBootCompleted")

        // Restore our main settings
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        dsOn = prefs.getBoolean(DolbyConstants.PREF_ENABLE, true)

        context.resources
            .getStringArray(R.array.dolby_profile_values)
            .map { it.toInt() }
            .forEach { profile ->
                // Reset dolby first to prevent it from loading bad settings
                dolbyEffect.resetProfileSpecificSettings(profile)
                // Now restore our profile-specific settings
                restoreSettings(profile)
            }

        // Finally restore the current profile.
        setCurrentProfile()
    }

    fun applySavedState() {
        dlog(TAG, "applySavedState")
        checkEffect()
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        dsOn = prefs.getBoolean(DolbyConstants.PREF_ENABLE, true)
        if (dsOn) setCurrentProfile()
    }

    fun setDolbyEnabled(enabled: Boolean) {
        dlog(TAG, "setDolbyEnabled: $enabled")
        checkEffect()
        dsOn = enabled
        setDsOnAndPersist(enabled)
    }

    fun getDolbyEnabled(): Boolean = dsOn

    val isStereoWideningSupported: Boolean
        get() = stereoWideningSupported

    val isVolumeLevelerSupported: Boolean
        get() = volumeLevelerSupported

    fun setCurrentProfilePersist(value: Int) {
        dlog(TAG, "setCurrentProfilePersist: $value")
        profile = value
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putString(DolbyConstants.PREF_PROFILE, value.toString())
            .apply()
    }

    private fun restoreSettings(profile: Int) {
        dlog(TAG, "restoreSettings(profile=$profile)")
        val prefs = context.getSharedPreferences("profile_$profile", Context.MODE_PRIVATE)
        setPreset(prefs.getString(DolbyConstants.PREF_PRESET, getPreset(profile))!!, profile)
        setIeqPreset(
            prefs.getString(DolbyConstants.PREF_IEQ, getIeqPreset(profile).toString())!!.toInt(),
            profile,
        )
        setHeadphoneVirtEnabled(
            prefs.getBoolean(DolbyConstants.PREF_HP_VIRTUALIZER, getHeadphoneVirtEnabled(profile)),
            profile,
        )
        setSpeakerVirtEnabled(
            prefs.getBoolean(DolbyConstants.PREF_SPK_VIRTUALIZER, getSpeakerVirtEnabled(profile)),
            profile,
        )
        setStereoWideningAmount(
            prefs
                .getString(
                    DolbyConstants.PREF_STEREO,
                    getStereoWideningAmount(profile).toString(),
                )!!
                .toInt(),
            profile,
        )
        setDialogueEnhancerAmount(
            prefs
                .getString(
                    DolbyConstants.PREF_DIALOGUE,
                    getDialogueEnhancerAmount(profile).toString(),
                )!!
                .toInt(),
            profile,
        )
        setBassEnhancerEnabled(
            prefs.getBoolean(DolbyConstants.PREF_BASS, getBassEnhancerEnabled(profile)),
            profile,
        )
        setBassLevel(
            readIntPref(prefs, DolbyConstants.PREF_BASS_LEVEL, getBassLevel(profile)),
            profile,
        )
        setBassCurve(
            readIntPref(prefs, DolbyConstants.PREF_BASS_CURVE, getBassCurve(profile)),
            profile,
        )
        setMidEnhancerEnabled(
            prefs.getBoolean(DolbyConstants.PREF_MID, getMidEnhancerEnabled(profile)),
            profile,
        )
        setMidLevel(
            readIntPref(prefs, DolbyConstants.PREF_MID_LEVEL, getMidLevel(profile)),
            profile,
        )
        setTrebleEnhancerEnabled(
            prefs.getBoolean(DolbyConstants.PREF_TREBLE, getTrebleEnhancerEnabled(profile)),
            profile,
        )
        setTrebleLevel(
            readIntPref(prefs, DolbyConstants.PREF_TREBLE_LEVEL, getTrebleLevel(profile)),
            profile,
        )
        setVolumeLevelerEnabled(
            prefs.getBoolean(DolbyConstants.PREF_VOLUME, getVolumeLevelerEnabled(profile)),
            profile,
        )
    }

    private fun checkEffect() {
        if (!dolbyEffect.hasControl()) {
            Log.w(TAG, "lost control, recreating effect")
            dolbyEffect.release()
            dolbyEffect = DolbyAudioEffect(EFFECT_PRIORITY, audioSession = 0)
        }
    }

    private fun readIntPref(prefs: SharedPreferences, key: String, defaultValue: Int): Int =
        when (val value = prefs.all[key]) {
            is Int -> value
            is String -> value.toIntOrNull() ?: defaultValue
            else -> defaultValue
        }

    private fun setCurrentProfile() {
        dlog(TAG, "setCurrentProfile")
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        profile = prefs.getString(DolbyConstants.PREF_PROFILE, "0" /*dynamic*/)!!.toInt()
    }

    fun setDsOnAndPersist(dsOn: Boolean) {
        this.dsOn = dsOn
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putBoolean(DolbyConstants.PREF_ENABLE, dsOn)
            .apply()
    }

    fun getProfileName(): String? {
        val profile = dolbyEffect.profile.toString()
        val profiles = context.resources.getStringArray(R.array.dolby_profile_values)
        val profileIndex = profiles.indexOf(profile)
        dlog(TAG, "getProfileName: profile=$profile index=$profileIndex")
        return if (profileIndex == -1) null
        else context.resources.getStringArray(R.array.dolby_profile_entries)[profileIndex]
    }

    fun resetProfileSpecificSettings() {
        dlog(TAG, "resetProfileSpecificSettings")
        checkEffect()
        dolbyEffect.resetProfileSpecificSettings()
        context.deleteSharedPreferences("profile_$profile")
    }

    fun getPreset(profile: Int = this.profile): String {
        val prefs = profilePrefs(profile)
        val stored = prefs.getString(DolbyConstants.PREF_PRESET, null)
        val gains =
            if (!stored.isNullOrEmpty()) {
                stored
            } else {
                dolbyEffect.getDapParameter(DsParam.GEQ_BAND_GAINS, profile).joinToString(",")
            }
        dlog(TAG, "getPreset: $gains")
        return gains
    }

    fun setPreset(value: String, profile: Int = this.profile) {
        dlog(TAG, "setPreset: $value")
        checkEffect()
        profilePrefs(profile)
            .edit()
            .putString(DolbyConstants.PREF_PRESET, value)
            .apply()
        applyEnhancedGains(profile)
    }

    // Recomputes the effective GEQ from the stored base preset plus the active
    // enhancers. The base preset itself is never modified.
    private fun applyEnhancedGains(profile: Int = this.profile) {
        checkEffect()
        val prefs = profilePrefs(profile)
        val gains = resolveBaseGains(prefs, profile)

        if (prefs.getBoolean(DolbyConstants.PREF_BASS, false)) {
            val level = readIntPref(prefs, DolbyConstants.PREF_BASS_LEVEL, 0)
            if (level > 0) {
                applyBassCurve(
                    gains,
                    level,
                    readIntPref(prefs, DolbyConstants.PREF_BASS_CURVE, 0),
                    1,
                )
            }
        }
        if (prefs.getBoolean(DolbyConstants.PREF_MID, false)) {
            val level = readIntPref(prefs, DolbyConstants.PREF_MID_LEVEL, 0)
            if (level > 0) {
                applyBandBoost(gains, MID_BANDS, (level * MID_GAIN_MULTIPLIER).toInt())
            }
        }
        if (prefs.getBoolean(DolbyConstants.PREF_TREBLE, false)) {
            val level = readIntPref(prefs, DolbyConstants.PREF_TREBLE_LEVEL, 0)
            if (level > 0) {
                applyBandBoost(gains, TREBLE_BANDS, (level * TREBLE_GAIN_MULTIPLIER).toInt())
            }
        }

        dolbyEffect.setDapParameter(DsParam.GEQ_BAND_GAINS, gains, profile)
        dlog(TAG, "applyEnhancedGains(profile=$profile): ${gains.joinToString(",")}")
    }

    // Returns the clean base preset, materializing it on first use so that
    // enhancers are always applied on top of an unmodified preset.
    private fun resolveBaseGains(prefs: SharedPreferences, profile: Int): IntArray {
        val stored = prefs.getString(DolbyConstants.PREF_PRESET, null)
        if (!stored.isNullOrEmpty()) {
            val gains = stored.split(",").mapNotNull { it.trim().toIntOrNull() }.toIntArray()
            if (gains.isNotEmpty()) return gains
        }
        val base = dolbyEffect.getDapParameter(DsParam.GEQ_BAND_GAINS, profile).copyOf()
        prefs.edit().putString(DolbyConstants.PREF_PRESET, base.joinToString(",")).apply()
        return base
    }

    private fun applyBandBoost(gains: IntArray, range: IntRange, gain: Int) {
        for (i in range) {
            if (i < gains.size) gains[i] = (gains[i] + gain).coerceIn(-150, 150)
        }
    }

    private fun profilePrefs(profile: Int): SharedPreferences =
        context.getSharedPreferences("profile_$profile", Context.MODE_PRIVATE)

    fun getPresetName(): String {
        val presets = context.resources.getStringArray(R.array.dolby_preset_values)
        val presetIndex = presets.indexOf(getPreset())
        return if (presetIndex == -1) {
            "Custom"
        } else {
            context.resources.getStringArray(R.array.dolby_preset_entries)[presetIndex]
        }
    }

    fun getHeadphoneVirtEnabled(profile: Int = this.profile) =
        dolbyEffect.getDapParameterBool(DsParam.HEADPHONE_VIRTUALIZER, profile).also {
            dlog(TAG, "getHeadphoneVirtEnabled: $it")
        }

    fun setHeadphoneVirtEnabled(value: Boolean, profile: Int = this.profile) {
        dlog(TAG, "setHeadphoneVirtEnabled: $value")
        checkEffect()
        dolbyEffect.setDapParameter(DsParam.HEADPHONE_VIRTUALIZER, value, profile)
    }

    fun getSpeakerVirtEnabled(profile: Int = this.profile) =
        dolbyEffect.getDapParameterBool(DsParam.SPEAKER_VIRTUALIZER, profile).also {
            dlog(TAG, "getSpeakerVirtEnabled: $it")
        }

    fun setSpeakerVirtEnabled(value: Boolean, profile: Int = this.profile) {
        dlog(TAG, "setSpeakerVirtEnabled: $value")
        checkEffect()
        dolbyEffect.setDapParameter(DsParam.SPEAKER_VIRTUALIZER, value, profile)
    }

    fun getBassEnhancerEnabled(profile: Int = this.profile): Boolean {
        val enabled = profilePrefs(profile).getBoolean(DolbyConstants.PREF_BASS, false)
        dlog(TAG, "getBassEnhancerEnabled: $enabled")
        return enabled
    }

    fun setBassEnhancerEnabled(value: Boolean, profile: Int = this.profile) {
        dlog(TAG, "setBassEnhancerEnabled: $value")
        checkEffect()
        profilePrefs(profile)
            .edit()
            .putBoolean(DolbyConstants.PREF_BASS, value)
            .apply()
        dolbyEffect.setDapParameter(DsParam.BASS_ENHANCER_ENABLE, value, profile)
        applyEnhancedGains(profile)
    }

    fun getBassLevel(profile: Int = this.profile): Int =
        readIntPref(profilePrefs(profile), DolbyConstants.PREF_BASS_LEVEL, 0)

    fun setBassLevel(level: Int, profile: Int = this.profile) {
        dlog(TAG, "setBassLevel: profile=$profile level=$level")
        if (level !in 0..100) {
            dlog(TAG, "setBassLevel: invalid level $level")
            return
        }
        checkEffect()
        profilePrefs(profile).edit().putInt(DolbyConstants.PREF_BASS_LEVEL, level).apply()
        applyEnhancedGains(profile)
    }

    fun getBassCurve(profile: Int = this.profile): Int =
        readIntPref(profilePrefs(profile), DolbyConstants.PREF_BASS_CURVE, 0)

    fun setBassCurve(curve: Int, profile: Int = this.profile) {
        dlog(TAG, "setBassCurve: profile=$profile curve=$curve")
        if (curve !in 0..2) {
            dlog(TAG, "setBassCurve: invalid curve $curve")
            return
        }
        checkEffect()
        profilePrefs(profile)
            .edit()
            .putString(DolbyConstants.PREF_BASS_CURVE, curve.toString())
            .apply()
        applyEnhancedGains(profile)
    }

    private fun applyBassCurve(gains: IntArray, level: Int, curve: Int, direction: Int) {
        val weights = BASS_CURVES.getOrElse(curve) { BASS_CURVES[0] }
        val baseGain = level * BASS_GAIN_MULTIPLIER
        for (i in weights.indices) {
            if (i >= gains.size) break
            val weightedGain = (baseGain * weights[i] * direction).toInt()
            gains[i] = (gains[i] + weightedGain).coerceIn(-150, 150)
        }
    }

    fun getMidEnhancerEnabled(profile: Int = this.profile): Boolean =
        profilePrefs(profile).getBoolean(DolbyConstants.PREF_MID, false)

    fun setMidEnhancerEnabled(value: Boolean, profile: Int = this.profile) {
        dlog(TAG, "setMidEnhancerEnabled: $value")
        profilePrefs(profile).edit().putBoolean(DolbyConstants.PREF_MID, value).apply()
        applyEnhancedGains(profile)
    }

    fun getMidLevel(profile: Int = this.profile): Int =
        readIntPref(profilePrefs(profile), DolbyConstants.PREF_MID_LEVEL, 0)

    fun setMidLevel(level: Int, profile: Int = this.profile) {
        dlog(TAG, "setMidLevel: profile=$profile level=$level")
        if (level !in 0..100) {
            dlog(TAG, "setMidLevel: invalid level $level")
            return
        }
        checkEffect()
        profilePrefs(profile).edit().putInt(DolbyConstants.PREF_MID_LEVEL, level).apply()
        applyEnhancedGains(profile)
    }

    fun getTrebleEnhancerEnabled(profile: Int = this.profile): Boolean =
        profilePrefs(profile).getBoolean(DolbyConstants.PREF_TREBLE, false)

    fun setTrebleEnhancerEnabled(value: Boolean, profile: Int = this.profile) {
        dlog(TAG, "setTrebleEnhancerEnabled: $value")
        profilePrefs(profile).edit().putBoolean(DolbyConstants.PREF_TREBLE, value).apply()
        applyEnhancedGains(profile)
    }

    fun getTrebleLevel(profile: Int = this.profile): Int =
        readIntPref(profilePrefs(profile), DolbyConstants.PREF_TREBLE_LEVEL, 0)

    fun setTrebleLevel(level: Int, profile: Int = this.profile) {
        dlog(TAG, "setTrebleLevel: profile=$profile level=$level")
        if (level !in 0..100) {
            dlog(TAG, "setTrebleLevel: invalid level $level")
            return
        }
        checkEffect()
        profilePrefs(profile).edit().putInt(DolbyConstants.PREF_TREBLE_LEVEL, level).apply()
        applyEnhancedGains(profile)
    }

    fun getVolumeLevelerEnabled(profile: Int = this.profile) =
        dolbyEffect.getDapParameterBool(DsParam.VOLUME_LEVELER_ENABLE, profile).also {
            dlog(TAG, "getVolumeLevelerEnabled: $it")
        }

    fun setVolumeLevelerEnabled(value: Boolean, profile: Int = this.profile) {
        dlog(TAG, "setVolumeLevelerEnabled: $value")
        checkEffect()
        dolbyEffect.setDapParameter(DsParam.VOLUME_LEVELER_ENABLE, value, profile)
    }

    fun getStereoWideningAmount(profile: Int = this.profile) =
        if (!stereoWideningSupported) {
            0
        } else {
            dolbyEffect.getDapParameterInt(DsParam.STEREO_WIDENING_AMOUNT, profile).also {
                dlog(TAG, "getStereoWideningAmount: $it")
            }
        }

    fun setStereoWideningAmount(value: Int, profile: Int = this.profile) {
        if (!stereoWideningSupported) return
        dlog(TAG, "setStereoWideningAmount: $value")
        checkEffect()
        dolbyEffect.setDapParameter(DsParam.STEREO_WIDENING_AMOUNT, value, profile)
    }

    fun getDialogueEnhancerAmount(profile: Int = this.profile): Int {
        val enabled = dolbyEffect.getDapParameterBool(DsParam.DIALOGUE_ENHANCER_ENABLE, profile)
        val amount =
            if (enabled) {
                dolbyEffect.getDapParameterInt(DsParam.DIALOGUE_ENHANCER_AMOUNT, profile)
            } else 0
        dlog(TAG, "getDialogueEnhancerAmount: enabled=$enabled amount=$amount")
        return amount
    }

    fun getDialogueEnhancerEnabled(profile: Int = this.profile): Boolean =
        dolbyEffect.getDapParameterBool(DsParam.DIALOGUE_ENHANCER_ENABLE, profile)

    fun setDialogueEnhancerAmount(value: Int, profile: Int = this.profile) {
        dlog(TAG, "setDialogueEnhancerAmount: $value")
        checkEffect()
        dolbyEffect.setDapParameter(DsParam.DIALOGUE_ENHANCER_ENABLE, (value > 0), profile)
        dolbyEffect.setDapParameter(DsParam.DIALOGUE_ENHANCER_AMOUNT, value, profile)
    }

    fun getIeqPreset(profile: Int = this.profile) =
        dolbyEffect.getDapParameterInt(DsParam.IEQ_PRESET, profile).also {
            dlog(TAG, "getIeqPreset: $it")
        }

    fun setIeqPreset(value: Int, profile: Int = this.profile) {
        dlog(TAG, "setIeqPreset: $value")
        checkEffect()
        dolbyEffect.setDapParameter(DsParam.IEQ_PRESET, value, profile)
    }

    companion object {
        private const val TAG = "DolbyController"
        private const val EFFECT_PRIORITY = 100

        private const val BASS_GAIN_MULTIPLIER = 1.4f
        private const val MID_GAIN_MULTIPLIER = 1.3f
        private const val TREBLE_GAIN_MULTIPLIER = 1.5f

        private val MID_BANDS = 5..13
        private val TREBLE_BANDS = 14..19

        private val BASS_CURVES =
            listOf(
                floatArrayOf(
                    1.00f, 1.00f, 0.95f, 0.90f, 0.80f, 0.70f, 0.55f, 0.40f, 0.25f, 0.15f,
                    0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f,
                ),
                floatArrayOf(
                    1.20f, 1.15f, 1.05f, 0.90f, 0.70f, 0.55f, 0.40f, 0.25f, 0.10f, 0.05f,
                    0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f,
                ),
                floatArrayOf(
                    0.90f, 0.95f, 1.00f, 1.00f, 0.90f, 0.75f, 0.60f, 0.45f, 0.30f, 0.20f,
                    0.10f, 0.05f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f,
                ),
            )

        @Volatile private var instance: DolbyController? = null

        fun getInstance(context: Context) =
            instance
                ?: synchronized(this) {
                    instance ?: DolbyController(context).also { instance = it }
                }
    }
}
