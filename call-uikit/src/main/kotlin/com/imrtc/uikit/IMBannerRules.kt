package com.imrtc.uikit

/**
 * 顶部橙条该写什么（规范 §08）：正在重连 / 连接已断开 / 对方网络不佳。
 *
 * # 为什么是一个纯函数，而不是几行写在 View 里
 *
 * 两类文案的**收起方式相反**，混在一起写就一定会踩其中一个：
 *
 * - **连接类**（重连 / 已断开）跟着 `connection` 走，恢复成 OK 的那一刻就该撤；
 * - **「对方网络不佳」**只停 [IMKitTheme.NETWORK_BANNER_MS] 就收成角标，由它自己的
 *   定时器撤。**不能跟着每一次渲染撤**——`room.active_speakers` 一秒来好几帧、
 *   每帧都渲染一次，跟着撤等于这条横幅根本来不及被看见。
 *
 * 原先那一版写成 `if (text.isNotEmpty() || connection != OK) banner.apply(text)`，
 * 想一个条件同时照顾这两点，结果**恰好把「恢复成 OK」这一格漏掉了**：那一刻 text 是
 * 空串、connection 又正好是 OK，条件为假，`apply("")` 一次都不会调——橙条就永远停在
 * 「正在重连…」。真机 2026-09-08 复现：信令早已恢复、上下行视频都回来了、心跳一路没断过，
 * 界面上那条橙条还挂着，**而且怎么操作都撤不掉**（每次重渲染都落到同一个假条件上）。
 * iOS 与 Web 在同一处都是对的（iOS 的 `else if !poor` 分支、Web 的声明式渲染），
 * 只有 Android 这一版有这个洞——所以把判断挪出 View，用例才守得住。
 */
internal object IMBannerRules {

    val RECONNECTING: String get() = IMText.t("banner.reconnecting")
    val LOST: String get() = IMText.t("banner.lost")
    val POOR: String get() = IMText.t("banner.peerNetwork")

    /**
     * 这一轮渲染橙条该写什么。
     *
     * @param current 橙条上**此刻真正写着**的文案（空串 = 没有横幅）。判断「该不该动」
     *   全靠它：只有连接类文案该由这里撤，网络不佳那条得留给它自己的定时器。
     * @param poorShown 「对方网络不佳」这一轮是不是已经出过了（出过就不再重复出）。
     * @return 要写上去的文案（空串 = 撤掉）；`null` = **这一轮不动橙条**。
     */
    fun next(
        connection: IMCallViewState.Connection,
        poor: Boolean,
        poorShown: Boolean,
        current: String,
    ): String? = when (connection) {
        IMCallViewState.Connection.RECONNECTING -> RECONNECTING
        IMCallViewState.Connection.LOST -> LOST
        IMCallViewState.Connection.OK -> when {
            poor && !poorShown -> POOR
            current == RECONNECTING || current == LOST -> ""
            else -> null
        }
    }
}
