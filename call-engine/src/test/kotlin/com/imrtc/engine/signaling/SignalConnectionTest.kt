package com.imrtc.engine.signaling

import com.imrtc.engine.IMKickedOutReason
import com.imrtc.engine.protocol.IMCloseCode
import com.imrtc.engine.protocol.IMErrorCode
import com.imrtc.engine.protocol.IMFrameType
import com.imrtc.engine.protocol.IMJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 信令连接的时序，全部用假传输 + 假时钟跑——**不连真服务端**。
 *
 * 这些行为在真连接上极难复现（要制造 4401、要等 30 秒退避、要让服务端半死不活），
 * 而它们恰恰是 Web 与 iOS 两端**实际出过 bug** 的地方。一条都不能靠肉眼验。
 */
class SignalConnectionTest {

    private val scheduler = FakeScheduler()
    private val transport = FakeTransport()
    private val events = RecordingEvents()
    private val connection = IMSignalConnection(transport, scheduler, events)

    private val config = IMSignalConnection.Config(url = "ws://test/rtc", deviceId = "d-1")

    private fun connect(token: String = "tk-1", resumed: Boolean = false) {
        connection.start(config, token)
        transport.open()
        transport.replyOk(
            IMFrameType.HELLO,
            mapOf(
                "session_id" to IMJson.Str("s-1"),
                "resumed" to IMJson.Bool(resumed),
                "ping_interval_sec" to IMJson.Num(15),
            ),
        )
    }

    @Test
    fun `握手：连上就发 sys hello，收到 ok 才算连接成功`() {
        connection.start(config, "tk-1")
        assertEquals(1, transport.connectCount)
        // 还没 open，不该发任何东西
        assertTrue(transport.sent.isEmpty())

        transport.open()
        val hello = transport.lastOf(IMFrameType.HELLO) ?: error("没发 sys.hello")
        assertEquals("tk-1", (hello.data["token"] as IMJson.Str).value)
        assertEquals("d-1", (hello.data["device_id"] as IMJson.Str).value)
        // 首次连接 session_id 为空串——**空串不是缺失**，缺了就是非法信封
        assertEquals("", (hello.data["session_id"] as IMJson.Str).value)
        assertTrue("握手没回来之前不该算已连接", !connection.isConnected)

        transport.replyOk(IMFrameType.HELLO, mapOf("session_id" to IMJson.Str("s-1")))
        assertTrue(connection.isConnected)
        assertEquals(listOf("s-1" to false), events.connected)
    }

    @Test
    fun `请求按 req_id 配对，迟到的应答丢掉而不是崩掉`() {
        connect()
        var result: String? = null
        connection.request(IMFrameType.ROOM_LEAVE, mapOf("room_id" to IMJson.Str("r-1"))) { ok, _, _, _ ->
            result = if (ok) "ok" else "fail"
        }
        transport.replyOk(IMFrameType.ROOM_LEAVE)
        assertEquals("ok", result)

        // 同一个 req_id 再来一次：这是迟到的重复应答，丢掉即可
        transport.replyOk(IMFrameType.ROOM_LEAVE)
        assertEquals("ok", result)
    }

    @Test
    fun `请求十秒无应答报 2004，之后应答回来也不崩`() {
        connect()
        var code: IMErrorCode? = null
        connection.request(IMFrameType.ROOM_JOIN, emptyMap()) { _, _, c, _ -> code = c }

        scheduler.advance(9_000)
        assertNull("9 秒还不该超时", code)
        scheduler.advance(2_000)
        assertEquals(IMErrorCode.SIGNALING_TIMEOUT, code)

        transport.replyOk(IMFrameType.ROOM_JOIN) // 迟到的应答
    }

    @Test
    fun `心跳按周期发 ping；两个周期收不到东西就自己断开重连`() {
        connect()
        scheduler.advance(15_000)
        assertEquals(1, transport.countOf(IMFrameType.PING))

        // 服务端有回应：连接是活的
        transport.deliver(IMFrameType.PONG, "")
        scheduler.advance(15_000)
        assertEquals(2, transport.countOf(IMFrameType.PING))

        // 之后一直没有任何下行：超过两个周期就主动断
        scheduler.advance(45_000)
        assertTrue("心跳超时后应当断开", !connection.isConnected)
        assertTrue("断开后应当排重连", transport.connectCount > 1)
    }

