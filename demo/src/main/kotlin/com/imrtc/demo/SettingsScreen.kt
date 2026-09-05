package com.imrtc.demo

import android.app.Activity
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.Switch
import com.imrtc.engine.media.IMVideoProfile

/**
 * 设置（草图 §02-D）。
 *
 * 草图把这一屏定义成「**Kit 配置项清单**」。Android 这边目前只有一项是真的
 * （详细日志）——悬浮窗、来电横幅那两项 Kit 还没做，**所以这里不摆开关**：
 * 摆一个拨了不生效的开关，比没有这一项更糟。等 Kit 补上再加。
 *
 * 画质档位是**宿主策略，不是服务端下发的**（见 [IMVideoProfile]）：真实宿主从自己的
 * 配置接口拿这个值，「后台可控」在产品上就是这个意思。放在设置页里是为了让这条边界看得见。
 */
internal class SettingsScreen(private val activity: Activity) : DemoScreen {

    private val profileRows = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

    override val title = "设置"
    override val titleAction: Pair<String, () -> Unit>? = null
    override val view: View

    init {
        view = DemoUI.scroll(
            activity,
            DemoUI.stack(
                activity,
                listOf(
                    DemoUI.card(
                        activity, "Kit 可配项",
                        listOf(
                            switchRow(
                                "详细日志",
                                "debug 级别，含主讲人 / 网络质量那些周期事件",
                                DemoSession.verboseLog,
                            ) { on -> DemoSession.verboseLog = on },
                            DemoUI.note(
                                activity,
                                "悬浮窗、来电横幅两项 Android Kit 还没实现，所以这里不摆开关——" +
                                    "拨了不生效的开关比没有更糟。",
                            ),
                        ),
                    ),
                    DemoUI.card(
                        activity, "采集画质（宿主策略，换了要重登）",
                        listOf(profileRows),
                    ),
                    DemoUI.card(
                        activity, "关于",
                        listOf(
                            detailRow("SDK", "im-rtc-android 0.1"),
                            detailRow("libwebrtc", "M150（io.github.webrtc-sdk:android:150.7871.01）"),
                            detailRow("设备 ID", DemoSession.deviceId),
                        ),
                    ),
                ),
            ),
        )
    }

    override fun refresh() {
        profileRows.removeAllViews()
        IMVideoProfile.PRESETS.forEachIndexed { index, profile ->
            if (index > 0) profileRows.addView(DemoUI.separator(activity))
            profileRows.addView(profileRow(profile))
        }
    }

    private fun profileRow(profile: IMVideoProfile): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val pad = DemoUI.dp(activity, 10)
        setPadding(0, pad, 0, pad)
        addView(
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(DemoUI.label(activity, profile.name, 16f, DemoUI.LABEL))
                addView(
                    DemoUI.label(
                        activity,
                        "${profile.width}×${profile.height} · ${profile.frameRate}fps · " +
                            "${profile.maxBitrateBps / 1000} kbps",
                        13f,
                        DemoUI.SECONDARY,
                    ),
                )
            },
            LinearLayout.LayoutParams(0, DemoUI.WRAP, 1f),
        )
        val chosen = profile.name == DemoSession.videoProfile.name
        addView(DemoUI.label(activity, if (chosen) "✓" else "", 17f, DemoUI.TINT))
        setOnClickListener {
            DemoSession.videoProfile = profile
            refresh()
        }
    }

    private fun switchRow(
        title: String,
        detail: String,
        initial: Boolean,
        onToggle: (Boolean) -> Unit,
    ): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(DemoUI.label(activity, title, 16f, DemoUI.LABEL))
                addView(DemoUI.label(activity, detail, 13f, DemoUI.SECONDARY))
            },
            LinearLayout.LayoutParams(0, DemoUI.WRAP, 1f),
        )
        @Suppress("DEPRECATION")
        addView(
            Switch(activity).apply {
                isChecked = initial
                setOnCheckedChangeListener { _: CompoundButton, on: Boolean -> onToggle(on) }
            },
        )
    }

    private fun detailRow(name: String, value: String): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            DemoUI.label(activity, name, 15f, DemoUI.LABEL).apply { typeface = Typeface.DEFAULT_BOLD },
            LinearLayout.LayoutParams(DemoUI.dp(activity, 84), DemoUI.WRAP),
        )
        addView(
            DemoUI.label(activity, value, 13f, DemoUI.SECONDARY),
            LinearLayout.LayoutParams(0, DemoUI.WRAP, 1f),
        )
    }
}
