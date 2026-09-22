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
import com.imrtc.engine.IMAudioRoute
import com.imrtc.engine.IMAudioRouteKind
import com.imrtc.engine.log.IMRTCLog
import java.util.concurrent.Executor

/**
 * 通话音频路由。**这一层的每一行都是平台规则，不是我们的设计**（CONVENTIONS §8）。
 *
 * 三件必须做对的事：
 * 1. **模式必须是 `MODE_IN_COMMUNICATION`**：不设就没有硬件回声消除，自己会听到自己——
 *    而那听起来像「对方设备有问题」，很容易查错方向（iOS 上踩过同一个坑）。
 * 2. **必须申请音频焦点**：不申请的话，别的应用放音乐时不会让路，通话被盖住。
 * 3. **路由 API 分两代**：Android 12（API 31）起用 `setCommunicationDevice()`（蓝牙 SCO 由系统自己拉起），
 *    之下只能用已废弃的 `setSpeakerphoneOn()`（蓝牙还要自己 `startBluetoothSco`）。两条路都要写，**并说清楚在哪个版本上验过**。
 *
 * 选哪台设备的规则全在 [IMAudioRoutePolicy]（纯函数，JVM 可测）；这里只负责读设备、下命令、往上报。
 * 清单与在用项的变化经 [onChanged] 报给适配器（→ Engine → 宿主与 Kit），四个时刻都报：
 * 起媒体那一刻、插拔 / 连断、手选之后、以及系统自己换了通信设备（API 31+ 才有这条通知）。
 *
 * **蓝牙的设备名**：`AudioDeviceInfo.productName` 给的就是耳机报的名字（「AirPods Pro」），
 * 不需要 `BLUETOOTH_CONNECT`——那个权限是 `BluetoothAdapter` 那套 API 要的，这里一行都不碰。
 */