    @Test
    fun `一次断线只排一次重连`() {
        connect()
        // 「关闭」与「连接失败」两条路都会走到排重连，不去重的话退避档一次涨两级
        transport.closed(1006, "network")
        transport.failure()
        scheduler.advance(60_000)
        assertEquals("只该重连一次", 2, transport.connectCount)
    }

    @Test
    fun `4401 连续三次就放弃并抛 onKickedOut`() {
        connection.start(config, "过期的票")
        repeat(3) {
            transport.open()
            transport.closed(IMCloseCode.UNAUTHORIZED.code, "token_invalid")
            scheduler.advance(60_000)
        }
        assertEquals(1, events.kickedOut)
        val connectsSoFar = transport.connectCount
        scheduler.advance(120_000)
        assertEquals("放弃之后不许再敲", connectsSoFar, transport.connectCount)
    }

    @Test
    fun `换票之后鉴权计数归零——那是一把新钥匙`() {
        connection.start(config, "过期的票")
        repeat(2) {
            transport.open()
            transport.closed(IMCloseCode.UNAUTHORIZED.code, "token_invalid")
            scheduler.advance(60_000)
        }
        assertEquals(0, events.kickedOut)

        connection.updateToken("新票")
        repeat(2) {
            transport.open()
            transport.closed(IMCloseCode.UNAUTHORIZED.code, "token_invalid")
            scheduler.advance(60_000)
        }
        assertEquals("换票后重新计数，两次还不该放弃", 0, events.kickedOut)
    }

    @Test
    fun `4403 被踢：抛 onKickedOut 且不再重连`() {
        connect()
        transport.closed(IMCloseCode.KICKED.code, "kicked")
        val connectsSoFar = transport.connectCount
        scheduler.advance(120_000)
        assertEquals(1, events.kickedOut)
        assertEquals("被踢之后重连等于跟另一台设备打架", connectsSoFar, transport.connectCount)
    }

    @Test
    fun `stop 之后一律不再重连——放弃必须用闩`() {
        connect()
        connection.stop()
        // 闩没上的话，这条排在后面的失败回调会把重连又排回来
        transport.closed(1006, "late close")
        transport.failure()
        val connectsSoFar = transport.connectCount
        scheduler.advance(120_000)
        assertEquals(connectsSoFar, transport.connectCount)
    }

    @Test
    fun `重连成功后 resumed 原样带上来`() {
        connect()
        transport.closed(1006, "network")
        scheduler.advance(5_000)
        transport.open()
        transport.replyOk(
            IMFrameType.HELLO,
            mapOf("session_id" to IMJson.Str("s-1"), "resumed" to IMJson.Bool(true)),
        )
        assertEquals(listOf("s-1" to false, "s-1" to true), events.connected)
    }

    @Test
    fun `未知帧静默忽略，不断连接也不上抛`() {
        connect()
        transport.deliver("room.something_new", "")
        assertTrue(connection.isConnected)
        assertTrue(events.frames.none { it.first == "room.something_new" })
        assertTrue(events.errors.isEmpty())
    }

    /*
      被顶号与「票不好使」是两种相反的处置：一个回登录页，一个悄悄换票重来。
      分不开的话宿主只能都当登录失效，把本可静默恢复的场景也变成「请重新登录」。
    */
    @Test
    fun `被踢的两种原因分得开`() {
        connect()
        transport.closed(IMCloseCode.KICKED.code, "elsewhere")
        assertEquals(listOf(IMKickedOutReason.TAKEN_OVER), events.kickReasons)
    }

    @Test
    fun `4401 用尽报的是 AUTH_EXPIRED 而不是被顶号`() {
        connect()
        repeat(3) {
            transport.closed(IMCloseCode.UNAUTHORIZED.code, "bad token")
            scheduler.advance(60_000)
        }
        assertEquals(listOf(IMKickedOutReason.AUTH_EXPIRED), events.kickReasons)
    }

    // ── 握手失败的分流：可重试的重连，不可重试的按「谁救得了」分三种 ──────────

