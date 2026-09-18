package com.imrtc.engine.webrtc

import com.imrtc.engine.log.IMRTCLog
import org.webrtc.VideoTrack

/*
 远端画面的**挂载与换绑**：轨道、归属、渲染器三者到达顺序不定，全都汇到这里判重。

 从 IMWebRTCAdapter 拆出来是体量红线（CONVENTIONS §2，那个文件贴着 600 行），
 与同目录的 IMCaptureExtensions.kt 一个路子。三张表因此从 private 放宽到 internal——
 只在本模块内可见，对宿主仍是封闭的。
 */

/**
 * bindRemoteTracks 把「已认领归属 + 有渲染器」的远端轨道接上去。
 *
 * 轨道、归属、渲染器三者**到达顺序完全不定**，所以三条路径（onRemoteTrack /
 * claimRemoteTracks / attachView）都调它，由这一个地方判重与换绑。
 */
internal fun IMWebRTCAdapter.bindRemoteTracks() {
    for ((trackId, track) in remoteVideo) {
        val renderer = trackOwners[trackId]?.let { renderers[it] }
        val boundRenderer = attached[trackId]
        val boundTrack = attachedTracks[trackId]
        // 轨道与渲染器**两样都要比**，摘的也是上一次那条轨道（见 attachedTracks）。
        if (boundRenderer === renderer && boundTrack === track) continue
        if (boundRenderer != null) boundTrack?.safeRemoveSink(boundRenderer)
        val owner = trackOwners[trackId] ?: ""
        if (renderer == null) {
            attached.remove(trackId)
            attachedTracks.remove(trackId)
            IMRTCLog.i("media", "远端画面已解绑：track_id=$trackId uid=$owner")
            continue
        }
        runCatching { track.addSink(renderer) }
        attached[trackId] = renderer
        attachedTracks[trackId] = track
        // **画面串格子只能从这里查**：渲染器是哪块、归属是谁，日志里没有就只能靠猜
        // （2026-09-18 真机：carol 的画面出现在 bot02 的格子里）。
        IMRTCLog.i(
            "media",
            "远端画面已绑定：track_id=$trackId uid=$owner renderer=${System.identityHashCode(renderer)}",
        )
    }
}

/** 卸掉某个 uid 的渲染器：先把挂在它上面的轨道摘干净，再 release（反了会崩在 native 层）。 */
internal fun IMWebRTCAdapter.detachRenderer(uid: String) {
    val previous = renderers.remove(uid) ?: return
    val gone = attached.filterValues { it === previous }.keys.toList()
    for (trackId in gone) {
        attachedTracks[trackId]?.safeRemoveSink(previous)
        attached.remove(trackId)
        attachedTracks.remove(trackId)
    }
    runCatching { previous.release() }
}
