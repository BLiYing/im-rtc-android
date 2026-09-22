package com.imrtc.uikit

import android.app.Dialog
import android.content.Context
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.graphics.drawable.Drawable
import android.widget.ScrollView
import android.widget.TextView

/**
 * 只读的成员列表（MEETING_ROOM_DESIGN §4.6），半屏。
 *
 * # M2 只做只读的这一半
 *
 * 搜索框与「正在说话」排序移出了 M2（§2.3）：25 人时列表一屏多一点就够翻，
 * 而成员列表在 M5（主持人操作）还要重做一次——那时才会长出「⋯」菜单里的
 * 静音 / 移出 / 设为主持人。现在放一个搜索框进去，等于为一个马上要重做的界面
 * 先付一次三端的工。
 *
 * 排序：**自己 → 进房顺序**。进房顺序就是 `state.members` 的顺序（`onUserEnter` 依次追加），
 * 不走画廊第一页那套发言人优先——列表里的人跟着说话跳位置，比画廊里更难找人。
 *
 * 用 `Dialog` 而不是新 Activity：通话页是全屏 Activity，弹一层半屏面板不该把它顶走
 * （顶走会让视频渲染器进后台，画面停一下）。
 */
internal object IMMemberListSheet {

    /** show 弹出成员列表。`resolver` 用来把 uid 换成显示名与头像。 */
    fun show(
        context: Context,
        state: IMCallViewState,
        resolver: IMProfileResolver?,
    ): Dialog = IMBottomSheet.show(context, buildContent(context, state, resolver))

    private fun buildContent(
        context: Context,
        state: IMCallViewState,
        resolver: IMProfileResolver?,
    ): FrameLayout {
        val root = FrameLayout(context)
        root.background = IMBottomSheet.background(context)
        root.setPadding(0, context.dp(12), 0, context.dp(16))

        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(
            TextView(context).apply {
                text = IMText.t("members.title", "n" to (state.members.size + 1))
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(IMKitTheme.primaryText)
                setPadding(context.dp(16), 0, context.dp(16), context.dp(8))
            },
        )

        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        // 自己恒在第一行，与画廊「自己占第一格」同一条规则。
        list.addView(row(context, IMText.t("self"), null, state.micOn, state.cameraOn))
        for (member in state.members.values) {
            list.addView(
                row(
                    context,
                    resolver?.displayName(member.uid) ?: member.uid,
                    resolver?.avatar(member.uid),
                    member.audio,
                    member.video,
                ),
            )
        }
        val scroll = ScrollView(context).apply { addView(list) }
        column.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                context.dp(MAX_LIST_HEIGHT_DP),
            ),
        )
        root.addView(column)
        return root
    }

    /** 一行：头像 + 名字 + 麦克风 / 摄像头状态。关着的那个变暗而不是消失——位置固定，一眼扫得出来。 */
    private fun row(
        context: Context,
        name: String,
        avatar: Drawable?,
        micOn: Boolean,
        cameraOn: Boolean,
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dp(16), context.dp(8), context.dp(16), context.dp(8))

        addView(
            avatarView(context, name, avatar),
            LinearLayout.LayoutParams(context.dp(28), context.dp(28)),
        )
        addView(
            TextView(context).apply {
                text = name
                textSize = 14f
                setTextColor(IMKitTheme.primaryText)
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = context.dp(10)
            },
        )
        addView(statusIcon(context, if (micOn) IMKitIcon.MIC else IMKitIcon.MIC_SLASH, micOn,
                           IMText.t(if (micOn) "members.micOn" else "members.micOff")))
        addView(statusIcon(context, if (cameraOn) IMKitIcon.VIDEO else IMKitIcon.VIDEO_SLASH, cameraOn,
                           IMText.t(if (cameraOn) "members.camOn" else "members.camOff")))
    }

    /**
     * 头像：有图就用图，没有就是**首字母 + 九色渐变底**（规范 §02，五端同一个哈希，见 [IMAvatar]）。
     *
     * 用 `ImageView` 套一层而不是把 Drawable 塞进 TextView 的背景：宿主给的头像可能是任意长宽比，
     * 背景会被拉伸，而这里要的是裁切填充。
     */
    private fun avatarView(context: Context, name: String, image: Drawable?): android.view.View {
        if (image != null) {
            return ImageView(context).apply {
                setImageDrawable(image)
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                background = IMKitTheme.circleDrawable(IMKitTheme.avatarBackground)
            }
        }
        return TextView(context).apply {
            text = IMAvatar.initial(name)
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(IMKitTheme.primaryText)
            setTypeface(null, android.graphics.Typeface.BOLD)
            background = IMKitTheme.avatarDrawable(name)
        }
    }

    private fun statusIcon(
        context: Context,
        icon: IMKitIcon,
        on: Boolean,
        label: String,
    ): ImageView = ImageView(context).apply {
        setImageResource(icon.resId)
        setColorFilter(IMKitTheme.secondaryText)
        alpha = if (on) 1f else 0.35f
        contentDescription = label
        layoutParams = LinearLayout.LayoutParams(context.dp(16), context.dp(16)).apply {
            leftMargin = context.dp(8)
        }
    }

    /** 列表最高占多少（半屏面板的高度上界）。25 人时一屏多一点，刚好够翻。 */
    private const val MAX_LIST_HEIGHT_DP = 380
}