    /**
     * 不可重试的握手错误**一次就放弃**，不再重连。
     *
     * 真机上踩过：device_id 里带了个空格，服务端一律回 1004，而客户端按
     * 1s→2s→4s→8s→15s→30s 无限退避重连，界面上只写着「登录失败」，
     * 日志里刷满同一条错误，把真正的原因埋掉了。
     *
     * **参数不会因为重连而改变**——这是它与 4401 的根本区别。
     */
    @Test
    fun `握手收到不可重试的错误：一次就放弃，不再重连`() {
        connection.start(config, "tk-1")
        transport.open()
        assertEquals(1, transport.connectCount)

        // 1004 bad_params：retryable=false
        transport.replyError(IMFrameType.HELLO, 1004, "bad_params", "invalid frame parameters")

        assertEquals("宿主该收到错误码", 1, events.errors.size)
        assertEquals(IMErrorCode.BAD_PARAMS, events.errors[0].first)
        assertEquals("该抛一次 onKickedOut", 1, events.kickedOut)
        assertEquals(IMKickedOutReason.CONFIG_REJECTED, events.kickReasons[0])

        // 关键：把所有定时器跑完，也不该再连一次。
        scheduler.advance(60_000)
        assertEquals("不可重试的错误却重连了", 1, transport.connectCount)
    }

    /**
     * **放弃的时候要自己把 socket 关掉**，别指望对端替我们关。
     *
     * 不关的症状是 `connecting` 一直是 true，而 `openSocket` 第一行就
     * `if (stopped || connecting || connected) return`——宿主照着 onKickedOut 的建议
     * 改完配置再 `login()`，会被这一行静默挡掉：没有 socket、没有日志、没有回调。
     */
    @Test
    fun `放弃时自己关掉 socket，之后还能重新 login`() {
        connection.start(config, "tk-1")
        transport.open()
        transport.replyError(IMFrameType.HELLO, 1004, "bad_params")

        assertEquals("放弃时该自己关 socket", 1, transport.closeCount)

        // 宿主改完配置重新登录：必须真的再连一次。
        connection.start(config, "tk-2")
        assertEquals("重新 login 之后没有再连", 2, transport.connectCount)
    }

    /**
     * **不可重试 ≠ 参数不对。** 三类的处置完全相反，合成一类就是给宿主一条错的建议：
     * 「去改配置」救不了一枚该换的票，「换票」也救不了一个被封的号。
     */
    @Test
    fun `不可重试的握手错误按「谁救得了」分成三种原因`() {
        val cases = listOf(
            // 参数/应用状态不对：换票和重试都没用，只能去改配置。
            Triple(1004L, "bad_params", IMKickedOutReason.CONFIG_REJECTED),
            Triple(1006L, "protocol_version_unsupported", IMKickedOutReason.CONFIG_REJECTED),
            Triple(1106L, "app_disabled", IMKickedOutReason.CONFIG_REJECTED),
            // 票不合法：**换一枚就好**。服务端轮换签名密钥时全端都会撞上这个码，
            // 报成 CONFIG_REJECTED 的话，本来静默换票就能恢复的事会把所有人踹回登录页。
            Triple(1101L, "token_invalid", IMKickedOutReason.AUTH_EXPIRED),
            // 宿主后台吊销了这个身份：服务端的吊销名单走的就是 sys.error{1104} + 4403。
            Triple(1104L, "kicked_out", IMKickedOutReason.TAKEN_OVER),
        )
        for ((code, name, expected) in cases) {
            val transport = FakeTransport()
            val events = RecordingEvents()
            val scheduler = FakeScheduler()
            val conn = IMSignalConnection(transport, scheduler, events)
            conn.start(config, "tk-1")
            transport.open()
            transport.replyError(IMFrameType.HELLO, code, name)

            scheduler.advance(60_000)
            assertEquals("$name 不该重连", 1, transport.connectCount)
            assertEquals("$name 该放弃", 1, events.kickedOut)
            assertEquals("$name 的原因归错类了", expected, events.kickReasons[0])
        }
    }

    /**
     * 本端不认识的码**信帧上自带的 `retryable`**。
     *
     * 本端那张错误码表是「上次同步时」的快照。本仓漏过一次 1106，症状正是这里要根治的
     * 无限重连：`fromCode` 返回 null，兜底成 1501 internal（可重试），于是照旧敲到天荒地老。
     */
    @Test
    fun `本端不认识的终局码也一次就放弃`() {
        connection.start(config, "tk-1")
        transport.open()
        // 1107：本端错误码表里没有这个码，但帧上写着 retryable=false。
        transport.replyError(IMFrameType.HELLO, 1107, "tenant_suspended", retryable = false)

        assertEquals("认不出的终局码却没放弃", 1, events.kickedOut)
        scheduler.advance(60_000)
        assertEquals("认不出的终局码却重连了", 1, transport.connectCount)
    }

