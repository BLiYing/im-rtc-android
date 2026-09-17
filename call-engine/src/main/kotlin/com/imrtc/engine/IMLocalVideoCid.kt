package com.imrtc.engine

/**
 * 本端摄像头轨道的 cid：**预览领到的那个，就是随后发布出去的那个**（设计文档 §7.5 `startLocalPreview(): cid`）。
 *
 * 原先预览用固定常量 `im-local-preview`，发布时才在 [IMLocalPublisher] 里现生成 cid——
 * 两套不相干的 id，宿主拿不到「预览那条轨道是谁」，也没法像 iOS / Web 那样 `attachLocalView(cid, view)` 认到底。
 *
 * # 为什么要锁
 *
 * [acquire] 在**调用方线程**上同步执行（`startLocalPreview()` 要当场把 cid 还给宿主），
 * [publish] / [reset] 却在 Engine 线程上。而「预览在途时再调一次」必须拿回同一个 cid，
 * 「关了再开」必须拿到新的——判据与改动得在同一把锁里。
 *
 * # 生命周期
 *
 * - 预览关掉（[releaseIfUnpublished]）且**还没发布**：作废，下次开是新 cid（与 Web 一致）；
 * - 已经发布：通话中关摄像头只停采集、不 unpublish，cid 留着，再开还是它；
 * - 这一轮媒体停掉（挂断 / 离房 / 登出，[reset]）：作废。
 */
internal class IMLocalVideoCid(private val nowMs: () -> Long) {
    private var cid: String? = null
    private var published = false

    /** 同一毫秒里关了又开也得是两个 cid（媒体层按 cid 认轨道）。 */
    private var seq = 0

    /** 预览要一个 cid：已有就给已有的（等同一次打开），没有就领新的。 */
    @Synchronized
    fun acquire(): String = cid ?: "local-video-${nowMs()}-${++seq}".also { cid = it }

    /** 发布视频用哪个 cid。之后 [releaseIfUnpublished] 不再作废它。 */
    @Synchronized
    fun publish(): String = acquire().also { published = true }

    /** 进房前关预览：没发布过才作废。返回是否作废了。 */
    @Synchronized
    fun releaseIfUnpublished(): Boolean {
        if (published) return false
        cid = null
        return true
    }

    @Synchronized
    fun reset() {
        cid = null
        published = false
    }
}
