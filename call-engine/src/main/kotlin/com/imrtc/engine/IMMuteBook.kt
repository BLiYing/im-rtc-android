package com.imrtc.engine

/**
 * 静音意图簿：**记住用户想要什么，等轨道发布了再补做**。
 *
 * # 它挡的是什么
 *
 * 「正在呼叫…」阶段按静音时，通话还没接通、房间还没进、轨道压根还没发布——
 * 那一刻既没有本端轨道可关，也没有 `track_id` 可发 `room.mute`。
 * 原先 `IMCallEngine.setMuted` 在这里直接早退（只留一条 WARN），
 * 而界面是**无条件**翻成「已静音」的：于是对方接通后，轨道按默认（开着）发布出去，
 * **你以为自己静音了，对方照样听得见**。真机 2026-09-09 alice 那通群呼就是这一幕。
 *
 * **这不是显示错乱，是隐私问题。**
 *
 * # 为什么单独一个类
 *
 * 两个理由：
 *
 *  1. **它是纯逻辑，可以单独测**——不认识 `IMMediaAdapter`、不认识状态机，
 *     只认两张表（本端轨道、服务端分配的 track_id）。「呼叫中就静音」这条路
 *     全是时序，不抽出来就只能靠端到端用例去撞。
 *  2. `IMCallEngine.kt` 已经贴着 600 行的体量红线（CONVENTIONS §2）。
 *
 * 与房间机的 `bufferedOps` 是同一个思路：那边缓存的是「进房前的房间操作」，
 * 这边缓存的是「发布前的静音意图」。
 */
internal class IMMuteBook {

    /** kind → 用户想要的静音状态。 */
    private val desired = LinkedHashMap<String, Boolean>()

    /** 要补做的一条：本端轨道要关，`room.mute` 也要发。 */
    data class Pending(val kind: String, val trackId: String, val muted: Boolean)

    /** 记下用户的意图。轨道在不在都记——在的时候调用方会立刻应用，不在的时候等 [pending]。 */
    fun want(kind: String, muted: Boolean) {
        desired[kind] = muted
    }

    /** 这一类此刻想要的状态；没按过就是 null。 */
    fun wanted(kind: String): Boolean? = desired[kind]

    /**
     * 意图跟着这一轮媒体一起作废。
     *
     * **通话结束时必须调**：留着的话会变成「上一通静音过，这一通莫名其妙也是静音的」。
     */
    fun clear() {
        desired.clear()
    }

    /**
     * 算出「这一步刚拿到 track_id」的那些轨道要补做什么。
     *
     * 判据是 **track_id 从无到有**（`before` 里没有、`after` 里有），不是「表里有」——
     * 后者会让每一帧下行都重发一遍 `room.mute`，那是纯粹的噪声。
     *
     * @param localTracks cid → kind，本端已发布的轨道
     * @param before / [after] 这一步前后的 cid → track_id
     */
    fun pending(
        localTracks: Map<String, String>,
        before: Map<String, String>,
        after: Map<String, String>,
    ): List<Pending> {
        if (desired.isEmpty()) return emptyList()
        val out = mutableListOf<Pending>()
        for ((cid, kind) in localTracks) {
            val trackId = after[cid] ?: continue
            if (before[cid] == trackId) continue // 不是这一步新拿到的
            val muted = desired[kind] ?: continue
            out += Pending(kind, trackId, muted)
        }
        return out
    }
}
