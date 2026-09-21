package com.imrtc.demo

import android.app.Activity
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.Switch
import com.imrtc.engine.IMCallEngineVersion
import com.imrtc.engine.media.IMVideoProfile

/**
 * 设置（草图 §02-D）。
 *
 * 草图把这一屏定义成「**Kit 配置项清单**」，三个开关**都是真的**：
 * 拨完立刻生效、不用重登（[com.imrtc.uikit.IMCallKitConfig] 是引用类型）。
 *
 * 画质档位是**宿主策略，不是服务端下发的**（见 [IMVideoProfile]）：真实宿主从自己的
 * 配置接口拿这个值，「后台可控」在产品上就是这个意思。放在设置页里是为了让这条边界看得见。
 */
internal class SettingsScreen(private val activity: Activity) : DemoScreen {

    private val profileRows = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val languageRows = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

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
                                "来电先出横幅",
                                "关掉则来电直接全屏",
                                DemoSession.bannerFirst,
                            ) { on -> DemoSession.bannerFirst = on },
                            switchRow(
                                "悬浮窗",
                                "允许把通话收成悬浮球（通话页左上角 ⌄）",
                                DemoSession.floatingWindow,
                            ) { on -> DemoSession.floatingWindow = on },
                            switchRow(
                                "静音来电铃声",
                                "打开后来电铃声与回铃音都不响，通话本身不受影响",
                                DemoSession.ringtoneMuted,
                            ) { on -> DemoSession.ringtoneMuted = on },
                            switchRow(
                                "硬件 H.264 编码",
                                "关掉退回 VP8 软编。换了要重登才生效",
                                DemoSession.preferHardwareH264,
                            ) { on -> DemoSession.preferHardwareH264 = on },
                            switchRow(
                                "详细日志",
                                "debug 级别，含主讲人 / 网络质量那些周期事件",
                                DemoSession.verboseLog,
                            ) { on -> DemoSession.verboseLog = on },
                            DemoUI.note(
                                activity,
                                "横幅与悬浮球都是应用内浮层，不申请 SYSTEM_ALERT_WINDOW" +
                                    "（那是敏感权限，会影响宿主上架）。" +
                                    "代价：离开本 App 就看不见了，通话本身不受影响。",
                            ),
                        ),
                    ),
                    DemoUI.card(
                        activity, "语言 / Language",
                        listOf(
                            languageRows,
                            DemoUI.note(activity, "切换通话界面的语言，立即生效；已经显示的提示不回译。"),
                        ),
                    ),
                    DemoUI.card(
                        activity, "采集画质（宿主策略，换了要重登）",
                        listOf(profileRows),
                    ),
                    DemoUI.card(
                        activity, "关于",
                        listOf(
                            detailRow("SDK", "im-rtc-android ${IMCallEngineVersion.VERSION}"),
                            detailRow("libwebrtc", "M150（io.github.webrtc-sdk:android:150.7871.01）"),
                            detailRow("设备 ID", DemoSession.deviceId),
                        ),
                    ),
                ),
            ),
        )
    }

    override fun refresh() {
        languageRows.removeAllViews()
        LANGUAGES.forEachIndexed { index, (value, name) ->
            if (index > 0) languageRows.addView(DemoUI.separator(activity))
            languageRows.addView(languageRow(value, name))
        }
        profileRows.removeAllViews()
        IMVideoProfile.PRESETS.forEachIndexed { index, profile ->
            if (index > 0) profileRows.addView(DemoUI.separator(activity))
            profileRows.addView(profileRow(profile))
        }
    }

    private fun languageRow(value: String, name: String): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val pad = DemoUI.dp(activity, 10)
        setPadding(0, pad, 0, pad)
        addView(DemoUI.label(activity, name, 16f, DemoUI.LABEL), LinearLayout.LayoutParams(0, DemoUI.WRAP, 1f))
        addView(DemoUI.label(activity, if (DemoSession.language == value) "✓" else "", 17f, DemoUI.TINT))
        setOnClickListener {
            DemoSession.language = value
            refresh()
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

    private companion object {
        /** 语言各用自己的名字显示，任何语言的界面里都认得出。 */
        val LANGUAGES = listOf(LANGUAGE_AUTO to "跟随系统 / Auto", "zh-CN" to "简体中文", "en" to "English")
    }
}
