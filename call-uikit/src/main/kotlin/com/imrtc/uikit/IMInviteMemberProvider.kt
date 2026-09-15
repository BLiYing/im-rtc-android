package com.imrtc.uikit

import android.app.Activity

/**
 * `provider.loadCandidates` 的结果出口。**必须回调，且这一页只回调一次**——
 * Kit 用「10 秒未回调」判超时（容信 iOS 现有实现在账号全无效时不回调，页面永远转圈，
 * `HOST_INTEGRATION_DESIGN.md` §3.4 点名的坑）。
 */
interface IMInviteCandidatesCallback {
    /** `nextCursor` 为 null 或空串表示没有下一页。 */
    fun onResult(items: List<IMInviteCandidate>, nextCursor: String?)

    /** 拉取失败：Kit 会显示「加载失败」+ 重试按钮。 */
    fun onError(message: String)
}

/** 宿主接管选人页时，把结果交回来（[IMInviteMemberProvider.presentInvitePicker]）。空数组 = 用户取消。 */
fun interface IMPickedCallback {
    fun onPicked(uids: List<String>)
}

/**
 * 按通话向宿主要「添加成员」的候选人（`HOST_INTEGRATION_DESIGN.md` §3.4），
 * 取代原来的静态 [IMCallKitConfig.inviteCandidates]。挂在 [IMCallKitConfig.inviteMemberProvider] 上。
 *
 * 三个方法都有默认实现，**Java 只需要覆盖 [loadCandidates]** 就能跑起来。
 */
interface IMInviteMemberProvider {

    /**
     * 拉一页候选人。`query` 为空串表示默认列表；`cursor` 为 null 表示第一页
     * （取自上一页 [IMInviteCandidatesCallback.onResult] 的 `nextCursor`）。
     *
     * 小群一次返回全部（`nextCursor` 传 null）；超级群走宿主自己的服务端搜索与分页。
     */
    fun loadCandidates(
        ctx: IMInviteContext,
        query: String,
        cursor: String?,
        callback: IMInviteCandidatesCallback,
    )

    /**
     * 整页换成宿主自己的选人页。返回 true 表示宿主接管，Kit 不再弹自带选人页；
     * 宿主选完把 uid 交给 `onPicked`（空数组 = 用户取消），**由 Kit 调 `inviteMore`**，
     * 宿主自己不必再发帧。
     *
     * 默认 false（不接管，走 Kit 自带的 [IMInvitePicker]）。
     */
    fun presentInvitePicker(activity: Activity, ctx: IMInviteContext, onPicked: IMPickedCallback): Boolean = false

    /** 宿主的权限规则（例：群禁言时仅管理员可加人）。默认 true。 */
    fun canInvite(ctx: IMInviteContext): Boolean = true
}
