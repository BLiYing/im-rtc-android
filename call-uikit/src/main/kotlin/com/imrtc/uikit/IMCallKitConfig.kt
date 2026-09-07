package com.imrtc.uikit

/**
 * 「添加成员」的候选人。**名单是宿主给的**——Kit 不内置联系人系统（CONVENTIONS §12）。
 * 与 iOS 的 `IMInviteCandidate` 同名同义。
 */
class IMInviteCandidate @JvmOverloads constructor(
    val uid: String,
    name: String = "",
) {
    val name: String = name.ifEmpty { uid }
}

/**
 * Kit 的可配项（草图 §02-D 那一屏其实就是这张清单）。
 *
 * **是引用类型、可以随时改**：宿主改完不用重启 Kit，下一次形态切换就读到新值。
 * 与 iOS 的 `IMCallKitConfig` 同名同义——五端的设置页应该长得一样。
 */
class IMCallKitConfig {

    /**
     * 来电先出顶部横幅，点横幅本体再展开成全屏（草图 §04）。关掉则来电直接全屏。
     *
     * **默认开**：用户正在打字时被一整屏盖住很粗暴。横幅 5s 不处理会自己升级为全屏。
     * 它只在**宿主有前台界面**时生效——App 在后台时没有可以挂横幅的地方，这时一律走全屏 Activity。
     */
    var bannerFirst: Boolean = true

    /**
     * 允许把通话收成小窗（草图 §04 / 交互稿 §08 差异 2）。关掉则通话页上没有「小窗」按钮。
     *
     * 视频通话**优先走系统画中画**（`enterPictureInPictureMode`）：不用权限、跨应用可见、行为符合系统习惯；
     * 画中画不可用（语音通话、旧系统、设备不支持）时退回应用内悬浮球。
     * **不申请 `SYSTEM_ALERT_WINDOW`**（CONVENTIONS §8）：那是敏感权限，会影响宿主上架。
     */
    var floatingWindow: Boolean = true

    /**
     * 群通话里「添加成员」的候选名单（交互稿 §05）。**名单是宿主给的**；不给就退化成 uid 输入框。
     * 自己不要放进来——呼叫名单里含主叫会被服务端拒掉。
     */
    var inviteCandidates: List<IMInviteCandidate> = emptyList()

    /**
     * uid → 本机该显示的名字与头像（见 [IMProfileResolver]）。
     *
     * **不设就退化成显示 uid**，与没有这个钩子时行为一致。
     * 宿主异步解析回来后调 [IMCallKit.reloadProfiles] 重画。
     */
    var profileResolver: IMProfileResolver? = null
}
