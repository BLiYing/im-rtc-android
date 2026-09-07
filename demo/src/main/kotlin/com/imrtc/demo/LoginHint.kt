package com.imrtc.demo

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 把换票失败的异常翻译成**身份卡上那句话**。
 *
 * # 为什么值得单独一个文件
 *
 * 「登录失败：Failed to connect to /127.0.0.1:8787」说了「连不上」，
 * 却没说**这个地址本来该是通的**。真机上踩过的那次：`127.0.0.1` 是靠
 * `adb reverse` 打的隧道，隧道被拔线抹掉之后，prefs 里那行地址**本身仍然是对的**——
 * 补上隧道立刻就能用。可界面上只写「连不上」，人自然会去改地址，
 * 而这台 Pixel 压根**没有**能用的局域网 IP（它与 Mac 挂在同一 SSID 的两个不同 AP 上，
 * 互相 ARP 不到）。于是改地址 → 还是失败 → 去翻服务端日志 → 那边一条请求都没有。
 *
 * 所以回环地址连不上时要把**补救动作**直接写出来，而不是让人去猜该填什么。
 *
 * 判据是「回环地址」**且**「根本没连上」两个条件同时成立：
 * 填局域网 IP 的 OPPO 不会看到这句，服务端回 401/500 时也不会——
 * 那两种情况下隧道没有任何关系，提示隧道只会把人带偏。
 */
internal object LoginHint {

    /** 身份卡上显示的完整文案。[error] 是 [DemoApi] 抛出来的那个。 */
    fun explain(server: String, error: Throwable): String {
        val head = "登录失败：${error.message ?: error::class.java.simpleName}"
        if (!isLoopback(server) || !isUnreachable(error)) return head
        return head + "\n\n" +
            "${hostOf(server)} 要靠 adb reverse 隧道，拔线 / 重插 / 手机重启都会把它断掉。" +
            "在 Mac 上重跑：\nadb reverse tcp:8787 tcp:8787"
    }

    /**
     * 取出地址里的 host。**手写而不用 `URI`**：地址是人填进输入框的，
     * 常常没有 scheme（`192.168.1.12:8787`），那种字符串 `URI` 会把它整个当路径，
     * host 解析成 null。
     */
    fun hostOf(server: String): String {
        var s = server.trim()
        val scheme = s.indexOf("://")
        if (scheme >= 0) s = s.substring(scheme + 3)
        s = s.substringBefore('/').substringAfterLast('@')
        // IPv6 是 `[::1]:8787`，方括号里的冒号不是端口分隔符。
        return if (s.startsWith("[")) {
            val end = s.indexOf(']')
            if (end > 0) s.substring(1, end) else s.drop(1)
        } else {
            s.substringBefore(':')
        }.lowercase()
    }

    /** 回环地址：只有靠隧道才可能通到 Mac。 */
    fun isLoopback(server: String): Boolean {
        val host = hostOf(server)
        return host == "localhost" || host == "::1" || host.startsWith("127.")
    }

    /**
     * **压根没连上**（而不是连上了、服务端回了个错）。
     *
     * 按异常类型判，不按 message 里的字样：那句 "Failed to connect to …" 是
     * OkHttp 的文案，换个版本就可能变。[DemoApi] 的 HTTP 错误是**裸的** [IOException]，
     * 落不进这几个子类，所以 401/500 不会被当成「连不上」。
     */
    fun isUnreachable(error: Throwable): Boolean = generateSequence(error) { it.cause }.any {
        it is ConnectException ||
            it is SocketTimeoutException ||
            it is UnknownHostException ||
            it is NoRouteToHostException
    }
}
