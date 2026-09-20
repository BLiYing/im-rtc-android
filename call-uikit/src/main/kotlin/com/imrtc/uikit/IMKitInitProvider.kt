package com.imrtc.uikit

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * 进程一起来就把 [IMActivityTracker] 装上，**不等宿主调 `IMCallKit.start`**。
 *
 * 为什么：宿主是登录成功之后才 `start` 的，冷启动时首页早就 resume 过了，Tracker 没看到那一次，
 * `foreground()` 一直是 null，来电横幅没有地方可挂，只能退回全屏接听页（2026-09-20 真机复现：杀掉 App
 * 重开，停在首页，第一通来电直接是接听页）。`registerActivityLifecycleCallbacks` 不会补发已发生的事件，
 * 所以只能赶在任何 Activity 创建之前注册。
 *
 * ContentProvider 的 `onCreate` 早于 `Application.onCreate`，LeakCanary / Firebase / androidx.startup 都是这个做法。
 * 宿主不想要可以在清单里对它写 `tools:node="remove"`，那样退回「start 时才装」。
 * 它不提供任何数据，查询 / 写入一律空操作。
 */
class IMKitInitProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        (context?.applicationContext as? Application)?.let { IMActivityTracker.install(it) }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
