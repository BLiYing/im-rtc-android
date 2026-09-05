package com.imrtc.engine.protocol

/**
 * 通话结束原因，对应协议 §6 与 `docs/conformance/reasons.json`。
 *
 * **`call.ended` 是唯一终态帧**，每个成员设备收到且仅收到一条，所有结局都走它。
 * 收到表外的值必须折成 [ERROR]（§2.4 规则 6）——**禁止崩溃、禁止透传给 UI**，
 * 这是「新增枚举值不算破坏兼容」成立的前提。
 *
 * 同样是从向量生成的，理由见 [IMErrorCode]。
 */
internal enum class IMCallEndReason(
    val wire: String,
    /** 这个 reason 是否可能出现在**已接通**的通话上。 */
    val canBeConnected: Boolean,
    /** 这个 reason 下 `duration_sec` 是否可能 > 0。 */
    val durationPositive: Boolean,
) {
    /** 已接通成员主动挂断 */
    HANGUP("hangup", true, true),
    /** 主叫接通前取消 */
    CANCEL("cancel", false, false),
    /** 被叫主动拒接 */
    REJECT("reject", false, false),
    /** 服务端振铃超时 */
    NO_ANSWER("no_answer", false, false),
    /** 被叫已在别的通话 */
    BUSY("busy", false, false),
    /** 被叫无在线设备 */
    OFFLINE("offline", false, false),
    /** 本账号另一台设备接听 */
    ANSWERED_ELSEWHERE("answered_elsewhere", false, false),
    /** 本账号另一台设备拒绝 */
    REJECTED_ELSEWHERE("rejected_elsewhere", false, false),
    /** 被主持人或管理 API 移出 */
    KICKED("kicked", true, false),
    /** 房间被强制解散 */
    ROOM_CLOSED("room_closed", true, false),
    /** 掉线超过 30s 恢复窗口 */
    NETWORK("network", true, false),
    /** 服务端内部错误兜底 */
    ERROR("error", true, false),
    ;

    companion object {
        private val byWire = entries.associateBy { it.wire }

        /** 表外的值一律折成 [ERROR]（§2.4 规则 6 的兜底）。 */
        val FALLBACK = ERROR

        fun from(wire: String): IMCallEndReason = byWire[wire] ?: FALLBACK

        /**
         * 群通话的主导 reason：**按固定优先级取，不按先后顺序**（协议 §4.4）。
         * 五端必须算出同一个值，否则同一通电话在不同端上显示成不同结局。
         */
        val GROUP_DOMINANT_PRIORITY = listOf(REJECT, BUSY, NO_ANSWER, OFFLINE)

        /** 从各成员的裁决里取主导 reason；都不在优先级表里时返回 [FALLBACK]。 */
        fun dominant(memberOutcomes: List<IMCallEndReason>): IMCallEndReason =
            GROUP_DOMINANT_PRIORITY.firstOrNull { it in memberOutcomes } ?: FALLBACK

        /**
         * 通话时长：**向下取整的秒**，未接通恒为 0。
         * **各端禁止自己算时长**（时钟偏移），一律用服务端给的 `call.ended.duration_sec`；
         * 这个函数只用来对向量、以及服务端不可用时的兜底展示。
         */
        fun durationSec(connectedAtMs: Long, endedAtMs: Long): Long {
            if (connectedAtMs <= 0L || endedAtMs <= connectedAtMs) return 0L
            return (endedAtMs - connectedAtMs) / 1000L
        }
    }
}
