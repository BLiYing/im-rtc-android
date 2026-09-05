package com.imrtc.uikit

/**
 * Kit 的可配项（草图 §02-D 那一屏其实就是这张清单）。
 *
 * **是引用类型、可以随时改**：宿主改完不用重启 Kit，下一次形态切换就读到新值。
 * 与 iOS 的 `IMCallKitConfig` 同名同义——四端的设置页应该长得一样。
 */
class IMCallKitConfig {

    /**
     * 来电先出顶部横幅，点横幅本体再展开成全屏（草图 §04）。关掉则来电直接全屏。
     *
     * **默认开**：用户正在打字时被一整屏盖住很粗暴。
     *
     * 它只在**宿主有前台界面**时生效——App 在后台时没有可以挂横幅的地方，
     * 这时一律走全屏 Activity（这也正是系统来电的做法）。
     */
    var bannerFirst: Boolean = true

    /**
     * 允许把通话收成悬浮球（草图 §04）。关掉则通话页上没有「小窗」按钮。
     *
     * **走应用内浮层，不申请 `SYSTEM_ALERT_WINDOW`**（CONVENTIONS §8）：
     * 那是敏感权限，会影响宿主上架，不该由一个通话 SDK 替宿主做这个决定。
     * 代价是**离开宿主 App 悬浮球就看不见了**——通话本身不受影响，回到 App 它还在。
     */
    var floatingWindow: Boolean = true
}
