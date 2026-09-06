package com.imrtc.uikit

/**
 * 权限申请的决策逻辑（交互稿 §01–§02 / §08 差异 6）：**前置说明卡 → 系统框 → 结果分支**，三段式。
 *
 * Android 比 iOS **多一屏**：拒绝一次还能再问，第二次拒绝才永久（`shouldShowRequestPermissionRationale`）。
 * 所以第一次拒绝后出「没有麦克风就没法通话，再试一次？」，第二次才进「去设置」。
 *
 * 这里只有决策，没有 android.*——能直接 JVM 单测。真正弹系统框的是 [IMPermissionActivity]，
 * 经 [Asker] 接口注入；测试注入假的。
 */
internal object IMPermissionGate {

    enum class Device(val permission: String) {
        MICROPHONE("android.permission.RECORD_AUDIO"),
        CAMERA("android.permission.CAMERA"),
    }

    /** 一次申请的结局。 */
    enum class Result { GRANTED, DENIED, CANCELLED }

    /** `ensure` 的四种结局。 */
    enum class Outcome {
        OK,
        /** 摄像头拿不到：**通话继续，只是没画面**。 */
        CAMERA_BLOCKED,
        /** 麦克风拿不到：**整通话取消**，不发 invite / accept。 */
        MIC_BLOCKED,
        /** 用户在说明卡上点了「取消」。 */
        CANCELLED,
    }

    /** 说明卡 / 被拒卡的文案（规范 §08）。 */
    data class Copy(val title: String, val body: String)

    /** 真正去问系统的那一层。已授权时立刻回 GRANTED、不弹框。 */
    fun interface Asker {
        fun ask(device: Device, callback: (Result) -> Unit)
    }

    /** 一次动作要申请哪些设备（交互稿 §01 的表）。麦克风先问：它被拒了整通话都不成立。 */
    fun devicesFor(mediaType: String, withCamera: Boolean): List<Device> =
        if (mediaType == "video" && withCamera) listOf(Device.MICROPHONE, Device.CAMERA) else listOf(Device.MICROPHONE)

    /** 说明卡：说清**用来做什么**，不说「请授权」。 */
    fun explanation(device: Device): Copy = when (device) {
        Device.MICROPHONE -> Copy("需要用到麦克风", "通话时对方要听见你的声音。接下来系统会问你要不要允许。")
        Device.CAMERA -> Copy("需要用到摄像头", "视频通话时对方要看见你。接下来系统会问你要不要允许。")
    }

    /** 第一次拒绝后的「再试一次」（Android 独有的那一屏）。 */
    fun secondChance(device: Device): Copy = when (device) {
        Device.MICROPHONE -> Copy("没有麦克风就没法通话", "再试一次？这次请选「允许」。")
        Device.CAMERA -> Copy("没有摄像头就看不到你", "再试一次？不允许的话会用语音继续通话。")
    }

    /** 永久被拒：麦克风走不下去；摄像头降级为语音继续。 */
    fun blocked(device: Device): Copy = when (device) {
        Device.MICROPHONE -> Copy("没有麦克风权限，无法通话", "到「设置 › 应用 › 权限」里打开麦克风后重试。")
        Device.CAMERA -> Copy("没有摄像头权限，已用语音继续通话", "要开视频，请到系统设置里打开摄像头权限。")
    }

    /**
     * 按顺序问每个设备，最后给一个 [Outcome]。回调式：公开面不用协程（CONVENTIONS §4）。
     */
    fun ensure(devices: List<Device>, asker: Asker, done: (Outcome) -> Unit) {
        fun step(index: Int, outcome: Outcome) {
            if (index >= devices.size) { done(outcome); return }
            val device = devices[index]
            asker.ask(device) { result ->
                when (result) {
                    Result.GRANTED -> step(index + 1, outcome)
                    Result.CANCELLED -> done(Outcome.CANCELLED)
                    Result.DENIED -> when (device) {
                        Device.MICROPHONE -> done(Outcome.MIC_BLOCKED)
                        Device.CAMERA -> step(index + 1, Outcome.CAMERA_BLOCKED)
                    }
                }
            }
        }
        step(0, Outcome.OK)
    }
}