    @Test
    fun `本端不认识但可重试的码照常重连`() {
        connection.start(config, "tk-1")
        transport.open()
        transport.replyError(IMFrameType.HELLO, 1508, "some_new_server_error", retryable = true)

        assertEquals("可重试的错误不该放弃", 0, events.kickedOut)
        scheduler.advance(60_000)
        assertTrue("该继续重连，得到 ${transport.connectCount} 次", transport.connectCount > 1)
    }

    /**
     * 1102 token_expired 是**可重试**的握手错误：重连时 Engine 可能已经换到新票。
     * 这条守住「别把可重试的也一起放弃了」。
     */
    @Test
    fun `票过期是可重试的：照常退避重连，不放弃`() {
        connection.start(config, "tk-1")
        transport.open()
        transport.replyError(IMFrameType.HELLO, 1102, "token_expired", "token expired", retryable = true)

        assertEquals("可重试的错误不该放弃", 0, events.kickedOut)
        scheduler.advance(60_000)
        assertTrue("该继续重连，得到 ${transport.connectCount} 次", transport.connectCount > 1)
    }

    /**
     * **宿主自己按的退出不该报成「服务端拒了你的参数」。**
     *
     * `stop()` 会拿 `2007 not_logged_in`（local 组、retryable=false）把在飞的握手结掉。
     * 只看 retryable 的话，一次正常的 `logout()` 就会抛 onKickedOut(CONFIG_REJECTED)——
     * 而 `relogin()` 正是先 `logout()` 再换票的，静默续期会当场变成把人踹回登录页。
     */
    @Test
    fun `握手途中 logout 不该报成被踢`() {
        connection.start(config, "tk-1")
        transport.open()
        // hello 已经发出、还没有应答，这时宿主退出。
        connection.stop()

        assertEquals("宿主自己退出却报了 onKickedOut", 0, events.kickedOut)
        scheduler.advance(60_000)
        assertEquals("退出之后又连回去了", 1, transport.connectCount)
    }

    /**
     * 放弃之后**票期定时器也要停**。
     *
     * 不停的症状：几分钟后它照样喊 onTokenWillExpire，宿主老实去后台换一枚新票塞回来，
     * 而连接早就闩上了、不会因此重连——宿主以为救回来了，其实 Engine 已经哑了。
     */
    @Test
    fun `放弃之后不再喊换票`() {
        connect()
        events.tokenWarnings.clear()
        transport.closed(IMCloseCode.KICKED.code, "elsewhere")

        scheduler.advance(24 * 60 * 60_000)
        assertTrue("放弃之后还在喊换票：${events.tokenWarnings}", events.tokenWarnings.isEmpty())
    }

    /*
      「断得太久 → 服务端那一侧的会话已经没了」这条倒计时。

      守的是真机 2026-09-08 的一幕：iOS carol 断网后停在「正在重连」，
      **不接网就永远停在通话界面，连挂断都点不动**——本地放弃的唯一入口是
      「重连上了但 resumed=false」，而网络不回来那一刻永远不会到。

      时刻取的是**上界**：3×ping（服务端读超时）+ 30s（恢复窗口）+ 5s 余量 = 80s。
      下面四条分别钉住：会到、不早到、连上就撤、以及重连失败不许把它往后推。
    */

    @Test
    fun `断开超过恢复窗口就报会话不可恢复`() {
        connect()
        transport.closed(IMCloseCode.NORMAL.code, "网断了")

        scheduler.advance(80_000)
        assertEquals("断了 80 秒还不放弃，界面就永远停在「正在重连」", 1, events.unrecoverable)
    }

