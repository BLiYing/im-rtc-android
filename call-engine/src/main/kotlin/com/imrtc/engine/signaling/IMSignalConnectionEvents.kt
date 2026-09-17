package com.imrtc.engine.signaling

import com.imrtc.engine.IMKickedOutReason
import com.imrtc.engine.protocol.IMErrorCode
import com.imrtc.engine.protocol.IMJson

/**
 * [IMSignalConnection] 连接层向上的出口。全部在 engine 线程上回调。
 *
 * 单独成文件是体量拆分（CONVENTIONS §2）：纯接口声明，没有任何逻辑，
 * 挪出去不影响 [IMSignalConnection] 的行为。
 */
internal interface IMSignalConnectionEvents {
    /** 握手成功。`resumed` 决定房间要不要归零（§1.4）。 */
    fun onConnected(sessionId: String, resumed: Boolean)

    /** 连接断开。`code` 是**真实关闭码**，没有就给 0；`willReconnect` 见 [IMSignalConnection.handleClosed]。 */
    fun onDisconnected(code: Int, willReconnect: Boolean)

    /** 一条下行帧（事件或双向帧；应答已经在本层配对掉了）。 */
    fun onFrame(type: String, data: Map<String, IMJson>)

    /**
     * 被踢下线，**不会自动重连**。
     *
     * `reason` 决定宿主该做什么，两者处置相反——合并成一个「被踢」的话，
     * 宿主只能都当登录失效处理，把本可静默恢复的场景也变成「请重新登录」。
     */
    fun onKickedOut(reason: IMKickedOutReason)

    /** 票快到期了，宿主该去取新票并 updateToken。见 [IMTokenExpiryTimer]。 */
    fun onTokenWillExpire(expiresAtMs: Long)

    /**
     * 断得太久了，**服务端那一侧的会话已经不可能再恢复**（§1.4 的恢复窗口过了）。
     *
     * 与「重连上了但 `resumed=false`」是同一件事，只是**不必等重连成功**——
     * 网络一直不回来的话那一刻永远不会到。少了它，界面就永远停在「正在重连」、
     * 连挂断都点不动（真机 2026-09-08）。
     */
    fun onSessionUnrecoverable()

    /** 连接层自己的错误（解析失败、握手被拒等）。 */
    fun onError(code: IMErrorCode, message: String)

    /**
     * 一次**还没握手成功**的连接尝试失败了（连不上、握手被拒），紧跟在那条关闭之后、重连或放弃之前。
     *
     * 门面拿它给 `login` 的结果收尾：连上之前的失败没有 `onDisconnected`（关闭码是 0 时不抛），
     * 不接这一条的话网络不通时登录的回调永远不来。
     */
    fun onConnectAttemptFailed() {}
}
