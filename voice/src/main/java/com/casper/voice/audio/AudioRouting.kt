package com.casper.voice.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/** Where the voice is heard. */
enum class AudioRoute {
    /** A headset when one is connected (Bluetooth, then wired), otherwise the loudspeaker. */
    Auto,
    Speaker,
    /** Held to the ear, like a phone call. */
    Earpiece
}

/**
 * Puts the phone in voice-call mode and sends the call's audio to [route],
 * following headsets as they come and go.
 *
 * Voice-call mode is what lets the phone's echo canceller work; without
 * routing, voice-call audio plays from the earpiece, which is too quiet for
 * a hands-free assistant. Needs `MODIFY_AUDIO_SETTINGS`, which the library's
 * manifest declares.
 */
class AudioRouting(context: Context, private val route: AudioRoute = AudioRoute.Auto) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var callback: AudioDeviceCallback? = null
    private var previousMode = AudioManager.MODE_NORMAL

    fun start() {
        previousMode = audio.mode
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        apply()
        if (callback == null) {
            callback = object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = apply()
                override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = apply()
            }
            audio.registerAudioDeviceCallback(callback, null)
        }
    }

    fun stop() {
        callback?.let { audio.unregisterAudioDeviceCallback(it) }
        callback = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audio.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = false
        }
        audio.mode = previousMode
    }

    private fun apply() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val device = chosen(audio.availableCommunicationDevices)
            if (device != null) audio.setCommunicationDevice(device)
        } else {
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = route == AudioRoute.Speaker || (route == AudioRoute.Auto && !headsetConnected())
        }
    }

    private fun chosen(devices: List<AudioDeviceInfo>): AudioDeviceInfo? {
        fun of(vararg types: Int) = devices.firstOrNull { it.type in types }
        return when (route) {
            AudioRoute.Speaker -> of(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
            AudioRoute.Earpiece -> of(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
            AudioRoute.Auto -> of(*BLUETOOTH) ?: of(*WIRED) ?: of(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        }
    }

    private fun headsetConnected(): Boolean =
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in BLUETOOTH || it.type in WIRED }

    private companion object {
        val BLUETOOTH = intArrayOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER
        )
        val WIRED = intArrayOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET
        )
    }
}