internal class IMAudioRouter(
    context: Context,
    private val onChanged: (routes: List<IMAudioRoute>, current: IMAudioRoute?) -> Unit,
) {

    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val main = Handler(Looper.getMainLooper())

    private var focusRequest: AudioFocusRequest? = null
    private var savedMode = AudioManager.MODE_NORMAL

    /** 两层意图，见 [IMAudioRoutePolicy.Choice]。 */
    private var choice = IMAudioRoutePolicy.Choice()

    /** 起了媒体才对外报清单：响铃期清单是假的（系统还没把设备按通话口径列出来）。 */
    @Volatile
    private var running = false

    /** API 31 以下自己拉起的蓝牙 SCO，停的时候要成对关掉。 */
    private var scoStarted = false

    /**
     * 耳机 / 蓝牙插拔：**连上自动切过去，断开退回上一条**（交互稿差异 7）。原先没注册，
     * 插拔这件事 SDK 压根不知道——通话中连上蓝牙耳机，声音照旧从听筒出来。
     */
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) =
            onDevicesChanged("接入", addedDevices, added = true)

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) =
            onDevicesChanged("断开", removedDevices, added = false)
    }

    /** 系统自己换了通信设备（比如蓝牙 SCO 连上晚了一拍）：只重报一次在用项，不改意图。 */
    private var deviceListener: Any? = null

    @Synchronized
    fun start() {
        savedMode = audioManager.mode
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        requestFocus()
        choice = IMAudioRoutePolicy.Choice()
        running = true
        // 回调派到主线程：选路只在这条线程与调用方线程上发生，applyRoute 自己是幂等的。
        audioManager.registerAudioDeviceCallback(deviceCallback, main)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) listenCommunicationDevice()
        // 默认跟随系统：接着耳机就走耳机，都没有才是听筒（「打电话」的语义）。视频通话由上层调 setSpeakerOn(true)。
        applyRoute("开始通话")
    }

    @Synchronized
    fun stop() {
        running = false
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) unlistenCommunicationDevice()
        abandonFocus()
        stopSco()
        clearCommunicationDevice()
        audioManager.mode = savedMode
    }

    /** 老的二态开关：开 = 手选扬声器；关 = 撤掉手选、跟随系统（**不是**钉死听筒）。 */
    @Synchronized
    fun setSpeakerOn(on: Boolean) {
        choice = IMAudioRoutePolicy.manual(choice, if (on) IMAudioRoute.SPEAKER_UID else null)
        applyRoute(if (on) "打开扬声器" else "关闭扬声器")
    }

    /** 面板里点了一条。切不过去只记日志（设备可能刚好被拔掉），通话照常。 */
    @Synchronized
    fun setRoute(route: IMAudioRoute) {
        choice = IMAudioRoutePolicy.manual(choice, route.uid)
        applyRoute("手选 ${route.kind} ${route.name}")
    }

    /** 此刻可选的清单；没起媒体时为空（语义「不提供路由选择」）。 */
    fun routes(): List<IMAudioRoute> =
        if (!running) emptyList() else IMAudioRoutePolicy.routes(devices()).mapNotNull(IMAudioRoutePolicy::toRoute)

    /** 此刻在用的那条：API 31+ 直接问系统；旧路径按扬声器 / SCO 开关反推。 */
    fun current(): IMAudioRoute? {
        val routes = routes()
        if (routes.isEmpty()) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val device = audioManager.communicationDevice ?: return null
            val uid = IMAudioRoutePolicy.uidOf(IMAudioRoutePolicy.Device(device.type, device.productName.toString(), device.id))
            return routes.firstOrNull { it.uid == uid }
                ?: routes.firstOrNull { it.kind == IMAudioRoutePolicy.kindOf(device.type) }
        }
        @Suppress("DEPRECATION")
        val kind = when {
            audioManager.isSpeakerphoneOn -> IMAudioRouteKind.SPEAKER
            scoStarted -> IMAudioRouteKind.BLUETOOTH
            routes.any { it.kind == IMAudioRouteKind.WIRED_HEADSET } -> IMAudioRouteKind.WIRED_HEADSET
            else -> IMAudioRouteKind.EARPIECE
        }
        return routes.firstOrNull { it.kind == kind }
    }

    private fun devices(): List<IMAudioRoutePolicy.Device> {
        val infos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.availableCommunicationDevices
        } else {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        }
        return infos.map { IMAudioRoutePolicy.Device(it.type, it.productName.toString(), it.id) }
    }

    @Synchronized
    private fun onDevicesChanged(what: String, infos: Array<out AudioDeviceInfo>, added: Boolean) {
        if (!running) return
        val types = infos.map { it.type }
        if (!IMAudioRoutePolicy.touchesExternal(types)) return
        val devices = infos.map { IMAudioRoutePolicy.Device(it.type, it.productName.toString(), it.id) }
        IMRTCLog.i("audio", "音频设备$what：types=$types choice=$choice")
        choice = if (added) {
            IMAudioRoutePolicy.externalAdded(choice, devices)
        } else {
            IMAudioRoutePolicy.removed(choice, devices.map(IMAudioRoutePolicy::uidOf))
        }
        applyRoute("设备$what")
    }

    private fun applyRoute(why: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            applyModernRoute(why)
        } else {
            applyLegacyRoute(why)
        }
        notifyChanged()
    }

    // 调用点都已按 Build.VERSION_CODES.S 分支，见 applyRoute / start / stop。
    private fun applyModernRoute(why: String) {
        val devices = audioManager.availableCommunicationDevices
        val target = IMAudioRoutePolicy.effective(
            choice,
            devices.map { IMAudioRoutePolicy.Device(it.type, it.productName.toString(), it.id) },
        )
        val device = target?.let { t -> devices.firstOrNull { it.id == t.id } }
        if (device == null) {
            // 挑不出来就交还系统默认，不硬选（原先是「保持原样」，可能停在一台已经拔掉的设备上）。
            audioManager.clearCommunicationDevice()
            IMRTCLog.w("audio", "$why：没有可选的通话设备（choice=$choice），交还系统默认")
            return
        }
        val ok = audioManager.setCommunicationDevice(device)
        IMRTCLog.i("audio", "$why：通话声音走 type=${device.type} id=${device.id} name=${device.productName}：$ok（API 31+ 路径）")
    }

    /**
     * API 31 以下：扬声器开关 + 蓝牙 SCO 要自己拉起；有线耳机系统会自己切。
     *
     * **先后直接用 [IMAudioRoutePolicy.effective]**（有线排在蓝牙前面）：两样都接着时不该拉起蓝牙 SCO——
     * 原先只看 `hasSco`，车载蓝牙连着时插上有线耳机，声音仍然走蓝牙，跟策略里「插线是更晚、更有意的动作」矛盾。
     * **这条路径 2026-09-22 之后没真机验过**（手头的机器都是 Android 12+）。
     */
    @Suppress("DEPRECATION")
    private fun applyLegacyRoute(why: String) {
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .map { IMAudioRoutePolicy.Device(it.type, it.productName.toString(), it.id) }
        val kind = IMAudioRoutePolicy.effective(choice, outputs)?.let { IMAudioRoutePolicy.kindOf(it.type) }
        audioManager.isSpeakerphoneOn = kind == IMAudioRouteKind.SPEAKER
        val wantSco = kind == IMAudioRouteKind.BLUETOOTH
        if (wantSco && !scoStarted) {
            audioManager.startBluetoothSco()
            audioManager.isBluetoothScoOn = true
            scoStarted = true
        } else if (!wantSco && scoStarted) {
            stopSco()
        }
        IMRTCLog.i("audio", "$why：路由=$kind 蓝牙SCO=$scoStarted 输出=${outputs.map { it.type }}（API 31 以下的旧路径）")
    }

    private fun notifyChanged() {
        if (!running) return
        val routes = routes()
        val current = current()
        IMRTCLog.i("audio", "音频路由清单：${routes.map { "${it.kind}:${it.name.ifEmpty { "-" }}" }} 在用=${current?.kind}")
        onChanged(routes, current)
    }

    // 调用点都已按 Build.VERSION_CODES.S 分支，见 applyRoute / start / stop。
    private fun listenCommunicationDevice() {
        val listener = AudioManager.OnCommunicationDeviceChangedListener { device ->
            IMRTCLog.i("audio", "系统换了通信设备：type=${device?.type} name=${device?.productName}")
            notifyChanged()
        }
        deviceListener = listener
        audioManager.addOnCommunicationDeviceChangedListener(Executor { main.post(it) }, listener)
    }

    // 调用点都已按 Build.VERSION_CODES.S 分支，见 applyRoute / start / stop。
    private fun unlistenCommunicationDevice() {
        (deviceListener as? AudioManager.OnCommunicationDeviceChangedListener)
            ?.let { audioManager.removeOnCommunicationDeviceChangedListener(it) }
        deviceListener = null
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