    /*
      **不许早到。** 真机上断开 14 秒后重连成功恢复、通话好端端继续；
      在那之前宣布「通话已结束」是把一通还能救回来的电话杀掉，
      而且服务端还认为我们在房里，房间会挂着一个幽灵成员。
    */
    @Test
    fun `恢复窗口没过就不许报不可恢复`() {
        connect()
        transport.closed(IMCloseCode.NORMAL.code, "网断了")

        // 服务端最快也要 2×ping 才察觉，再加 30 秒窗口——60 秒时它一定还没放弃。
        scheduler.advance(59_000)
        assertEquals("提前放弃会杀掉一通还能恢复的通话", 0, events.unrecoverable)
    }

    @Test
    fun `重连成功就把倒计时撤掉`() {
        connect()
        transport.closed(IMCloseCode.NORMAL.code, "网断了")

        scheduler.advance(2_000) // 让退避把重连排出去
        transport.open()
        transport.replyOk(
            IMFrameType.HELLO,
            mapOf("session_id" to IMJson.Str("s-1"), "resumed" to IMJson.Bool(true)),
        )
        /*
         走过原来那个截止时刻（80 秒）还得再多走一段。
         **中途要喂下行帧**：不喂的话心跳自己会判超时、主动断开重连，
         于是倒计时被重新排上、80 秒后照样响——那时红的是心跳，不是这条闸。
        */
        repeat(12) {
            scheduler.advance(10_000)
            transport.deliver(IMFrameType.PONG, "")
        }
        assertEquals("已经连回来了还报不可恢复，会把正在进行的通话杀掉", 0, events.unrecoverable)
    }

    /*
      **每次重连失败都重排的话，截止时刻就一直往后挪、永远不会到**——
      而那正是这条倒计时要治的病。起点必须是第一次断开的那一刻。
    */
    @Test
    fun `重连一直失败不许把截止时刻往后推`() {
        connect()
        transport.closed(IMCloseCode.NORMAL.code, "网断了")

        // 断断续续地失败重连：每 5 秒撞一次墙，总时长仍然只走到 80 秒。
        repeat(16) {
            scheduler.advance(5_000)
            transport.failure(RuntimeException("连不上"))
        }
        assertEquals("截止时刻被重连失败一路推后，等于这条闸从来不会合上", 1, events.unrecoverable)
    }

    /*
      后台重连节奏「不清零，最长 3 秒」（2026-09-11，真机 OPPO/ColorOS）。

      ColorOS 后台每约 3 秒杀一次 socket，默认退避会越退越慢，而服务端「被叫刚断线」
      只等 5 秒就转振铃——这五条钉住四条判定规则 + 回前台立即重连，一条都不能只靠肉眼验。
      纯判定逻辑本身在 [IMReconnectPolicy]，这里验的是 [IMSignalConnection] 把它接对了没有。
     */

    @Test
    fun `前台：不管连接活了多久，断开都归零退避`() {
        connect()
        scheduler.advance(500) // 活得很短
        transport.closed(1006, "network")
        assertEquals("前台断开该归零（归零后重连本身会把档位变成1）", 1, connection.debugBackoffAttempts)

        scheduler.advance(2_000) // 把上面那次重连排出去
        transport.open()
        transport.replyOk(IMFrameType.HELLO, mapOf("session_id" to IMJson.Str("s-1")))
        // 这次活过了后台规则③的 10 秒线——但心跳要喂着，不然会被判成假活提前断开，
        // 那就测的是心跳超时那条路而不是这一条了。
        scheduler.advance(12_000)
        transport.closed(1006, "network")
        assertEquals("活很久也一样归零", 1, connection.debugBackoffAttempts)
    }

    @Test
    fun `后台连上不到10秒就断：退避不归零，但等待封顶3秒`() {
        connection.setForeground(false)
        connection.start(config, "tk-1")
        transport.open()
        transport.replyOk(IMFrameType.HELLO, mapOf("session_id" to IMJson.Str("s-1")))

        // 连续 6 轮「连上不到 10 秒就被 ColorOS 掐掉」：退避档从 1s 一路推到顶格 30s，
        // 但只要还是这种短命连接，每一轮都必须在 3 秒内就已经在重连——不许越等越久。
        repeat(6) { i ->
            scheduler.advance(2_000) // 活了 2 秒，< 10 秒
            transport.closed(1006, "colouros kill")
            val before = transport.connectCount
            scheduler.advance(3_000)
            assertTrue("第${i + 1}轮该在封顶 3 秒内重连", transport.connectCount > before)
            transport.open()
            transport.replyOk(IMFrameType.HELLO, mapOf("session_id" to IMJson.Str("s-1")))
        }
        assertEquals(
            "后台短命连接不该把退避归零——6 轮下来该正好是 6",
            6,
            connection.debugBackoffAttempts,
        )
    }

