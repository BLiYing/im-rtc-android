package com.imrtc.uikit

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager

/**
 * 通话页里从底部升起的半屏面板（成员列表 §4.6、音频路由面板 §04）共用的壳：
 * 上圆角 20、`bannerBackground` 底、贴底、宽满、点面板外取消。
 *
 * 用 `Dialog` 而不是新 Activity：通话页是全屏 Activity，弹一层半屏面板不该把它顶走
 * （顶走会让视频渲染器进后台，画面停一下）。**调用方要持有返回的 Dialog，在页面销毁 / 通话结束时 `dismiss()`**，
 * 否则 Activity 先没了会报 WindowLeaked（code review 09-22 抓到）。
 */
internal object IMBottomSheet {

    /** 面板底：上圆角 20、下方直角（贴屏幕底边）。 */
    fun background(context: Context): GradientDrawable = GradientDrawable().apply {
        cornerRadii = FloatArray(8) { index -> if (index < 4) context.dp(20).toFloat() else 0f }
        setColor(IMKitTheme.bannerBackground)
    }

    /** show 把 `content` 装进贴底的 Dialog 并弹出。`dim` 是面板上方遮罩的黑度，0 = 不压遮罩。 */
    fun show(context: Context, content: View, dim: Float = 0f): Dialog {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(content)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.apply {
            setBackgroundDrawable(GradientDrawable().apply { setColor(Color.TRANSPARENT) })
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            if (dim > 0f) {
                addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setDimAmount(dim)
            }
        }
        dialog.show()
        return dialog
    }
}
