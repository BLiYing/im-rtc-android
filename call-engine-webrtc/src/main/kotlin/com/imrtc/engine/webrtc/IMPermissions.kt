package com.imrtc.engine.webrtc

import android.content.Context
import android.content.pm.PackageManager

/**
 * `checkSelfPermission(...) == PackageManager.PERMISSION_GRANTED` 这句在本模块出现了三次
 * （[IMWebRTCAdapter] 摄像头、[IMCallForegroundService] 麦克风与摄像头），收成一处。
 */
internal fun Context.hasPermission(permission: String): Boolean =
    checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
