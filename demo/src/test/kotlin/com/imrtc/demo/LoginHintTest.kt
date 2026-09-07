package com.imrtc.demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException

/**
 * 隧道提示的判据守卫。
 *
 * 真机上踩过的那次：`adb reverse` 的隧道被拔线抹掉，界面只写「连不上」，
 * 于是人去改地址——而那台 Pixel 没有能用的局域网 IP，改了也白改。
 * 这里钉住的是**什么时候该提示、什么时候绝对不能提示**：
 * 提示错了比不提示更糟，会把人往错的方向带一整轮。
 */
class LoginHintTest {

    private val connectFailed = ConnectException("Failed to connect to /127.0.0.1:8787")

    @Test
    fun `回环地址连不上时给出隧道命令`() {
        val msg = LoginHint.explain("http://127.0.0.1:8787", connectFailed)
        assertTrue(msg, msg.startsWith("登录失败：Failed to connect to /127.0.0.1:8787"))
        assertTrue(msg, msg.contains("adb reverse tcp:8787 tcp:8787"))
    }

    @Test
    fun `localhost 与 IPv6 回环同样算回环`() {
        assertTrue(LoginHint.isLoopback("http://localhost:8787"))
        assertTrue(LoginHint.isLoopback("http://[::1]:8787"))
        assertTrue(LoginHint.isLoopback("http://127.0.0.53:8787"))
        assertTrue(LoginHint.explain("http://localhost:8787", connectFailed).contains("adb reverse"))
    }

    @Test
    fun `局域网 IP 连不上时不提隧道`() {
        // OPPO 走的就是这条路：隧道跟它没有任何关系，提了只会把人带偏。
        val msg = LoginHint.explain("http://192.168.1.12:8787", ConnectException("Failed to connect"))
        assertFalse(msg, msg.contains("adb reverse"))
        assertEquals("登录失败：Failed to connect", msg)
    }

    @Test
    fun `连上了但服务端回错时不提隧道`() {
        // DemoApi 的 HTTP 错误是裸 IOException——连接是通的，隧道没问题。
        val msg = LoginHint.explain("http://127.0.0.1:8787", IOException("HTTP 401：unauthorized"))
        assertFalse(msg, msg.contains("adb reverse"))
        assertEquals("登录失败：HTTP 401：unauthorized", msg)
    }

    @Test
    fun `超时也算连不上`() {
        assertTrue(LoginHint.explain("http://127.0.0.1:8787", SocketTimeoutException("timeout")).contains("adb reverse"))
    }

    @Test
    fun `包在外层的连接异常也认得出`() {
        // OkHttp 有时会把 ConnectException 包一层再抛。
        val wrapped = IOException("unexpected", connectFailed)
        assertTrue(LoginHint.isUnreachable(wrapped))
    }

    @Test
    fun `没有 scheme 的地址也解析得出 host`() {
        // 输入框里人常常只填 `IP:端口`。
        assertEquals("127.0.0.1", LoginHint.hostOf("127.0.0.1:8787"))
        assertEquals("192.168.1.12", LoginHint.hostOf("http://192.168.1.12:8787/"))
        assertEquals("::1", LoginHint.hostOf("http://[::1]:8787"))
        assertTrue(LoginHint.isLoopback("127.0.0.1:8787"))
    }

    @Test
    fun `没有 message 的异常不会显示 null`() {
        val msg = LoginHint.explain("http://192.168.1.12:8787", ConnectException())
        assertEquals("登录失败：ConnectException", msg)
    }
}
