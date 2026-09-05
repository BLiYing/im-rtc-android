package com.imrtc.engine.webrtc

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
 *   还要在清单里声明对应的 `FOREGROUND_SERVICE_MICROPHONE` / `_CAMERA` 权限。
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
        startInForeground(withCamera)
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (withCamera) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        IMRTCLog.i("service", "前台服务已启动（camera=$withCamera）")
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
