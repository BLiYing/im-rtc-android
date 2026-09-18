package com.imrtc.uikit

/*
 顶部橙条那一小块：「正在重连…」「连接已断开」「对方网络不佳」。

 从 `IMCallView` 拆出来是体量红线（CONVENTIONS §2，那个文件贴着 600 行）；
 这一刀也切得开：**该不该换横幅**的判据全在 [IMBannerRules]（纯逻辑、有单测），
 这里只剩「把它写上去」与那条 2s 的定时器。
 */

/** 橙条：文案怎么定见 [IMBannerRules]，这里只管把它写上去、以及给「网络不佳」那条排定时器。 */
internal fun IMCallView.renderBanner(state: IMCallViewState) {
    val poor = !state.isGroup && state.members.values.any { IMCallViewState.isNetworkPoor(it.networkLevel) } // 只做 1v1
    if (!poor) poorShown = false
    val next = IMBannerRules.next(state.connection, poor, poorShown, bannerText) ?: return
    if (next == IMBannerRules.POOR) {
        poorShown = true
        // 定时器**只撤自己那条**：这 2s 里连接可能已经断了，那时橙条上写的是
        // 「正在重连…」，不认一下就会把它一起抹掉。
        main.postDelayed(
            { if (bannerText == IMBannerRules.POOR) applyBanner("") },
            IMKitTheme.NETWORK_BANNER_MS,
        )
    }
    applyBanner(next)
}

private fun IMCallView.applyBanner(text: String) {
    if (text == bannerText) return
    bannerText = text
    banner.apply(text)
}
