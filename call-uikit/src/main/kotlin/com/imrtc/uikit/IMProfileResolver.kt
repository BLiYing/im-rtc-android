package com.imrtc.uikit

import android.graphics.drawable.Drawable

/**
 * 宿主身份解析：把 uid 翻成**这台设备上该显示的**名字与头像。
 *
 * # 为什么这件事必须交给宿主
 *
 * Kit 只认识 uid。而宿主的显示名往往是一条链——备注 > 群昵称 > 昵称——其中
 * **备注是查看者私有的**：同一个 uid 在五个人的九宫格里可能要显示五个不同的名字。
 * 服务端只有一份房间状态、一条广播通道，它在物理上就发不出五个不同的值；
 * 硬塞进协议的结果是「我给某人起的私房备注被广播给了全房间」。
 *
 * 所以这条链整个留在宿主，Kit 只开一个口子问它。
 * 详见 im-rtc-server/docs/design/HOST_PROFILE_DISPLAY_DESIGN.md §1、§3。
 *
 * # 两条形状约束
 *
 * 1. **同步返回**。宿主的解析器（IMProgram 的 IMUserProfileCache 一类）都是
 *    「命中就返回，没命中返回空并在后台攒一批去拉」。Kit 在绘制路径上调用它，
 *    解析不到时先画兜底，等宿主调 [IMCallKit.reloadProfiles] 再重画。
 * 2. **头像给已经加载好的 Drawable，不给 URL**。Kit 不知道宿主的 base host、
 *    不知道要不要带鉴权头、也不该把某个图片库强加给宿主
 *    （本仓不引第三方 UI 库，见 CLAUDE.md）。**Kit 不发任何指向宿主域的请求。**
 *
 * # 不实现会怎样
 *
 * 退化成显示 uid。作为通用 SDK 这是合理默认——「内部 ID 不该上屏」是某个宿主的
 * 产品纪律，不是本产品的。
 */
interface IMProfileResolver {

    /**
     * 这个 uid 在本机该显示成什么名字。
     *
     * 返回 `null` 或空白 = 还没解析到（或查到了但没名字），Kit 会用兜底（默认 uid）。
     * **空白也算没有**：「查到了但名字是空的」直接用会让格子上什么都没有。
     */
    fun displayName(uid: String): String?

    /**
     * 这个 uid 的头像，**已经加载好的 Drawable**。
     *
     * 返回 `null` = 没有头像，Kit 退化成首字母色块。
     */
    fun avatar(uid: String): Drawable? = null
}

/**
 * 取本机该显示的名字。解析不到（或名字是空白）时返回 [fallback]，调用方通常传 uid。
 *
 * 本端那格 uid 是空串——不解析，直接用 fallback（那就是「我」）。
 *
 * **resolver 显式传进来、不从 IMCallKit 里读**：IMCallKit 是个在类初始化时就建
 * `Handler(Looper.getMainLooper())` 的 object，一碰它这条纯逻辑就只能上 Robolectric 才跑得起来。
 * 显式传参让它留在纯 JVM 单测里（CONVENTIONS §11）。
 */
internal fun resolvedName(resolver: IMProfileResolver?, uid: String, fallback: String): String {
    if (uid.isEmpty()) return fallback
    val name = resolver?.displayName(uid)?.trim().orEmpty()
    return name.ifEmpty { fallback }
}

/** 取本机该显示的头像；没有就返回 null，调用方退化成首字母色块。 */
internal fun resolvedAvatar(resolver: IMProfileResolver?, uid: String): Drawable? {
    if (uid.isEmpty()) return null
    return resolver?.avatar(uid)
}

/**
 * 标题栏那一行：1v1 写对方名字（宿主解析，解析不到才是 uid）；
 * 群通话 / 会议是人数 / 房号，与宿主无关，原样用 [IMCallViewState.titleText]。
 */
internal fun resolvedTitle(resolver: IMProfileResolver?, state: IMCallViewState): String {
    val oneToOne = !state.isGroup && !state.isMeeting && state.peer.isNotEmpty()
    return if (oneToOne) resolvedName(resolver, state.peer, state.titleText) else state.titleText
}
