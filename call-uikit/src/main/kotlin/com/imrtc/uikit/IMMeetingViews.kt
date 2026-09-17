package com.imrtc.uikit

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/*
 会议房专有的两个小视图与两组手势（MEETING_ROOM_DESIGN §4.1 / §4.4）。

 单独一个文件是因为 `IMCallView` 贴着 600 行红线——与 `IMHiddenCountPill` 同一个理由。
 */

/**
 * 分页画廊的页码（`1 / 7`）：舞台**底部居中**，不可点——翻页靠左右滑（§4.1）。
 *
 * 它取代了 M1 那枚右下角的「还有 N 人未显示」胶囊（§4.5）：
 * 分页之后没有「看不见的人」这回事，只有「在别的页上」。
 */
internal class IMPagePill(context: Context) : TextView(context) {

    init {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(IMKitTheme.primaryText)
        val padH = dp(12)
        setPadding(padH, dp(3), padH, dp(3))
        background = GradientDrawable().apply {
            cornerRadius = dp(11).toFloat()
            setColor(0x99000000.toInt())
        }
        isClickable = false
        isFocusable = false
        visibility = GONE
    }

    /** 页码文案；空串就藏起来。 */
    fun show(label: String) {
        text = label
        visibility = if (label.isEmpty()) GONE else VISIBLE
    }

    companion object {
        fun layoutParams(pill: IMPagePill): FrameLayout.LayoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
        ).apply { setMargins(0, 0, 0, pill.dp(20)) }
    }
}

/**
 * 演讲者视图：**主画面 + 底部一条 4 格**（§4.4）。
 *
 * # M2 只做「钉住」这一半
 *
 * 没钉住时**不会**进这一屏——「自动跟着主讲人切主画面」移出了 M2（§2.3）。
 * 理由是主画面高频切 h 层、每切一次都要等关键帧，几个人抢话时主画面每隔几秒糊一下；
 * 而钉住是用户明确指定的，切换频率由他自己决定。
 *
 * # 层
 *
 * 主画面报 `h`，底部条报 `l`。被换下主画面的人留在底部条里，所以换回来时先有 l 层画面、
 * 再升到 h，不会从黑屏开始。
 */
internal class IMSpeakerStage(context: Context) : FrameLayout(context) {

    /** 点 📌 取消钉住。 */
    var onUnpin: (() -> Unit)? = null

    private val mainHost = FrameLayout(context)
    private val strip = LinearLayout(context)
    private val unpinButton = TextView(context)
    private var mainTile: View? = null

    init {
        mainHost.background = GradientDrawable().apply {
            cornerRadius = dp(IMKitTheme.TILE_RADIUS_DP).toFloat()
            setColor(IMKitTheme.tileBackground)
        }
        mainHost.clipToOutline = true
        addView(
            mainHost,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                setMargins(dp(12), dp(4), dp(12), dp(4) + STRIP_HEIGHT_DP.let { dp(it) } + dp(8))
            },
        )

        strip.orientation = LinearLayout.HORIZONTAL
        strip.gravity = Gravity.CENTER
        addView(
            strip,
            LayoutParams(LayoutParams.MATCH_PARENT, dp(STRIP_HEIGHT_DP), Gravity.BOTTOM).apply {
                setMargins(dp(12), 0, dp(12), dp(4))
            },
        )

        unpinButton.text = "📌 取消钉住"
        unpinButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        unpinButton.setTextColor(IMKitTheme.primaryText)
        unpinButton.setPadding(dp(10), dp(4), dp(10), dp(4))
        unpinButton.background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(0x99000000.toInt())
        }
        unpinButton.contentDescription = "取消钉住"
        unpinButton.setOnClickListener { onUnpin?.invoke() }
        addView(
            unpinButton,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START)
                .apply { setMargins(dp(20), dp(12), 0, 0) },
        )
    }

    /** setMain 换主画面。**同一个格子不重挂**：重挂会让渲染器的 Surface 重建，画面会黑一帧。 */
    fun setMain(tile: View) {
        if (mainTile === tile) return
        mainTile?.let { mainHost.removeView(it) }
        (tile.parent as? android.view.ViewGroup)?.removeView(tile)
        mainHost.addView(tile, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        mainTile = tile
        unpinButton.bringToFront()
    }

    /** setStrip 换底部条。顺序没变就不重挂——同上。 */
    fun setStrip(tiles: List<View>) {
        val same = strip.childCount == tiles.size &&
            tiles.indices.all { strip.getChildAt(it) === tiles[it] }
        if (same) return
        strip.removeAllViews()
        for (tile in tiles) {
            (tile.parent as? android.view.ViewGroup)?.removeView(tile)
            strip.addView(
                tile,
                LinearLayout.LayoutParams(dp(STRIP_HEIGHT_DP), dp(STRIP_HEIGHT_DP)).apply {
                    setMargins(dp(4), 0, dp(4), 0)
                },
            )
        }
    }

    /** detach 把格子交还出去（回画廊之前调），否则它们还挂在这里，画廊摆不上。 */
    fun detach() {
        mainTile?.let { mainHost.removeView(it) }
        mainTile = null
        strip.removeAllViews()
    }

    private companion object {
        const val STRIP_HEIGHT_DP = 84
    }
}

/**
 * installMeetingGestures 给画廊装**左右滑翻页**（§4.1）。
 *
 * 只认横向：竖向留给别的手势。阈值挡住「点一下」——单击是「显示 / 隐藏控制条」，
 * 不该被误判成翻页。
 */
@SuppressLint("ClickableViewAccessibility")
internal fun IMCallView.installMeetingGestures() {
    val detector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                if (!state.isMeeting || meeting.pinnedUid.isNotEmpty()) return false
                val dx = e2.x - (e1?.x ?: return false)
                if (abs(dx) < SWIPE_THRESHOLD_PX || abs(dx) <= abs(e2.y - e1.y)) return false
                // 左滑（dx < 0）= 看下一页。
                val moved = meeting.turn(if (dx < 0) 1 else -1, state.members.size)
                if (moved) render(state)
                return moved
            }
        },
    )
    grid.setOnTouchListener { _, event -> detector.onTouchEvent(event) }
}

/**
 * attachPinGesture 给格子装「双击钉住」（§4.4）。
 *
 * **双击才算**，不是单击：单击留给「显示 / 隐藏控制条」那一套手势，
 * 而钉住是一个明确的、不该被误触发的动作。每个格子只装一次——
 * 格子按 uid 复用，重复装会让一次双击触发好几回。
 */
@SuppressLint("ClickableViewAccessibility")
internal fun IMCallView.attachPinGesture(tile: IMVideoTile, uid: String) {
    if (tile.pinGestureUid == uid) return
    tile.pinGestureUid = uid
    val detector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!state.isMeeting) return false
                meeting.togglePin(uid)
                render(state)
                return true
            }
        },
    )
    tile.setOnTouchListener { _, event -> detector.onTouchEvent(event) }
}

/** 翻页要滑多远（像素）。太小会把「点一下」误判成翻页。 */
private const val SWIPE_THRESHOLD_PX = 48
