package com.imrtc.engine.webrtc

/**
 * pub 侧 ICE 自愈的**放弃判定**（协议 §7.2）。
 *
 * # 为什么要有这个东西
 *
 * ICE 判 `failed` 不等于通话废了——切网、锁屏、进电梯都会抖一下，
 * 见着就报等于把「正在自愈」误报成「通话废了」。
 * 但反过来「一律自愈、永不上报」更糟：重启失败还会再进 `failed`，
 * 于是天然形成一个**永久重试节奏**，宿主从头到尾收不到任何信号，
 * 而对端的格子早就黑了、计时器还在走。
 *
 * 所以要有个「救不回来了」的判据：连续 [giveUpAfter] 次重启后仍失败才报**一次**。
 *
 * # 为什么单独一个类
 *
 * 与 [IMNegotiationGate] 同一个理由：`IMPeerConnections` 要造
 * `PeerConnectionFactory`，JVM 单测里起不来（缺原生库）。
 * 判定逻辑摘出来就能直接测，而它恰恰是最容易写错的那部分——
 * 差一个 `>=` 就变成「永远不报」或者「每次都报」。
 *
 * **不是线程安全的**：调用方（`IMPeerConnections.Observer`）只在 WebRTC 的
 * signaling 线程上碰它，与那里的其它可变状态同一个并发模型。
 */
internal class IMIceGiveUp(private val giveUpAfter: Int = 3) {
    private var restarts = 0
    private var gaveUp = false

    /**
     * noteFailure 记一次失败，返回**这一次该不该上报 2006**。
     *
     * 同一轮故障只会返回一次 true：放弃是「告诉宿主一声」，不是「不救了」，
     * 调用方照样继续重启，只是不再重复刷这条错误。
     */
    fun noteFailure(): Boolean {
        restarts += 1
        if (restarts < giveUpAfter || gaveUp) return false
        gaveUp = true
        return true
    }

    /** noteConnected 在这条 PC 通了之后清零——那是新一轮，不该拿旧账凑数。 */
    fun noteConnected() {
        restarts = 0
        gaveUp = false
    }

    /** restartCount 供日志与测试观察，不参与判定。 */
    fun restartCount(): Int = restarts
}
