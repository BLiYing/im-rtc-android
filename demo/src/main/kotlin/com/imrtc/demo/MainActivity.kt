package com.imrtc.demo

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * Demo App：**拨号 / 通话记录 / 设置** 三屏，底部 tab 切换（草图 §02）。
 *
 * 它证明的是一件事：**只用公开回调表就能做出完整体验**。
 * 通话界面整套交给 `IMCallKit`（草图 §01 的用法 B），一行接管；
 * 通话记录完全由 `onCallEnd` 拼出来——**宿主只监听那一个回调也能完整记账**。
 *
 * 与 iOS Demo 同构：同样的四块拨号卡、同样的选人上限、同样的记录文案。
 * 两端并排放着对比时，差异应该只剩平台控件本身的样子。
 */
class MainActivity : Activity() {

    private lateinit var screens: List<DemoScreen>
    private lateinit var titleSlot: FrameLayout
    private lateinit var container: FrameLayout
    private lateinit var tabBar: LinearLayout

    private var index = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DemoSession.install(this)
        screens = listOf(DialerScreen(this), HistoryScreen(this), SettingsScreen(this))
        setContentView(buildRoot())
        DemoSession.onChange = { refresh() }
        showTab(0)
        // 这里**故意不申请任何权限**：麦克风与摄像头由 Kit 在三个闸口自己要
        // （发起 / 开摄像头 / 接听），而且它的结果有业务语义——麦克风被拒＝取消整通话，
        // 摄像头被拒＝降级语音继续，这判断宿主做不了。Demo 抢在前面要一次，
        // 只会烧掉「第一次」：用户在这里拒绝之后，真打电话时 Kit 的说明卡被跳过，
        // 直接落到「永久拒绝 → 去设置」。**这个 Demo 要证明的正是「宿主只管调 call()」。**
        // POST_NOTIFICATIONS 是唯一留给宿主的一条，见 README「宿主要自己申请的权限」。
        // 上次登录过就自动重登——**杀掉 app 再打开不该回到登录页**。
        DemoSession.autoLogin()
    }

    override fun onDestroy() {
        DemoSession.onChange = null
        super.onDestroy()
    }

    // ── 外壳 ──────────────────────────────────────────────────────────

    private fun buildRoot(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(DemoUI.BACKGROUND)
        fitsSystemWindows = true

        // 顶栏下压一条 1px 分隔线。
        titleSlot = FrameLayout(this@MainActivity)
        addView(titleSlot, LinearLayout.LayoutParams(DemoUI.MATCH, DemoUI.WRAP))
        addView(DemoUI.separator(this@MainActivity))

        container = FrameLayout(this@MainActivity)
        addView(container, LinearLayout.LayoutParams(DemoUI.MATCH, 0, 1f))

        // tab 栏同样压一条：**不能靠背景色差**，白底对白底根本看不出边界。
        addView(DemoUI.separator(this@MainActivity))

        tabBar = buildTabBar()
        addView(tabBar, LinearLayout.LayoutParams(DemoUI.MATCH, DemoUI.WRAP))
    }

    private fun buildTabBar(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setBackgroundColor(DemoUI.CARD)
        listOf("🔢" to "拨号", "🕘" to "记录", "⚙" to "设置")
            .forEachIndexed { position, (icon, name) ->
                addView(tabItem(icon, name, position), LinearLayout.LayoutParams(0, DemoUI.WRAP, 1f))
            }
    }

    /**
     * 一个 tab。三格由 `buildTabBar` 用 `weight = 1f` 等分，**这里只管把字摆在格子中间**。
     *
     * **父容器的 `gravity = Gravity.CENTER` 单独用是不够的**：竖排 LinearLayout 的
     * `generateDefaultLayoutParams()` 给的是 `MATCH_PARENT × WRAP_CONTENT`
     * （横排才是 `WRAP × WRAP`），所以不带 LayoutParams 添进来的两个 TextView
     * **本来就撑满整格**，父容器没有可居中的余量，字于是靠 TextView 自己的默认 gravity
     * 顶在左边。三个 tab 都偏，只是第一个贴着屏幕左缘、看起来最明显。
     * 修法与 `DemoUI.titleBar` 一致：**gravity 设在 TextView 自己身上**。
     */
    private fun tabItem(icon: String, name: String, position: Int): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, DemoUI.dp(this@MainActivity, 8), 0, DemoUI.dp(this@MainActivity, 10))
            addView(
                DemoUI.label(this@MainActivity, icon, 18f, DemoUI.SECONDARY)
                    .apply { gravity = Gravity.CENTER },
            )
            addView(
                DemoUI.label(this@MainActivity, name, 11f, DemoUI.SECONDARY)
                    .apply { gravity = Gravity.CENTER },
            )
            setOnClickListener { showTab(position) }
        }

    private fun showTab(position: Int) {
        index = position
        val screen = screens[position]

        titleSlot.removeAllViews()
        titleSlot.addView(DemoUI.titleBar(this, screen.title, screen.titleAction))

        container.removeAllViews()
        (screen.view.parent as? FrameLayout)?.removeView(screen.view)
        container.addView(
            screen.view,
            FrameLayout.LayoutParams(DemoUI.MATCH, FrameLayout.LayoutParams.MATCH_PARENT),
        )

        paintTabs()
        screen.refresh()
    }

    private fun paintTabs() {
        for (position in 0 until tabBar.childCount) {
            val item = tabBar.getChildAt(position) as? LinearLayout ?: continue
            val color = if (position == index) DemoUI.TINT else DemoUI.SECONDARY
            for (child in 0 until item.childCount) {
                (item.getChildAt(child) as? android.widget.TextView)?.setTextColor(color)
            }
        }
    }

    /** 状态变了就重画**当前这一屏**：另外两屏切过去时自己会 refresh。 */
    private fun refresh() {
        screens[index].refresh()
        // 标题栏上的动作绑的是当前屏，切屏时才会重建，这里不用动。
    }

}
