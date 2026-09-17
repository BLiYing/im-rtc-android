package com.imrtc.engine.webrtc

import com.imrtc.engine.media.IMVideoProfile
import org.webrtc.VideoCapturer
import org.webrtc.VideoSink
import org.webrtc.VideoTrack

/**
 * [IMWebRTCAdapter] 里几处逐字重复的小动作，单独成文件是体量拆分
 * （CONVENTIONS §2：`IMWebRTCAdapter.kt` 已经贴着 600 行红线）。
 */

/** `track.removeSink(sink)` 包一层 runCatching：轨道可能已经 dispose，removeSink 会抛。 */
internal fun VideoTrack.safeRemoveSink(sink: VideoSink) = runCatching { removeSink(sink) }

/** 用 [IMVideoProfile] 起采集：`active.startCapture(w, h, fps)` 三处逐字重复过。 */
internal fun VideoCapturer.startCapture(profile: IMVideoProfile) =
    startCapture(profile.width, profile.height, profile.frameRate)
