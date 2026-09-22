package com.imrtc.engine

import com.imrtc.engine.log.IMRTCLog
import com.imrtc.engine.protocol.IMJson
import com.imrtc.engine.statemachine.IMMachineInput

/** [IMCallEngine.setRemoteLayer] 在 engine 线程上真正做的事：给某人的每条视频轨道各发一条 `update_layer`。从门面抽出来只为体量。 */
internal object IMRemoteLayer {

    fun report(loop: IMFrameLoop, dispatcher: IMEventDispatcher, uid: String, layer: String) {
        var sent = 0
        for ((trackId, info) in loop.ctx.room.remoteTracks) {
            if (info.uid != uid || info.kind != "video") continue
            val result = IMCallResult(dispatcher, null, IMCallResult.UNIT)
            loop.input(
                IMMachineInput.Act("update_layer", mapOf("track_id" to IMJson.Str(trackId), "max_layer" to IMJson.Str(layer))),
                result,
            )
            result.seal()
            sent++
        }
        /*
         **这两行是这条通路唯一的外部可见性**：服务端不记录成功的 room 帧，客户端也不逐帧打日志。
         **找不到轨道不是错**：人先进来、轨道后到是常态，轨道到了 Kit 会重报
         （`IMCallKit.invalidateReportedLayer`）。所以那一支记 DEBUG 不记 WARN。
        */
        if (sent > 0) {
            IMRTCLog.i("engine", "层上界已报 uid=$uid layer=$layer tracks=$sent")
        } else {
            IMRTCLog.d("engine", "层上界暂不发 uid=$uid layer=$layer（他的视频轨道还没到）")
        }
    }
}
