package com.imrtc.uikit

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.imrtc.engine.IMAudioRoute
import com.imrtc.engine.IMAudioRouteKind

/**
 * 音频路由面板：出现第三条路由时从底部升起的那张设备列表（设计稿 §04 v3.5，交互稿差异 7）。
 *
 * Android 没有对应的系统控件，只能自己画；iOS 那边也是自己画的（系统的 `AVRoutePickerView`
 * 绕开 libwebrtc 的会话管理，真机上把两个方向的声音都打没了，CLIENT_PARITY `[^audioroute]` v1.59），
 * 两端同一套语义：点一行只把 [IMAudioRoute] 交回去（→ `IMCallKit.selectAudioRoute` → Engine `setAudioRoute`）。
 *
 * 版式照设计稿：上圆角 20、行高 56、左右各留 20、左图标 24 + 间距 14 + 名字 15/regular、
 * 当前项右侧一枚 20 的勾（accent 色）、上方压一层黑 45% 的遮罩。
 * **点任意一项立即切换并收起；点面板外 = 取消。面板里没有「取消」按钮。**
 * 用 `Dialog` 而不是新 Activity：通话页是全屏 Activity，弹一层半屏面板不该把它顶走（同 [IMMemberListSheet]）。
 */
internal object IMAudioRoutePanel {

    /** show 弹出面板。`onPick` 在主线程回调；面板自己收起。 */
    fun show(context: Context, state: IMCallViewState, onPick: (IMAudioRoute) -> Unit): Dialog {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(buildContent(context, state) { route -> onPick(route); dialog.dismiss() })
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.apply {
            setBackgroundDrawable(GradientDrawable().apply { setColor(Color.TRANSPARENT) })
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.45f)
        }
        dialog.show()
        return dialog
    }

    private fun buildContent(
        context: Context,
        state: IMCallViewState,
        onPick: (IMAudioRoute) -> Unit,
    ): LinearLayout {
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.background = GradientDrawable().apply {
            cornerRadii = FloatArray(8) { index -> if (index < 4) context.dp(20).toFloat() else 0f }
            // 主题里没有设计稿那个 surface，用同语义的 banner（深色卡片底），与成员列表一致。
            setColor(IMKitTheme.bannerBackground)
        }
        // 上下留白 16（设计稿：高度自适应 = 行数 × 56 + 32 + 安全区）。
        column.setPadding(0, context.dp(16), 0, context.dp(16))
        for (route in state.audioRoutes) {
            column.addView(row(context, route, isCurrent = route == state.currentAudioRoute, onPick))
        }
        return column
    }

    /** 一行：左图标 24 + 间距 14 + 设备名 15/regular，当前项右侧一枚 20 的勾。 */
    private fun row(
        context: Context,
        route: IMAudioRoute,
        isCurrent: Boolean,
        onPick: (IMAudioRoute) -> Unit,
    ): LinearLayout {
        val name = routeDisplayName(route)
        val line = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(20), 0, context.dp(20), 0)
            isClickable = true
            isFocusable = true
            background = RippleDrawable(ColorStateList.valueOf(IMKitTheme.controlOff), null, ColorDrawable(Color.WHITE))
            contentDescription = name
            isSelected = isCurrent
            setOnClickListener { onPick(route) }
        }
        line.addView(
            ImageView(context).apply {
                setImageResource(routeIcon(route.kind).resId)
                setColorFilter(IMKitTheme.primaryText)
            },
            LinearLayout.LayoutParams(context.dp(24), context.dp(24)),
        )
        line.addView(
            TextView(context).apply {
                text = name
                textSize = 15f
                setTextColor(IMKitTheme.primaryText)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = context.dp(14) },
        )
        line.addView(
            ImageView(context).apply {
                setImageResource(IMKitIcon.CHECK.resId)
                // 设计稿的 accent 在本 Kit 对应接听键那支绿。
                setColorFilter(IMKitTheme.answer)
                visibility = if (isCurrent) android.view.View.VISIBLE else android.view.View.INVISIBLE
            },
            LinearLayout.LayoutParams(context.dp(20), context.dp(20)).apply { marginStart = context.dp(12) },
        )
        return line.also {
            it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(56))
        }
    }
}

/** 路由的字形（设计稿 §05 图标库；稿里没画的三枚按同一风格补在 `res/drawable`）。 */
internal fun routeIcon(kind: IMAudioRouteKind): IMKitIcon = when (kind) {
    IMAudioRouteKind.EARPIECE -> IMKitIcon.EARPIECE
    IMAudioRouteKind.SPEAKER -> IMKitIcon.SPEAKER
    IMAudioRouteKind.WIRED_HEADSET -> IMKitIcon.HEADPHONES
    IMAudioRouteKind.BLUETOOTH -> IMKitIcon.BLUETOOTH
}
