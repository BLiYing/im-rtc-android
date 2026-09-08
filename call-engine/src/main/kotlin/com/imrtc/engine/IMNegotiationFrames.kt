package com.imrtc.engine

import com.imrtc.engine.media.IMMediaAdapter
import com.imrtc.engine.protocol.IMFrameType
import com.imrtc.engine.protocol.IMJson

/**
 * 把协商类下行帧原样喂给媒体层。
 *
 * **状态机不认识 SDP**（它是纯函数，跑一致性向量），所以 `room.offer` / `room.answer` /
 * `room.ice_candidate` 这三帧要绕过它直接进媒体层。
 *
 * 做成自由函数而不是门面的私有方法：它不碰门面的任何状态，只是一层字段翻译——
 * 留在门面里既让那个文件更长（它贴着 600 行红线），也让人误以为这里有状态可依赖。
 */
internal fun IMMediaAdapter.applyNegotiationFrame(type: String, data: Map<String, IMJson>) {
    when (type) {
        IMFrameType.ROOM_OFFER -> applyRemoteSdp(data.text("pc"), "offer", data.text("sdp"))
        IMFrameType.ROOM_ANSWER -> applyRemoteSdp(data.text("pc"), "answer", data.text("sdp"))
        IMFrameType.ROOM_ICE_CANDIDATE -> applyRemoteCandidate(
            data.text("pc"),
            data.text("candidate"),
            data.text("sdp_mid"),
            ((data["sdp_mline_index"] as? IMJson.Num)?.value ?: 0L).toInt(),
        )
    }
}

/** 取一个字符串字段；类型不对或缺字段都给空串（帧级解码已经补过默认值，这里只是兜底）。 */
internal fun Map<String, IMJson>.text(key: String) = (this[key] as? IMJson.Str)?.value ?: ""
