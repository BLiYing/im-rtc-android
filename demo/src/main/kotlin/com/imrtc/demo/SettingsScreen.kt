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

    override val title = dt("demo.settings.title")
    override val titleAction: Pair<String, () -> Unit>? = null
    override val view: View

    init {
        view = DemoUI.scroll(
            activity,
            DemoUI.stack(
                activity,
                listOf(
                    DemoUI.card(
                        activity, dt("demo.settings.kitGroup"),
                        listOf(
                            switchRow(
                                dt("demo.settings.bannerFirst"),
                                dt("demo.settings.bannerFirstMobileNote"),
                                DemoSession.bannerFirst,
                            ) { on -> DemoSession.bannerFirst = on },
                            switchRow(
                                dt("demo.settings.floatWindow"),
                                dt("demo.settings.floatWindowNoteAndroid"),
                                DemoSession.floatingWindow,
                            ) { on -> DemoSession.floatingWindow = on },
                            switchRow(
                                dt("demo.settings.ringtoneMuted"),
                                dt("demo.settings.ringtoneMutedMobileNoteAndroid"),
                                DemoSession.ringtoneMuted,
                            ) { on -> DemoSession.ringtoneMuted = on },
                            switchRow(
                                dt("demo.settings.hwH264"),
                                dt("demo.settings.hwH264Note"),
                                DemoSession.preferHardwareH264,
                            ) { on -> DemoSession.preferHardwareH264 = on },
                            switchRow(
                                dt("demo.settings.verboseLog"),
                                dt("demo.settings.verboseLogMobileNote"),
                                DemoSession.verboseLog,
                            ) { on -> DemoSession.verboseLog = on },
                            DemoUI.note(
                                activity,
                                dt("demo.settings.overlayNote"),
                            ),
                        ),
                    ),
                    DemoUI.card(
                        activity, "语言 / Language",
                        listOf(
                            languageRows,
                            DemoUI.note(activity, dt("demo.settings.languageNote")),
                        ),
                    ),
                    DemoUI.card(
                        activity, dt("demo.settings.profileMobile"),
                        listOf(profileRows),
                    ),
                    DemoUI.card(
                        activity, dt("demo.settings.about"),
                        listOf(
                            detailRow("SDK", "im-rtc-android ${IMCallEngineVersion.VERSION}"),
                            detailRow("libwebrtc", "M150（io.github.webrtc-sdk:android:150.7871.01）"),
                            detailRow(dt("demo.settings.deviceId"), DemoSession.deviceId),
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
            // Demo 自己的页面是建一次画一次的，换语言直接重建；MainActivity 会停回设置页。
            activity.recreate()
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
