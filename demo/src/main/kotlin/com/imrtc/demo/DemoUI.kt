package com.imrtc.demo

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Demo 几屏共用的小控件。**普通页面走系统浅色风格**（草图 §01），不套 Kit 的深色主题——
 * Kit 那身深色是通话页专用的，把它铺到设置页上只会让人以为通话没结束。
 *
 * 用代码搭而不是 XML：与 `call-uikit` 同理，少一批 layout 资源就少一批与宿主重名的风险；
 * 而且 Demo 的重点是「宿主怎么用回调」，不是「怎么写 XML」。
 *
 * 配色对齐 iOS 的 system grouped 那一套，四端 Demo 看起来是同一个东西。
 */
internal object DemoUI {

    // ── 配色（对齐 iOS systemGroupedBackground 那一组）─────────────────
    const val BACKGROUND = 0xFFF2F2F7.toInt()
    const val CARD = 0xFFFFFFFF.toInt()
    const val LABEL = 0xFF000000.toInt()
    const val SECONDARY = 0xFF8E8E93.toInt()
    const val TINT = 0xFF007AFF.toInt()
    const val RED = 0xFFFF3B30.toInt()
    const val GREEN = 0xFF34C759.toInt()
    const val SEPARATOR = 0xFFE5E5EA.toInt()
    const val FILL = 0xFFE9E9EB.toInt()

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    fun field(context: Context, hint: String, text: String): EditText = EditText(context).apply {
        this.hint = hint
        setText(text)
        textSize = 15f
        setTextColor(LABEL)
        setHintTextColor(SECONDARY)
        // 用户名、房间号、IP 一律不要首字母大写与自动纠错：**它们不是英文单词**，
        // 输入法「帮」你改一下，登录就失败在一个看不出来的地方。
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        background = rounded(0xFFF2F2F7.toInt(), dp(context, 8))
        setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10))
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(context, 40))
    }

    fun button(context: Context, title: String, onClick: () -> Unit): Button =
        Button(context).apply {
            text = title
            isAllCaps = false
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(TINT)
            background = rounded(FILL, dp(context, 8))
            stateListAnimator = null
            setPadding(0, 0, 0, 0)
            minHeight = dp(context, 40)
            minimumHeight = dp(context, 40)
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(context, 40))
            setOnClickListener { onClick() }
        }

    fun note(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 12f
        setTextColor(SECONDARY)
    }

    fun label(context: Context, text: String, size: Float, color: Int): TextView =
        TextView(context).apply {
            this.text = text
            textSize = size
            setTextColor(color)
        }

    /** 一行并排、等宽。 */
    fun row(context: Context, views: List<View>): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEachIndexed { index, view ->
            val params = LinearLayout.LayoutParams(0, WRAP, 1f)
            if (index > 0) params.leftMargin = dp(context, 8)
            addView(view, params)
        }
    }

    /** card 是一个带标题的白底分组，对齐 iOS 的 inset grouped。 */
    fun card(context: Context, title: String, content: List<View>): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(CARD, dp(context, 12))
            val pad = dp(context, 14)
            setPadding(pad, pad, pad, pad)
            addView(
                label(context, title, 13f, SECONDARY).apply { typeface = Typeface.DEFAULT_BOLD },
            )
            content.forEach { view ->
                val params = (view.layoutParams as? LinearLayout.LayoutParams)
                    ?: LinearLayout.LayoutParams(MATCH, WRAP)
                params.topMargin = dp(context, 10)
                addView(view, params)
            }
        }

    /** 竖排容器，卡片之间留 16dp。 */
    fun stack(context: Context, children: List<View>): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        children.forEachIndexed { index, view ->
            val params = (view.layoutParams as? LinearLayout.LayoutParams)
                ?: LinearLayout.LayoutParams(MATCH, WRAP)
            if (index > 0) params.topMargin = dp(context, 16)
            addView(view, params)
        }
    }

    /**
     * 把一个竖排 stack 放进可滚动的页面里。
     *
     * **必须能滚**：小屏 + 键盘弹起之后，底部的按钮会被顶到屏幕外——
     * 那种「按钮不见了」最容易被当成布局坏了。
     */
    fun scroll(context: Context, content: View): ScrollView = ScrollView(context).apply {
        val pad = dp(context, 16)
        setPadding(pad, pad, pad, pad)
        clipToPadding = false
        // **不要 fillViewport**：只有一张卡片时它会被拉到整屏高，空记录页看起来像布局坏了。
        isFillViewport = false
        addView(content, ViewGroup.LayoutParams(MATCH, WRAP))
    }

    /** 顶部标题栏：居中标题 + 右侧一个可选动作（记录页的 🗑 走这里）。 */
    fun titleBar(context: Context, title: String, action: Pair<String, () -> Unit>?): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(CARD)
            setPadding(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 12))
            addView(
                label(context, title, 17f, LABEL).apply {
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                },
                LinearLayout.LayoutParams(0, WRAP, 1f),
            )
            if (action != null) {
                addView(
                    label(context, action.first, 17f, TINT).apply {
                        setOnClickListener { action.second() }
                    },
                )
            }
        }

    fun rounded(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius.toFloat()
        setColor(color)
    }

    fun circle(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    fun separator(context: Context): View = View(context).apply {
        setBackgroundColor(SEPARATOR)
        layoutParams = LinearLayout.LayoutParams(MATCH, 1)
    }

    const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
    const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
}

/** 三个 tab 共同的形状：一屏 = 一个 View + 一次刷新 + 标题栏上的那点东西。 */
internal interface DemoScreen {
    val title: String

    /** 标题栏右侧的动作（文案 → 点了做什么）。只有记录页用得上（🗑 清空）。 */
    val titleAction: Pair<String, () -> Unit>?

    val view: View

    /** 状态变了重画一次。**每一屏都要能重复调**——三个 tab 共用一份状态。 */
    fun refresh()
}
