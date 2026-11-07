package com.will.tvquizlock

import android.Manifest
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.text.format.Formatter
import android.view.KeyEvent
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {

    private companion object {
        const val REQUEST_POST_NOTIFICATIONS = 100
        const val REQUEST_FULL_SCREEN_INTENT = 101
    }

    private lateinit var tvStatus: TextView
    private lateinit var tvWebInfo: TextView
    private lateinit var btnToggleGuard: Button
    private lateinit var btnTestQuiz: Button
    private lateinit var btnOverlayPerm: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvWebInfo = findViewById(R.id.tvWebInfo)
        btnToggleGuard = findViewById(R.id.btnToggleGuard)
        btnTestQuiz = findViewById(R.id.btnTestQuiz)
        btnOverlayPerm = findViewById(R.id.btnOverlayPerm)

        btnToggleGuard.setOnClickListener {
            if (QuestionRepo.isGuardActive(this)) {
                // 关闭守护需要验证密码 5555
                promptPasswordToStopGuard()
            } else {
                startGuard()
            }
        }

        btnTestQuiz.setOnClickListener {
            if (!QuestionRepo.isGuardActive(this)) {
                startGuard()
            }
            GuardService.launchQuizNow(this)
        }

        btnOverlayPerm.setOnClickListener {
            requestOverlayPermission()
        }

        updateUI()
        btnToggleGuard.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    private fun startGuard() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先开启“显示在其他应用上层”悬浮窗权限，确保看视频时能正常弹题", Toast.LENGTH_LONG).show()
            requestOverlayPermission()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_POST_NOTIFICATIONS)
            return
        }
        GuardService.ensureAlertNotificationChannel(this)
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            Toast.makeText(this, "请在系统设置中允许本应用发送通知，然后重新开启守护", Toast.LENGTH_LONG).show()
            openAppNotificationSettings()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val alertChannel = getSystemService(NotificationManager::class.java)
                .getNotificationChannel(GuardService.ALERT_CHANNEL_ID)
            if (alertChannel != null && alertChannel.importance < NotificationManager.IMPORTANCE_HIGH) {
                Toast.makeText(this, "请将“锁屏答题提醒”通知设为高优先级，然后重新开启守护", Toast.LENGTH_LONG).show()
                try {
                    startActivity(
                        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                            putExtra(Settings.EXTRA_CHANNEL_ID, GuardService.ALERT_CHANNEL_ID)
                        }
                    )
                } catch (e: Exception) {
                    Toast.makeText(this, "请在本应用通知设置中开启“锁屏答题提醒”并设为高优先级", Toast.LENGTH_LONG).show()
                }
                return
            }
        }
        if (Build.VERSION.SDK_INT >= 34 &&
            !getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        ) {
            try {
                startActivityForResult(
                    Intent(
                        Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                        Uri.parse("package:$packageName")
                    ),
                    REQUEST_FULL_SCREEN_INTENT
                )
            } catch (e: Exception) {
                Toast.makeText(
                    this,
                    "请在系统设置中允许本应用发送全屏通知，否则电视播放时无法自动显示答题界面",
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }
        QuestionRepo.setGuardActive(this, true)
        QuestionRepo.setGuardTimerStartedAt(this, System.currentTimeMillis())
        val serviceIntent = Intent(this, GuardService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        updateUI()
        Toast.makeText(this, "学习守护已开启！每 ${QuestionRepo.getIntervalMinutes(this)} 分钟自动弹题", Toast.LENGTH_SHORT).show()
    }

    private fun openAppNotificationSettings() {
        try {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(
                    Settings.EXTRA_APP_PACKAGE,
                    packageName
                )
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "请在智慧屏设置中允许本应用的通知权限", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Use Activity Result APIs")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_POST_NOTIFICATIONS) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                startGuard()
            } else {
                Toast.makeText(
                    this,
                    "必须允许通知权限，定时提醒才能显示；请到系统设置中开启后再启动守护",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    @Deprecated("Use Activity Result APIs")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_FULL_SCREEN_INTENT) {
            if (Build.VERSION.SDK_INT >= 34 &&
                getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
            ) {
                startGuard()
            } else {
                Toast.makeText(
                    this,
                    "未允许全屏通知，定时答题界面可能无法覆盖电视播放画面",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun promptPasswordToStopGuard() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "请输入家长密码"
        }
        AlertDialog.Builder(this)
            .setTitle("关闭学习守护")
            .setMessage("请输入家长密码以退出守护状态：")
            .setView(input)
            .setPositiveButton("确定关闭") { _, _ ->
                if (input.text.toString() == QuestionRepo.getPassword(this)) {
                    QuestionRepo.setGuardActive(this, false)
                    QuestionRepo.setLockedInQuiz(this, false)
                    QuestionRepo.setPenaltyEndTime(this, 0L)
                    QuestionRepo.setGuardTimerStartedAt(this, 0L)
                    stopService(Intent(this, GuardService::class.java))
                    updateUI()
                    Toast.makeText(this, "学习守护已关闭", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "密码错误！", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                try {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                } catch (_: Exception) {
                    Toast.makeText(this, "请在智慧屏【设置 -> 应用 -> 权限管理】中允许本应用悬浮窗权限", Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(this, "悬浮窗权限已正常开启！", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateUI() {
        val active = QuestionRepo.isGuardActive(this)
        val ip = getLocalIpAddress()
        val totalQ = QuestionRepo.getAllQuestions(this).size
        if (active) {
            tvStatus.text = "当前状态：守护运行中（每 ${QuestionRepo.getIntervalMinutes(this)} 分钟弹题 | 当前题库 $totalQ 题）"
            btnToggleGuard.text = "关闭守护(需密码)"
        } else {
            tvStatus.text = "当前状态：未开启（当前题库 $totalQ 题）"
            btnToggleGuard.text = "开启学习守护"
        }
        tvWebInfo.text = "局域网题库与配置管理网址：http://$ip:8888 （开启守护后用电脑或手机浏览器打开）"
    }

    private fun getLocalIpAddress(): String {
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ipInt = wm.connectionInfo.ipAddress
            if (ipInt != 0) {
                return Formatter.formatIpAddress(ipInt)
            }
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: "192.168.7.x"
                    }
                }
            }
        } catch (_: Exception) {}
        return "智慧屏局域网IP"
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && QuestionRepo.isGuardActive(this)) {
            // 如果守护已开启，按返回键退到后台继续守护，而不是直接销毁
            moveTaskToBack(true)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
