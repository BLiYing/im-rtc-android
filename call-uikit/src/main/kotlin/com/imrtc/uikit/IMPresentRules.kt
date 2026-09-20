package com.imrtc.uikit

/**
 * 「形态没变，但通话页其实不在前台」时要不要重新拉起——纯判据，不碰平台类（CONVENTIONS §1）。
 *
 * **为什么要有**（2026-09-20 真机复现，Android 日志 20:46 ~ 21:31）：通话页全屏时按 Home 进后台，停 45 分钟再回来，
 * 停在宿主首页，通话页没了，而 Kit 的形态还是 `fullscreen`。进程、信令、媒体一路都活着
 * （`room.quality` 每 5 分钟 150 条一条不少），服务端认为人还在房里，对端一直显示他在通话中。
 * 根因是 [IMCallPresentation.apply] 只在「形态变了」的那一次拉起通话页：形态已经是 `fullscreen`，
 * 页面被系统收走之后没人再拉，回前台也不拉。
 *
 * **判据**：形态是全屏、通话没结束，而**宿主的页面回到了前台**——通话页在前台的话，宿主页面不可能同时 resumed，
 * 所以这一刻通话页一定不在前台。用户没有别的办法离开全屏页（返回键在能收小窗时收小窗，否则没反应），
 * 所以这一条不会和用户的操作打架。
 *
 * 三个不算：
 * - 通话已经 IDLE（没有要回的通话）。
 * - 前台页是权限弹窗页（[IMPermissionActivity]）：通话中申请权限时它会 resume，这时把通话页盖上去会挡住弹窗。
 * - 刚拉起过不久：`startActivity` 到通话页 resume 之间宿主页可能又 resume 一次，别连环拉。
 */
internal object IMPresentRules {

    /** 两次拉起至少隔这么久。通话页从 startActivity 到 resume 通常几百毫秒，留足余量。 */
    const val MIN_GAP_MS = 1500L

    fun shouldRepresent(
        phase: IMCallViewState.Phase,
        hostResumed: Boolean,
        hostVisible: Boolean,
        sinceLastPresentMs: Long,
    ): Boolean =
        phase != IMCallViewState.Phase.IDLE &&
            hostResumed &&
            hostVisible &&
            sinceLastPresentMs >= MIN_GAP_MS
}
