package com.imrtc.demo

import android.content.SharedPreferences
import android.os.Build

// SharedPreferences 的键。**放文件级**：DemoSession 与本文件都要用，
// 藏在任一个类里都会让另一个写成 `Xxx.KEY_YYY`，读起来像跨模块访问，其实同包同文件夹。
internal const val KEY_SERVER = "server"
internal const val KEY_USER = "user"
internal const val KEY_CALLEE = "callee"
internal const val KEY_ROOM = "room"
internal const val KEY_GROUP = "group"
internal const val KEY_AUTO = "auto_login"
internal const val KEY_PROFILE = "video_profile"
internal const val KEY_H264 = "prefer_hardware_h264"
internal const val KEY_VERBOSE = "verbose_log"
internal const val KEY_BANNER = "banner_first"
internal const val KEY_FLOATING = "floating_window"
internal const val KEY_RING_MUTED = "ringtone_muted"
internal const val KEY_LANGUAGE = "language"
internal const val LANGUAGE_AUTO = "auto"
internal const val KEY_RECORDS = "records"

/**
 * 登录表单「上次填的东西」与它们的默认值 / 提示语。
 *
 * 从 `DemoSession` 里拿出来，是因为它与会话生命周期无关：这里只回答
 * 「输入框里该预填什么、提示语该写什么」，一行都不碰引擎。
 */
class DemoFormPrefs(private val prefs: SharedPreferences) {

    /**
     * 上次用的服务器地址；没用过就给模拟器的默认值。
     *
     * **模拟器与真机的默认值必须不一样**：模拟器里 `10.0.2.2` 就是宿主机，开箱即用；
     * 真机上 `127.0.0.1` 指的是**手机自己**，永远连不上。
     *
     * 真机上**留空**，靠 hint 说该填什么。不预填一个像模像样的假 IP（比如
     * 192.168.1.100）：那种地址一眼看不出是错的，人会以为服务端挂了去查服务端日志——
     * 而那边根本没有请求进来，最难查的一类。
     */
    val defaultServer: String
        get() = prefs.getString(KEY_SERVER, null)
            ?: if (isEmulator) "http://10.0.2.2:8787" else ""

    val serverHint: String
        get() = if (isEmulator) {
            "服务器（模拟器用 10.0.2.2 指向 Mac）"
        } else {
            "http://<Mac 的局域网 IP>:8787"
        }

    val serverNote: String
        get() = if (isEmulator) {
            "模拟器里 10.0.2.2 就是宿主机，默认值直接可用。"
        } else {
            "真机请填 Mac 的局域网 IP（启动 dev.sh 时会打印）。127.0.0.1 在手机上指手机自己。"
        }

    /** 默认 **carol**：Web 默认 alice、iOS 默认 bob，三端错开，联调不用改用户名。 */
    val defaultUsername: String get() = prefs.getString(KEY_USER, null) ?: "carol"

    val defaultCallee: String
        get() = prefs.getString(KEY_CALLEE, null) ?: if (defaultUsername == "alice") "bob" else "alice"

    val defaultRoom: String get() = prefs.getString(KEY_ROOM, "").orEmpty()

    fun rememberCallee(value: String) = prefs.edit().putString(KEY_CALLEE, value).apply()

    fun rememberRoom(value: String) = prefs.edit().putString(KEY_ROOM, value).apply()

    private val isEmulator: Boolean
        get() = Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.contains("emulator") ||
            Build.MODEL.contains("sdk_gphone")
}
