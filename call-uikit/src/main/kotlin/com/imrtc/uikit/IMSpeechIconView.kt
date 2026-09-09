package com.imrtc.uikit

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * 名牌气泡里那枚说话 / 静音的小信号。**九宫格与会议里才有，1v1 不用**（2026-09-09 拍板）。
 *
 * # 为什么不是绿描边了
 *
 * 原先「谁在说话」是整格 3dp 绿描边 + 整条绿底名牌：两处大面积色块同时变，
 * 视线被拽走；三个人轮流说话时整屏在闪。改成名字右边一枚 9×10 的图标之后，
 * 画面与名牌底色纹丝不动，**整个格子里唯一的绿就是这枚图标**。
 *
 * # 为什么是竖条不是麦克风
 *
 * 麦克风说的是「他有麦克风」，竖条说的是「他此刻正在出声」——而且能把音量编码进条高。
 * 微信 / Zoom / Teams 用的都是这一类，不需要教。
 *
 * # 三种状态，永远占位
 *
 * `QUIET` 什么都不画但**照样占 10dp 宽**（2026-09-09 拍板：留位）。
 * 不留位的话名字会随说话左右跳，比图标本身更晃眼。
 *
 * # 收声要拖一拍
 *
 * 服务端 300ms 一次全量快照（协议 §3.5），说话人名单里有没有你是逐次判定的。
 * 一句话里的换气会让你短暂掉出名单——**直接跟着灭就是闪烁**。
 * 所以停的时候拖 [HOLD_MS] 再灭，起的时候立刻亮。
 */
internal class IMSpeechIconView(context: Context) : View(context) {

    enum class Mode { QUIET, SPEAKING, MUTED }

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = IMKitTheme.speaking }
    private val rect = RectF()
    private val micSlash = context.getDrawable(IMKitIcon.MIC_SLASH.resId)?.mutate()?.apply {
        setTint(IMKitTheme.mutedBadge)
    }

    private var mode = Mode.QUIET
    /** 0~100，服务端给的音量。映射到峰值高度 [PEAK_MIN]~1.0。 */
    private var volume = 0
    /** 动画相位起点。用开机时钟而不是帧计数——View 不可见时不掉帧就不会跳。 */
    private var phaseStart = 0L
    /** 收声后拖到这个时刻才真的灭。见类注释。 */
    private var quietAt = 0L
    /**
     * 系统「移除动画」开关（辅助功能 / 开发者选项把动画时长缩放调成 0）。
     *
     * iOS 看 `UIAccessibility.isReduceMotionEnabled`、web 有
     * `@media (prefers-reduced-motion:reduce)`，**这一端原先什么都不看**：
     * 明明开着「移除动画」，九个格子还是各有三根条在按屏幕刷新率逐帧重画。
     * 那正是这个开关要挡掉的东西，顺带也是白烧的电。
     *
     * 挂上来的时候读一次就够：每帧去查 Settings 太贵，而通话中途改这个开关的没有。
     */
    private var reduceMotion = false

    /** 设置状态。`speaking` 与 `muted` 互斥——静音的人不可能在说话，静音优先。 */
    fun set(speaking: Boolean, muted: Boolean, volume: Int) {
        this.volume = volume.coerceIn(0, 100)
        val want = when {
            muted -> Mode.MUTED
            speaking -> Mode.SPEAKING
            else -> Mode.QUIET
        }
        if (want == Mode.SPEAKING) {
            quietAt = 0L
            if (mode != Mode.SPEAKING) {
                phaseStart = android.os.SystemClock.uptimeMillis()
                mode = Mode.SPEAKING
            }
            invalidate()
            return
        }
        // 从「说话」退出来的那一次要拖一拍，其余状态立刻切。
        if (mode == Mode.SPEAKING && want == Mode.QUIET) {
            if (quietAt == 0L) quietAt = android.os.SystemClock.uptimeMillis() + HOLD_MS
            invalidate()
            return
        }
        quietAt = 0L
        if (mode != want) {
            mode = want
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        reduceMotion = android.provider.Settings.Global.getFloat(
            context.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // 固定尺寸：留位是设计的一部分，不许被父容器压扁。
        setMeasuredDimension(dp(WIDTH_DP), dp(HEIGHT_DP))
    }

    override fun onDraw(canvas: Canvas) {
        if (mode == Mode.MUTED) {
            micSlash?.setBounds(0, 0, width, height)
            micSlash?.draw(canvas)
            return
        }
        if (mode != Mode.SPEAKING) return

        val now = android.os.SystemClock.uptimeMillis()
        if (quietAt != 0L && now >= quietAt) {
            mode = Mode.QUIET
            quietAt = 0L
            return
        }

        // 音量映射：安静时也别缩成一条线，留 PEAK_MIN 的底。
        val peak = PEAK_MIN + (1f - PEAK_MIN) * (volume / 100f)
        val barW = dp(BAR_W_DP).toFloat()
        val gap = (width - barW * BARS) / (BARS - 1)
        for (i in 0 until BARS) {
            val scale = if (reduceMotion) {
                // 静止但仍读得出「在说话」：中间高、两边矮，和 iOS 那条同一个形状。
                peak * if (i == 1) 1f else STILL_SIDE
            } else {
                val phase = (now - phaseStart) / PERIOD_MS.toFloat() + PHASES[i]
                // 三角波比正弦省一次三角函数，而且在这个尺寸下看不出区别。
                val t = kotlin.math.abs((phase % 1f) * 2f - 1f)
                (MIN_SCALE + (1f - MIN_SCALE) * t) * peak
            }
            val h = height * scale
            val left = i * (barW + gap)
            rect.set(left, (height - h) / 2f, left + barW, (height + h) / 2f)
            canvas.drawRoundRect(rect, barW / 2f, barW / 2f, barPaint)
        }
        if (!reduceMotion) {
            postInvalidateOnAnimation()
            return
        }
        /*
          不逐帧重画了，但**拖拍还得有人来收尾**：上面那句「到点就转 QUIET」只在
          onDraw 里跑，没有下一帧就永远等不到，图标会一直亮着。所以精确地约一次。
        */
        if (quietAt != 0L) postInvalidateDelayed(quietAt - now)
    }

    private fun dp(v: Float) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        const val WIDTH_DP = 9f
        const val HEIGHT_DP = 10f
        const val BAR_W_DP = 2f
        const val BARS = 3
        const val PERIOD_MS = 620L
        const val MIN_SCALE = 0.34f
        const val PEAK_MIN = 0.5f
        /** 「移除动画」下两边那两根条的相对高度，让静止图标仍有个起伏的轮廓。 */
        const val STILL_SIDE = 0.7f
        const val HOLD_MS = 400L
        /** 三根条的相位错开，不然是一起上下的一整块。 */
        val PHASES = floatArrayOf(0f, 0.45f, 0.22f)
    }
}
