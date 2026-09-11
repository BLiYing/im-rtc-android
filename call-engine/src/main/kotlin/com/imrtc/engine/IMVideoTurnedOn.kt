package com.imrtc.engine

import com.imrtc.engine.protocol.IMJson
import com.imrtc.engine.statemachine.IMEmittedEvent

/**
 * 这一步里「谁的画面刚变成可用」：每条 `onUserVideoAvailable(uid, true)` 的 uid。
 *
 * Engine 拿它让媒体层等新画面真正上屏再报首帧（[com.imrtc.engine.media.IMMediaAdapter.awaitFirstVideoFrame]）。
 * **要排在 `dispatchAll` 之后调**：两边都 post 到主线程，界面先收到「有画面了」，才轮到「首帧上屏了」——
 * 反过来的话，揭示在前、挂起在后，格子会一直停在头像上直到兜底到点。
 */
internal fun videoTurnedOn(emit: List<IMEmittedEvent>): List<String> = emit
    .filter { it.callback == "onUserVideoAvailable" && (it.args["available"] as? IMJson.Bool)?.value == true }
    .mapNotNull { (it.args["uid"] as? IMJson.Str)?.value?.takeIf { uid -> uid.isNotEmpty() } }
