package com.imrtc.engine.webrtc

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.imrtc.engine.log.IMRTCLog

/**
 * 通话音频路由。**这一层的每一行都是平台规则，不是我们的设计**（CONVENTIONS §8）。
 *
 * 三件必须做对的事：
 * 1. **模式必须是 `MODE_IN_COMMUNICATION`**：不设就没有硬件回声消除，自己会听到自己——
 *    而那听起来像「对方设备有问题」，很容易查错方向（iOS 上踩过同一个坑）。
 * 2. **必须申请音频焦点**：不申请的话，别的应用放音乐时不会让路，通话被盖住。
 * 3. **路由 API 分两代**：Android 12（API 31）起用 `setCommunicationDevice()`，
 *    之下只能用已废弃的 `setSpeakerphoneOn()`（蓝牙还要自己 `startBluetoothSco`）。两条路都要写，**并说清楚在哪个版本上验过**。
 *
 * 选哪台设备的规则在 [IMAudioRoutePolicy]：扬声器键关着时**跟随系统**（耳机 / 蓝牙优先，最后才是听筒）。
 */
internal class IMAudioRouter(context: Context) {

    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var focusRequest: AudioFocusRequest? = null
    private var savedMode = AudioManager.MODE_NORMAL

    /** 用户按下了扬声器键。**关着 = 跟随系统**（见 [IMAudioRoutePolicy]），不是「钉死听筒」。 */
    @Volatile
    private var speakerForced = false

    /** API 31 以下自己拉起的蓝牙 SCO，停的时候要成对关掉。 */
    private var scoStarted = false

    /**
     * 耳机 / 蓝牙插拔：**跟随系统时重新选路**。原先没注册，插拔这件事 SDK 压根不知道——
     * 通话中连上蓝牙耳机，声音照旧从听筒出来。强制外放时不动（按钮还亮着，声音就该在扬声器）。
     */
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) =
            onDevicesChanged("接入", addedDevices)

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) =
            onDevicesChanged("断开", removedDevices)
    }

    fun start() {
        savedMode = audioManager.mode
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        requestFocus()
        speakerForced = false
        // 回调派到主线程：选路只在这条线程与调用方线程上发生，applyRoute 自己是幂等的。
        audioManager.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))
        // 默认跟随系统：接着耳机就走耳机，都没有才是听筒（「打电话」的语义）。视频通话由上层调 setSpeakerOn(true)。
        applyRoute("开始通话")
    }

    fun stop() {
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        abandonFocus()
        stopSco()
        clearCommunicationDevice()
        audioManager.mode = savedMode
    }

    fun setSpeakerOn(on: Boolean) {
        speakerForced = on
        applyRoute(if (on) "打开扬声器" else "关闭扬声器")
    }

    private fun onDevicesChanged(what: String, devices: Array<out AudioDeviceInfo>) {
        val types = devices.map { it.type }
        if (!IMAudioRoutePolicy.touchesExternal(types)) return
        IMRTCLog.i("audio", "音频设备$what：types=$types speakerForced=$speakerForced")
        if (!speakerForced) applyRoute("设备$what")
    }

    private fun applyRoute(why: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = audioManager.availableCommunicationDevices
            val type = IMAudioRoutePolicy.pick(speakerForced, devices.map { it.type })
            val device = devices.firstOrNull { it.type == type }
            if (device == null) {
                // 挑不出来就交还系统默认，不硬选（原先是「保持原样」，可能停在一台已经拔掉的设备上）。
                audioManager.clearCommunicationDevice()
                IMRTCLog.w("audio", "$why：没有可选的通话设备（speakerForced=$speakerForced），交还系统默认")
                return
            }
            val ok = audioManager.setCommunicationDevice(device)
            IMRTCLog.i("audio", "$why：通话声音走 type=${device.type}：$ok（API 31+ 路径）")
        } else {
            applyLegacyRoute(why)
        }
    }

    /**
     * API 31 以下：扬声器开关 + 蓝牙 SCO 要自己拉起；有线耳机系统会自己切。
     *
     * **先后直接用 [IMAudioRoutePolicy.pick]**（有线排在蓝牙前面）：两样都接着时不该拉起蓝牙 SCO——
     * 原先只看 `hasSco`，车载蓝牙连着时插上有线耳机，声音仍然走蓝牙，跟策略里「插线是更晚、更有意的动作」矛盾。
     */
    @Suppress("DEPRECATION")
    private fun applyLegacyRoute(why: String) {
        audioManager.isSpeakerphoneOn = speakerForced
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }
        val wantSco = !speakerForced &&
            IMAudioRoutePolicy.pick(speakerForced = false, available = outputs) == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        if (wantSco && !scoStarted) {
            audioManager.startBluetoothSco()
            audioManager.isBluetoothScoOn = true
            scoStarted = true
        } else if (!wantSco && scoStarted) {
            stopSco()
        }
        IMRTCLog.i("audio", "$why：扬声器=$speakerForced 蓝牙SCO=$scoStarted 输出=$outputs（API 31 以下的旧路径）")
    }

    @Suppress("DEPRECATION")
    private fun stopSco() {
        if (!scoStarted) return
        audioManager.isBluetoothScoOn = false
        audioManager.stopBluetoothSco()
        scoStarted = false
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
