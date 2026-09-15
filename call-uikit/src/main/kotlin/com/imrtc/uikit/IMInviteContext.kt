package com.imrtc.uikit

/**
 * 「添加成员」这一次要问的是哪一通电话（`HOST_INTEGRATION_DESIGN.md` §3.4）。
 *
 * 交给 [IMInviteMemberProvider] 的每个方法，取代原来「全局静态一份名单」的做法——
 * provider 需要知道**哪一通、哪个群**才能分页搜索、才能判断权限。
 */
class IMInviteContext(
    val callId: String,
    /** 宿主自己的群号，可能为空——不是每通电话都属于某个群。 */
    val chatGroupId: String,
    /** 主叫在 `IMCallOptions.userData` 里塞的 opaque 数据，原样透传，Kit 不解析。 */
    val userData: String,
    /** 这通电话的发起人 uid。他离场后服务端拉不回来（invite_more 回 bad_params），选人页把他置灰。 */
    val callerUid: String,
    val mediaType: String,
    /** 此刻在通话里 + 正在振铃的人，**含自己**。这些 uid 在选人页里显示「已在通话中」不可选。 */
    val participantUids: List<String>,
    /** 还能加几个人（= 9 − [participantUids] 人数）。选人页最多勾这么多。 */
    val slotsLeft: Int,
)
