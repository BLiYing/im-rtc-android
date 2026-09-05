package com.imrtc.engine.webrtc

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import com.imrtc.engine.log.IMRTCLog

/**
 * 通话音频路由。**这一层的每一行都是平台规则，不是我们的设计**（CONVENTIONS §8）。
 *
 * 三件必须做对的事：
 * 1. **模式必须是 `MODE_IN_COMMUNICATION`**：不设就没有硬件回声消除，自己会听到自己——
 *    而那听起来像「对方设备有问题」，很容易查错方向（iOS 上踩过同一个坑）。
 * 2. **必须申请音频焦点**：不申请的话，别的应用放音乐时不会让路，通话被盖住。
 * 3. **路由 API 分两代**：Android 12（API 31）起用 `setCommunicationDevice()`，
 *    之下只能用已废弃的 `setSpeakerphoneOn()`。两条路都要写，**并说清楚在哪个版本上验过**。
 */
internal class IMAudioRouter(context: Context) {

    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var focusRequest: AudioFocusRequest? = null
    private var savedMode = AudioManager.MODE_NORMAL

    fun start() {
        savedMode = audioManager.mode
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        requestFocus()
        // 默认走听筒：这是「打电话」的语义。视频通话由上层调 setSpeakerOn(true)。
        setSpeakerOn(false)
    }

    fun stop() {
        abandonFocus()
        clearCommunicationDevice()
        audioManager.mode = savedMode
    }

    fun setSpeakerOn(on: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val target = if (on) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            val device = audioManager.availableCommunicationDevices.firstOrNull { it.type == target }
            if (device == null) {
                IMRTCLog.w("audio", "找不到目标音频设备（type=$target），保持原样")
                return
            }
            val ok = audioManager.setCommunicationDevice(device)
            IMRTCLog.i("audio", "切到 ${if (on) "扬声器" else "听筒"}：$ok（API 31+ 路径）")
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = on
            IMRTCLog.i("audio", "切到 ${if (on) "扬声器" else "听筒"}（API 31 以下的旧路径）")
        }
    }

    private fun requestFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes)
                .build()
            focusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            )
        }
    }

    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    private fun clearCommunicationDevice() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = false
        }
    }
}