    @Test
    fun `后台短命连接的等待精确封顶在3000ms，抖动也不会让它溢出`() {
        connection.setForeground(false)
        connection.start(config, "tk-1")
        transport.open()
        transport.replyOk(IMFrameType.HELLO, mapOf("session_id" to IMJson.Str("s-1")))

        // 先跑两轮把退避档推到第 3 档：base=4000ms，±20% 抖动最低也有 3200ms，
        // 一定会撞上封顶——用它来精确验证「封顶」而不是碰巧没抖动到 3000 以上。
        repeat(2) {
            scheduler.advance(2_000)
            transport.closed(1006, "colouros kill")
            scheduler.advance(3_000)
            transport.open()
            transport.replyOk(IMFrameType.HELLO, mapOf("session_id" to IMJson.Str("s-1")))
        }

        scheduler.advance(2_000)
        transport.closed(1006, "colouros kill")
        val before = transport.connectCount
        scheduler.advance(2_999)
        assertEquals("差 1ms 到 3000ms 不该提前重连", before, transport.connectCount)
        scheduler.advance(1)
        assertEquals("满 3000ms 必须已经重连", before + 1, transport.connectCount)
    }

    @Test
    fun `后台连上超过10秒才断：不是被ColorOS掐的那种，跟前台一样归零`() {
        connection.setForeground(false)
        connection.start(config, "tk-1")
        transport.open()
        transport.replyOk(IMFrameType.HELLO, mapOf("session_id" to IMJson.Str("s-1")))

        // 先制造两轮短命连接，把退避档推起来（规则②：不归零）。
        repeat(2) {
            scheduler.advance(2_000)
            transport.closed(1006, "colouros kill")
            scheduler.advance(3_000)
            transport.open()
            transport.replyOk(IMFrameType.HELLO, mapOf("session_id" to IMJson.Str("s-1")))
        }
        val attemptsBeforeLongLived = connection.debugBackoffAttempts
        assertTrue(
            "前两轮短命连接应该把退避档推起来，实际=$attemptsBeforeLongLived",
            attemptsBeforeLongLived >= 2,
        )

        // 这一条连接活过了 10 秒才断——规则③：归零。
        scheduler.advance(11_000)
        transport.closed(1006, "normal bg close")
        assertEquals(
            "活过10秒该归零（归零后重连本身会把档位变成1，而不是继续往上涨）",
            1,
            connection.debugBackoffAttempts,
        )
    }

    @Test
    fun `从未连上成功：不管前后台都按原退避走满，不许被封顶到3秒`() {
        connection.setForeground(false)
        connection.start(config, "tk-1")

        // 制造两轮「压根没连上」的失败，把退避推到第 3 档（base=4000ms）。
        transport.failure(RuntimeException("no network"))
        scheduler.advance(1_500) // 第 1 档 base=1000ms，跑完它触发下一次尝试
        transport.failure(RuntimeException("no network"))
        scheduler.advance(2_500) // 第 2 档 base=2000ms

        // 第 3 档 base=4000ms：±20% 抖动最低也有 3200ms，
        // 若被误判成「后台短命连接」封顶到 3 秒，这里 3 秒内就会重连——不许发生，
        // 否则一次真实的断网会被当成每 3 秒空连一次。
        val before = transport.connectCount
        transport.failure(RuntimeException("no network"))
        scheduler.advance(3_000)
        assertEquals(
            "从未连上成功却被封顶到了 3 秒，会把断网场景变成每 3 秒空连一次",
            before,
            transport.connectCount,
        )
        // 再往后走完，确认它只是慢，不是不重连了。
        scheduler.advance(2_000)
        assertTrue("该继续按原退避重连，只是没被封顶", transport.connectCount > before)
    }

