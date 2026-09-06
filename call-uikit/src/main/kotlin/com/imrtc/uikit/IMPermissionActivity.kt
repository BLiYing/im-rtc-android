package com.imrtc.uikit

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings

/**
 * 弹系统权限框的那个透明 Activity（交互稿 §02 / §08 差异 6）。
 *
 * 运行时权限只能从 Activity 请求，而 Kit 未必有自己的界面在前台（拨出前就要问）。
 * 所以起一个透明的、不入最近任务的 Activity 专门干这件事：
 * 说明卡（首次）→ 系统框 → 拒绝一次再劝一次 → 永久拒绝就「去设置」。结果经 [pending] 回给 [IMPermissionGate]。
 *
 * 一次只问一个设备：麦克风被拒了整通话都不成立，没必要再问摄像头。
 */
internal class IMPermissionActivity : Activity() {

    private lateinit var device: IMPermissionGate.Device
    private var retried = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        device = IMPermissionGate.Device.valueOf(intent.getStringExtra(EXTRA_DEVICE) ?: "MICROPHONE")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || isGranted(this, device)) {
            finishWith(IMPermissionGate.Result.GRANTED)
            return
        }
        // 首次（系统框还没弹过）先出说明卡：系统框只有一次机会，说明卡是为它做铺垫。
        // Android 上「从没问过」与「拒绝过一次」都会让 shouldShowRequestPermissionRationale 为 false / true，
        // 这里用 SharedPreferences 记一下「问过没」。
        if (!askedBefore()) {
            showCard(IMPermissionGate.explanation(device), "好", "取消") { go -> if (go) request() else finishWith(IMPermissionGate.Result.CANCELLED) }
        } else {
            request()
        }
    }

    private fun request() {
        markAsked()
        requestPermissions(arrayOf(device.permission), REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            finishWith(IMPermissionGate.Result.GRANTED)
            return
        }
        // 拒绝一次还能再问（rationale 为 true）：劝一次；第二次拒绝或「不再询问」才进被拒分支。
        if (!retried && shouldShowRequestPermissionRationale(device.permission)) {
            retried = true
            showCard(IMPermissionGate.secondChance(device), "再试一次", "不了") { again ->
                if (again) requestPermissions(arrayOf(device.permission), REQUEST_CODE) else finishWith(IMPermissionGate.Result.DENIED)
            }
            return
        }
        showCard(IMPermissionGate.blocked(device), "知道了", if (device == IMPermissionGate.Device.MICROPHONE) "去设置" else "") { primary ->
            if (!primary) openSettings()
            finishWith(IMPermissionGate.Result.DENIED)
        }
    }

    private fun showCard(copy: IMPermissionGate.Copy, primary: String, secondary: String, answer: (Boolean) -> Unit) {
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(copy.title)
            .setMessage(copy.body)
            .setCancelable(false)
            .setPositiveButton(primary) { _, _ -> answer(true) }
        if (secondary.isNotEmpty()) dialog.setNegativeButton(secondary) { _, _ -> answer(false) }
        dialog.show()
    }

    private fun openSettings() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private fun finishWith(result: IMPermissionGate.Result) {
        val callback = pending
        pending = null
        finish()
        overridePendingTransition(0, 0)
        callback?.invoke(result)
    }

    private fun askedBefore(): Boolean = prefs().getBoolean(device.permission, false)

    private fun markAsked() = prefs().edit().putBoolean(device.permission, true).apply()

    private fun prefs() = getSharedPreferences("im-rtc-kit", MODE_PRIVATE)

    companion object {
        private const val EXTRA_DEVICE = "device"
        private const val REQUEST_CODE = 0x1A

        /** 正在等结果的那个回调。一次只问一个设备，所以一个槽就够。 */
        @Volatile
        private var pending: ((IMPermissionGate.Result) -> Unit)? = null

        fun isGranted(context: Context, device: IMPermissionGate.Device): Boolean =
            context.checkSelfPermission(device.permission) == PackageManager.PERMISSION_GRANTED

        /** 默认的 [IMPermissionGate.Asker]：已授权直接回；否则拉起透明 Activity 去问。 */
        fun asker(context: Context) = IMPermissionGate.Asker { device, callback ->
            if (isGranted(context, device)) {
                callback(IMPermissionGate.Result.GRANTED)
                return@Asker
            }
            pending?.invoke(IMPermissionGate.Result.CANCELLED) // 上一次没收尾的先作废
            pending = callback
            context.startActivity(
                Intent(context, IMPermissionActivity::class.java)
                    .putExtra(EXTRA_DEVICE, device.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
            )
        }
    }
}
