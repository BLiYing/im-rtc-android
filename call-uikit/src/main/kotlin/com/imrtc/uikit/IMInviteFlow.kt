package com.imrtc.uikit

import android.app.Activity

/**
 * 「添加成员」取名单的优先级与收尾（`HOST_INTEGRATION_DESIGN.md` §3.4）：
 * 宿主接管选人页 > provider > 静态 `inviteCandidates`（兼容） > 空态。
 *
 * 从 [IMCallKit.showInvitePicker] 搬出来——那个文件已经踩着体量红线（CONVENTIONS §2）。
 */
internal object IMInviteFlow {

    fun show(
        activity: Activity,
        state: IMCallViewState,
        config: IMCallKitConfig,
        onInvite: (List<String>) -> Unit,
    ) {
        val ctx = buildContext(state)
        val provider = config.inviteMemberProvider

        if (provider != null) {
            if (!provider.canInvite(ctx)) {
                IMCallKit.hint(IMText.t("hint.inviteNotAllowed"))
                return
            }
            // 宿主的钩子：**吞掉它可能抛出的异常**——一个第三方 provider 崩溃不该带崩通话页。
            val handled = runCatching {
                provider.presentInvitePicker(activity, ctx, IMPickedCallback { picked -> if (picked.isNotEmpty()) onInvite(picked) })
            }.getOrDefault(false)
            if (handled) return
            IMInvitePicker(activity, ctx, ProviderSource(ctx, provider), config.allowsManualUidInput, onInvite).show()
            return
        }

        // 静态兼容：一次性给全部候选人，Picker 按同一套逻辑过滤/搜索/分页（这里恒无下一页）。
        IMInvitePicker(activity, ctx, StaticSource(config.inviteCandidates), config.allowsManualUidInput, onInvite).show()
    }

    /** 从当前通话视图状态拼一份 [IMInviteContext]。 */
    private fun buildContext(state: IMCallViewState): IMInviteContext {
        val selfUid = IMCallKit.engine?.uid.orEmpty()
        // 主叫侧 state.caller 没值（只有被叫侧才记发起人）——本端自己就是发起人。
        val callerUid = if (state.role == "caller") selfUid else state.caller
        val participants = LinkedHashSet<String>()
        if (selfUid.isNotEmpty()) participants += selfUid
        participants += state.members.keys
        return IMInviteContext(
            callId = state.callId,
            chatGroupId = state.chatGroupId,
            userData = state.userData,
            callerUid = callerUid,
            mediaType = state.mediaType,
            participantUids = participants.toList(),
            slotsLeft = state.inviteSlotsLeft,
        )
    }
}

/** [IMInvitePicker] 消费候选人的统一接缝：provider 分页拉取，或者静态名单一次给全部。 */
internal fun interface IMInviteSource {
    fun load(query: String, cursor: String?, callback: IMInviteCandidatesCallback)
}

private class ProviderSource(
    private val ctx: IMInviteContext,
    private val provider: IMInviteMemberProvider,
) : IMInviteSource {
    override fun load(query: String, cursor: String?, callback: IMInviteCandidatesCallback) =
        provider.loadCandidates(ctx, query, cursor, callback)
}

/** 静态名单：本地按 uid / name 过滤，一次性给全部、永远没有下一页。 */
private class StaticSource(private val all: List<IMInviteCandidate>) : IMInviteSource {
    override fun load(query: String, cursor: String?, callback: IMInviteCandidatesCallback) {
        val q = query.trim()
        val filtered = if (q.isEmpty()) all else all.filter { it.uid.contains(q, ignoreCase = true) || it.name.contains(q, ignoreCase = true) }
        callback.onResult(filtered, null)
    }
}