    @Test
    fun `重连定时器等待中回到前台：立刻重连并把退避归零`() {
        connection.setForeground(false)
        connection.start(config, "tk-1")
        transport.open()
        transport.replyOk(IMFrameType.HELLO, mapOf("session_id" to IMJson.Str("s-1")))

        scheduler.advance(2_000)
        transport.closed(1006, "colouros kill")
        assertTrue("这一步该有一个待发的重连定时器", connection.debugBackoffAttempts >= 1)
        val connectsBefore = transport.connectCount

        connection.setForeground(true)

        assertEquals("回前台该立刻重连，不等定时器", connectsBefore + 1, transport.connectCount)
        assertEquals("回前台该把退避归零", 0, connection.debugBackoffAttempts)
    }

    @Test
    fun `前台运行中的通话被切到后台：照样按后台节奏走（前台服务托着通话不影响这条判定）`() {
        // 对应任务规则第 6 条的确认项：通话中按 Home 键、App 本身进后台（即使前台服务
        // 还在跑），IMSignalConnection 这一层只看 setForeground 喂的信号，不知道也不
        // 关心「是不是在通话」——这正是设计上要的：封顶 3 秒比前台最长 30 秒的退避更快
        // 够上服务端 5 秒的等待窗口，对「后台但在通话」这种场景只有好处。
        connect() // 默认前台，模拟正在通话
        connection.setForeground(false) // 按 Home：App 后台，前台服务继续跑
        scheduler.advance(2_000)
        transport.closed(1006, "colouros kill")
        val before = transport.connectCount
        scheduler.advance(3_000)
        assertTrue("通话中切后台也该在3秒内重连", transport.connectCount > before)
    }

    /** fire：**立刻发、应答对不上号就丢**。应答要是漏进事件流，门面会把 `.ok` 当事件喂给状态机。 */
    @Test
    fun `fire 不等应答：立刻发出，应答不进事件流`() {
        connect()
        assertTrue(connection.fire(IMFrameType.CALL_HANGUP, mapOf("call_id" to IMJson.Str("c-1"))))

        val sent = transport.lastOf(IMFrameType.CALL_HANGUP) ?: error("fire 没发出去")
        assertEquals("c-1", (sent.data["call_id"] as IMJson.Str).value)
        assertTrue("请求帧必须带 req_id，服务端才回得了应答", sent.reqId.isNotEmpty())

        transport.deliver(IMFrameType.CALL_HANGUP + ".ok", sent.reqId)
        transport.deliver("sys.error", sent.reqId, mapOf("code" to IMJson.Num(1401), "name" to IMJson.Str("call_not_found")))
        assertTrue("应答不许漏进事件流", events.frames.none { it.first.startsWith(IMFrameType.CALL_HANGUP) })
        assertTrue("被拒也不该变成宿主的 onError", events.errors.isEmpty())
    }

    @Test
    fun `fire 在没连上时一帧都不发`() {
        assertEquals(false, connection.fire(IMFrameType.CALL_HANGUP, mapOf("call_id" to IMJson.Str("c-1"))))
        connection.start(config, "tk-1")
        assertEquals(false, connection.fire(IMFrameType.CALL_HANGUP, mapOf("call_id" to IMJson.Str("c-1"))))
        assertEquals("握手之前发业务帧会被服务端当成协议错误", 0, transport.countOf(IMFrameType.CALL_HANGUP))
    }

    private class RecordingEvents : IMSignalConnection.Events {
        val connected = mutableListOf<Pair<String, Boolean>>()
        val frames = mutableListOf<Pair<String, Map<String, IMJson>>>()
        val errors = mutableListOf<Pair<IMErrorCode, String>>()
        var kickedOut = 0
        val kickReasons = mutableListOf<IMKickedOutReason>()
        val tokenWarnings = mutableListOf<Long>()
        var disconnects = 0
        var unrecoverable = 0

        override fun onConnected(sessionId: String, resumed: Boolean) {
            connected += sessionId to resumed
        }

        override fun onDisconnected(code: Int, reason: String) {
            disconnects++
        }

        override fun onSessionUnrecoverable() {
            unrecoverable++
        }

        override fun onFrame(type: String, data: Map<String, IMJson>) {
            frames += type to data
        }

        override fun onKickedOut(reason: IMKickedOutReason) {
            kickedOut++
            kickReasons += reason
        }

        override fun onTokenWillExpire(expiresAtMs: Long) {
            tokenWarnings += expiresAtMs
        }

        override fun onError(code: IMErrorCode, message: String) {
            errors += code to message
        }
    }
}
