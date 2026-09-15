package com.imrtc.engine.signaling

import com.imrtc.engine.IMKickedOutReason
import com.imrtc.engine.protocol.IMErrorCode
import com.imrtc.engine.protocol.IMJson

/**
 * 握手失败该不该一次就放弃，放弃的话按哪种原因抛给宿主。返回 `null` = 照常退避重连。
 *
 * 从 [IMSignalConnection] 拆出来是体量红线（CONVENTIONS §2）；它本来也是一条纯判定，
 * 与 [IMReconnectPolicy] 同一个形状（Web 端同名文件 `handshakeGiveUp.ts`）。
 *
 * **不可重试 ≠ 参数不对**，三类的处置完全不同，合成一类就等于给宿主一条错的建议：
 *
 * | 码 | 抛什么 | 宿主该做什么 |
 * |---|---|---|
 * | 1101 `token_invalid` | [IMKickedOutReason.AUTH_EXPIRED] | **换一枚票再来**。签名密钥轮换、票被吊销都长这样，而换票正好救得了——`IMSignalConnection` 类注释第 1 条那次 Web 事故就是它 |
 * | 1104 `kicked_out` | [IMKickedOutReason.TAKEN_OVER] | 回登录页。服务端的吊销名单走的就是「`sys.error{1104}` + 4403」这一对 |
 * | 1004 / 1006 / 1106 … | [IMKickedOutReason.CONFIG_REJECTED] | 去改配置。换票和重试都救不了——`device_id` 里那个空格不会因为再来一次就没了 |
 *
 * 两条边界：
 *
 * 1. **local 组的码不是服务端的裁决。** `IMSignalConnection.stop` 会拿 `2007 not_logged_in`
 *    把在飞的握手结掉，那是宿主自己按的退出；不挡掉的话，一次正常的 `logout()` 会报成
 *    「服务端拒了你的参数」，而 `relogin()` 正是先 `logout()` 再换票的——
 *    静默续期会当场变成把人踹回登录页。
 * 2. **本端不认识的码信帧上自带的 `retryable`。** 本端这张表是上次同步时的快照，
 *    漏一个新码就退回「无限重连」——本仓漏过 1106 一次，症状正是这里要根治的那个。
 */
internal fun handshakeGiveUpReason(code: IMErrorCode?, payload: Map<String, IMJson>): IMKickedOutReason? {
    if (code != null && !code.isWire) return null
    val retryable = code?.retryable
        ?: (payload["retryable"] as? IMJson.Bool)?.value
        // 连码带标志都读不出来：当可重试处理，维持「不认识就先退避着」的老行为。
        ?: true
    if (retryable) return null
    return when (code) {
        IMErrorCode.TOKEN_INVALID -> IMKickedOutReason.AUTH_EXPIRED
        IMErrorCode.KICKED_OUT -> IMKickedOutReason.TAKEN_OVER
        else -> IMKickedOutReason.CONFIG_REJECTED
    }
}
