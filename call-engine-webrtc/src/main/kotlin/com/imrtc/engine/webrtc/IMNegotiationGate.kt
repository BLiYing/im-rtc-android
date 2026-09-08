package com.imrtc.engine.webrtc

/**
 * 一条 PeerConnection 上的**协商闸门**：同一时刻只许一个 offer 在飞。
 *
 * # 为什么必须串行
 *
 * 一条 PC 上同时飞两个 offer，第二条 answer 回来时状态已经是 stable，
 * native 层直接报 `Called in wrong state: stable`，那条轨道就再也协商不上了。
 * 真机上一跑就撞到：发布 audio 与 video 两条轨道 → 两次 publish.ok → 两次 offer。
 *
 * # 为什么单独一个类
 *
 * 两个理由，都不是洁癖：
 *
 *  1. **它必须是线程安全的，而原先不是。** 这三个集合会被**三个线程**并发读写——
 *     信令线程（`restart_pub_ice` 之类的动作）、WebRTC 的信令线程（`setRemoteDescription`
 *     的 `onSetSuccess` 回调里放闸）、以及 PC observer 线程（ICE 进 FAILED 时重启）。
 *     原先是三个裸 `mutableSetOf`，竞态下 `negotiating -= pc` 会丢失，
 *     那条 PC **从此永远卡在「协商进行中」**，后续任何 offer（包括恢复后的 ICE 重启）
 *     都只排队、永不发出。真机 2026-09-08 正是这样：Wi-Fi 关掉再打开，
 *     信令恢复了、下行也恢复了，**上行再也没协商过一次**，对端全程看不到画面，
 *     而界面停在「正在重连」——日志里一条错误都没有。
 *  2. **`call-engine-webrtc` 里其余代码都要 libwebrtc，纯 JVM 单测跑不了。**
 *     闸门抽出来之后它不认识 WebRTC，于是那条「多线程狂敲也不许卡死」的用例才写得出来。
 *
 * # 闸门必须在**每一个**终局释放
 *
 * 不只是成功那条路。失败（createOffer / setLocal / setRemote 出错）、
 * 以及「这条 answer 来晚了、状态已经 stable，丢掉」那条分支，都得放闸——
 * 少放一处就是永久卡死，而且**没有任何报错**，只表现为「对端再也看不到我」。
 */
internal class IMNegotiationGate {
    private val lock = Any()

    /** 正在飞 offer 的 PC。 */
    private val negotiating = mutableSetOf<String>()

    /** 「刚才想发但那时有 offer 在飞」，等这一轮收工要补一次。 */
    private val pendingOffer = mutableSetOf<String>()

    /**
     * 「要重启 ICE，但那一刻有 offer 在飞」。
     *
     * **不能和 [pendingOffer] 合并**：补协商补的是一个普通 offer，
     * 丢了 ICE restart 这一位，网断了这条 PC 就永远重连不上，而日志里一切正常。
     */
    private val pendingIceRestart = mutableSetOf<String>()

    /**
     * 申请发一个 offer。
     *
     * @return `null` 表示**这一轮不发**（已经有 offer 在飞，已记下待补）；
     *   非空表示可以发，值是「这一轮要不要带 ICE restart」。
     */
    fun beginOffer(pc: String, iceRestart: Boolean): Boolean? = synchronized(lock) {
        if (pc in negotiating) {
            pendingOffer += pc
            if (iceRestart) pendingIceRestart += pc
            return null
        }
        negotiating += pc
        // 上一轮想重启但当时有 offer 在飞，这一轮补上。
        return iceRestart || pendingIceRestart.remove(pc)
    }

    /**
     * 这一轮协商**成功**收工。
     *
     * @return 是否还有排队的 offer 要立刻补一个。
     */
    fun finishOffer(pc: String): Boolean = synchronized(lock) {
        negotiating -= pc
        return pendingOffer.remove(pc)
    }

    /**
     * 这一轮协商**失败**收场（createOffer / setLocal / setRemote 报错，或应答被丢弃）。
     *
     * 与 [finishOffer] 的差别只有一处：**不补排队的那一个**。
     * 这一轮都失败了，立刻再发一个多半也是同样的下场，交给上层的重试节奏
     * （ICE 进 FAILED 会重启、会话恢复会重新协商）去驱动，别在这里自旋。
     */
    fun abortOffer(pc: String) = synchronized(lock) {
        negotiating -= pc
    }

    /** 只置「下一个 offer 要重启 ICE」这一位，不自己发 offer。 */
    fun markIceRestart(pc: String) = synchronized(lock) {
        pendingIceRestart += pc
    }

    /**
     * 会话恢复之后重置这条 PC 的在飞状态。
     *
     * **换了一条连接，之前那个 offer 的 answer 永远不会回来了**——它是从旧连接上发出去的，
     * 服务端的应答也回到了那条已经死掉的 socket 上。不重置的话闸门一直关着，
     * 恢复后的重新协商只会排队，那条 PC 就此永久沉默。
     *
     * 排队标记与 ICE restart 标记**照旧保留**：那两个说的是「还欠一次协商 / 还欠一次重启」，
     * 换连接不改变这个事实，恰恰是恢复之后要补的东西。
     */
    fun resetInFlight(pc: String) = synchronized(lock) {
        negotiating -= pc
    }

    /** 释放两条 PC 时清空全部状态。 */
    fun clear() = synchronized(lock) {
        negotiating.clear()
        pendingOffer.clear()
        pendingIceRestart.clear()
    }

    /** 仅供测试与排查：这条 PC 此刻是不是有 offer 在飞。 */
    fun isNegotiating(pc: String): Boolean = synchronized(lock) { pc in negotiating }
}
