package com.imrtc.uikit

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import com.imrtc.engine.log.IMRTCLog

/**
 * 来电铃声 / 回铃音的播放层。**薄薄一层包 `MediaPlayer`**，该不该响、响哪种由
 * [IMRingRules.ringtoneFor] 判——这里只管「照给定的 [IMRingtoneKind] 起停」。
 *
 * # 三条不许碰的规矩（CONVENTIONS §8，都是踩过的坑）
 *
 * 1. **绝不碰 `AudioManager.mode`**：`call-engine-webrtc` 的 `IMAudioRouter.start()` 会把
 *    当前 mode 存进 `savedMode`、`stop()` 时还原；这里若抢先把 mode 改掉，通话结束后
 *    系统模式会被留在错误值上。铃声的语义完全靠 [AudioAttributes] 表达
 *    （`USAGE_NOTIFICATION_RINGTONE` + `CONTENT_TYPE_SONIFICATION`），不需要动 mode。
 * 2. **音频焦点用 `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`**，且**离开 INCOMING/OUTGOING 必须先
 *    abandon**：接听后 `IMAudioRouter.start()` 会抢 `AUDIOFOCUS_GAIN_TRANSIENT`，
 *    我们的焦点若还占着，通话音会被系统当成「还有一路在响」而压低（DUCK）。
 *    [stop] 因此**无条件先 `abandonFocus()` 再释放播放器**，[IMCallKit.update] 里
 *    离开 INCOMING/OUTGOING 那一刻会同步（不经 `main.post` 排队）调到这里，
 *    尽量抢在 `IMAudioRouter.start()` 前面。
 * 3. **焦点 API 分两代**，同 [IMAudioRouter]：
 *    - **API 26（O）+**：`AudioFocusRequest` + `AudioAttributes`。
 *    - **O 以下**：`requestAudioFocus(listener, streamType, durationHint)` 旧签名。
 *    **两条路径尚未各自上真机验证**（本轮是纯 JVM + 编译验收，minSdk 24 的旧路径只读代码审过）；
 *    下一次真机窗口按 CONVENTIONS §8 补验并在这里写清楚验过的型号/版本。
 *
 * 不做的事：不做音量渐变、不做振动（这一轮范围内明确排除）。
 */
internal class IMRingPlayer(context: Context) {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var player: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null

    /** 此刻在响哪种（给 [play] 做幂等判断，避免同一种铃反复重起）。 */
    private var current: IMRingtoneKind = IMRingtoneKind.NONE

    /**
     * 把状态切到 [kind]。**幂等**：连续两次喂同一个值只有第一次真正起停，
     * 这样调用方（[IMCallKit.update]）可以无脑地「每次都调」而不必自己记上一次喂的是什么。
     */
    fun play(kind: IMRingtoneKind) {
        if (kind == current) return
        stop()
        if (kind == IMRingtoneKind.NONE) return
        val uri = uriFor(kind)
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            mp.isLooping = true
            mp.setDataSource(appContext, uri)
            mp.setOnPreparedListener { it.start() }
            mp.setOnErrorListener { _, what, extra ->
                IMRTCLog.w("ring", "播放出错 what=$what extra=$extra，收掉")
                stop()
                true
            }
            requestFocus()
            mp.prepareAsync()
            player = mp
            current = kind
        } catch (e: Exception) {
            // setDataSource 在内置资源缺失、或宿主给的 Uri 打不开时会抛——**通话本身不能被这个拖垮**。
            IMRTCLog.w("ring", "起铃声失败（kind=$kind）：${e.javaClass.simpleName} ${e.message}")
            mp.release()
            abandonFocus()
            current = IMRingtoneKind.NONE
        }
    }

    /** 停下来、把播放器释放掉、**先 abandon 音频焦点**。重复调用无害。 */
    fun stop() {
        // 无论此刻是不是真的在响都先 abandon：见类注释第 2 条，这是「离开 INCOMING/OUTGOING
        // 时抢在 IMAudioRouter 前面」的关键一步，不能等播放器释放完再做。
        abandonFocus()
        val mp = player ?: run { current = IMRingtoneKind.NONE; return }
        player = null
        current = IMRingtoneKind.NONE
        try {
            mp.setOnPreparedListener(null)
            mp.setOnErrorListener(null)
            mp.reset()
        } catch (e: Exception) {
            IMRTCLog.w("ring", "停铃声时出错（忽略，继续释放）：${e.javaClass.simpleName} ${e.message}")
        } finally {
            mp.release()
        }
    }

    private fun uriFor(kind: IMRingtoneKind): Uri = when (kind) {
        IMRingtoneKind.INCOMING -> IMCallKit.config.incomingRingtone ?: rawUri(R.raw.im_ringtone)
        IMRingtoneKind.RINGBACK -> IMCallKit.config.ringbackTone ?: rawUri(R.raw.im_ringback)
        IMRingtoneKind.NONE -> throw IllegalArgumentException("NONE 不该走到这里")
    }

    private fun rawUri(resId: Int): Uri =
        Uri.parse("android.resource://${appContext.packageName}/$resId")

    private fun requestFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes)
                .build()
            focusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_RING,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            )
        }
    }

    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }
}
