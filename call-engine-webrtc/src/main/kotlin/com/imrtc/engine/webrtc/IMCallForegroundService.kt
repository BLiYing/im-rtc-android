package com.imrtc.engine.webrtc

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.imrtc.engine.log.IMRTCLog

/**
 * 通话中的前台服务。
 *
 * **不起它，进程随时会被系统回收**——症状是「切到后台一会儿就掉线」，而且用户不会觉得
 * 这是系统干的，只会觉得我们的通话不稳定（CONVENTIONS §8）。
 *
 * 三条平台规则都在这里：
 * - `foregroundServiceType` 取 `microphone`（视频再加 `camera`）。**Android 14 起**
 *   还要在清单里声明对应的 `FOREGROUND_SERVICE_MICROPHONE` / `_CAMERA` 权限，
 *   **且对应的运行时权限必须已授权**——所以类型按实际授权给（[IMForegroundTypes]）。
 * - **Android 13 起通知要 `POST_NOTIFICATIONS` 运行时权限**，没有它这条通知不显示，
 *   用户看不见「通话中」——服务照样在跑，但体验上像个后台幽灵。
 * - 用 `phoneCall` 类型的前提是接 Telecom / `ConnectionService`，那属后续期，MVP 不做。
 *
 * 宿主可以整个不用它（自己起前台服务），把 [start] / [stop] 换成自己的实现即可。
 */
class IMCallForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val withCamera = intent?.getBooleanExtra(EXTRA_WITH_CAMERA, false) ?: false
        /*
          **这里抛出去就是崩溃循环**（2026-09-11 真机，PKD130 / Android 15）：异常先于
          `return START_NOT_STICKY` 冒出去，进程崩掉，而那条没投递成功的 start 会被系统
          `restartService` 重投——每次重开 App 都再崩一次。所以必须接住，而且接住之后要 stopSelf。
        */
        try {
            startInForeground(withCamera)
        } catch (e: Exception) {
            IMRTCLog.w("service", "startForeground 失败，收掉服务：${e.javaClass.simpleName} ${e.message}")
            stopSelf()
        }
        // 被系统杀掉后不自动重建：通话早就结束了，重建一个空壳服务只会让用户看到幽灵通知。
        return START_NOT_STICKY
    }

    private fun startInForeground(withCamera: Boolean) {
        ensureChannel()
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("通话中")
            .setContentText("点按返回通话")
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setOngoing(true)
            .build()

        val types = IMForegroundTypes.granted(this, withCamera)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var type = 0
            if (types.microphone) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (types.camera) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (type == 0) {
                // start() 已经挡过；走到这里是「刚放行、权限就被收回」的那几毫秒。
                IMRTCLog.w("service", "麦克风与摄像头权限都没有，前台服务不起")
                stopSelf()
                return
            }
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        IMRTCLog.i("service", "前台服务已启动（mic=${types.microphone} camera=${types.camera}）")
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "通话", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
        )
    }

    companion object {
        private const val CHANNEL_ID = "im_rtc_call"
        private const val NOTIFICATION_ID = 4231
        private const val EXTRA_WITH_CAMERA = "with_camera"

        @JvmStatic
        fun start(context: Context, withCamera: Boolean) {
            /*
              **一个类型都给不出来就别调 startForegroundService。** 调了之后服务必须在几秒内
              startForeground，否则系统以「did not then call startForeground」把进程带崩——
              不能指望 onStartCommand 里再补救。
              典型场景：用户在系统设置里收回了麦克风，来电页上点开摄像头起预览。
            */
            if (IMForegroundTypes.granted(context, withCamera).isEmpty) {
                IMRTCLog.w("service", "麦克风与摄像头权限都没有，前台服务不起（camera=$withCamera）")
                return
            }
            val intent = Intent(context, IMCallForegroundService::class.java)
                .putExtra(EXTRA_WITH_CAMERA, withCamera)
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                // Android 12 起，后台状态下 startForegroundService 会抛
                // ForegroundServiceStartNotAllowedException。**不能让它把通话带崩**——
                // 没有前台服务只是更容易被回收，不是不能通话。
                IMRTCLog.w("service", "前台服务起不来：${e.javaClass.simpleName} ${e.message}")
            }
        }

        @JvmStatic
        fun stop(context: Context) {
            context.stopService(Intent(context, IMCallForegroundService::class.java))
        }
    }
}

/**
 * 前台服务该带哪几种类型——**按实际拿到的权限给，不按「想要」给**。
 *
 * Android 14 起（targetSdk ≥ 34）`microphone` 类型要求 `RECORD_AUDIO` 已授权、`camera` 类型要求
 * `CAMERA` 已授权，缺了 `startForeground` 直接抛 SecurityException。原先恒带 `microphone`：
 * 麦克风被收回后在来电页点开摄像头（`ensureCapture` 起服务）就崩，且崩溃循环。
 *
 * 纯函数摘出来单测；[granted] 只负责去读系统权限。
 */
internal data class IMForegroundTypes(val microphone: Boolean, val camera: Boolean) {
    val isEmpty: Boolean get() = !microphone && !camera

    companion object {
        fun of(withCamera: Boolean, micGranted: Boolean, cameraGranted: Boolean) =
            IMForegroundTypes(microphone = micGranted, camera = withCamera && cameraGranted)

        fun granted(context: Context, withCamera: Boolean) = of(
            withCamera = withCamera,
            micGranted = context.hasPermission(Manifest.permission.RECORD_AUDIO),
            cameraGranted = context.hasPermission(Manifest.permission.CAMERA),
        )
    }
}
