package com.will.tvquizlock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

class GuardService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var webServer: WebServer? = null
    private var secondsElapsed = 0

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!QuestionRepo.isGuardActive(this@GuardService)) {
                stopSelf()
                return
            }

            if (QuestionRepo.isLockedInQuiz(this@GuardService)) {
                // 如果当前处于未答完题状态，防止小朋友按Home键逃逸，每2秒检测并拉回前台
                if (!QuizActivity.isQuizVisible) {
                    launchQuizNow(this@GuardService)
                }
            } else {
                secondsElapsed++
                val targetSeconds = QuestionRepo.getIntervalMinutes(this@GuardService) * 60
                if (secondsElapsed >= targetSeconds) {
                    secondsElapsed = 0
                    launchQuizNow(this@GuardService)
                }
            }

            handler.postDelayed(this, 2000L)
            secondsElapsed++ // 步进补偿（每次循环2秒）
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(1001, buildNotification())
        try {
            webServer = WebServer(applicationContext, 8888)
            webServer?.start()
        } catch (_: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RESET_TIMER) {
            secondsElapsed = 0
        }
        handler.removeCallbacksAndMessages(null)
        handler.post(tickRunnable)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try {
            webServer?.stop()
        } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val channelId = "tv_quiz_guard_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "智慧屏学习守护服务",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("智慧屏学习守护运行中")
            .setContentText("每 ${QuestionRepo.getIntervalMinutes(this)} 分钟弹题一次 | 局域网管理端口: 8888")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_RESET_TIMER = "com.will.tvquizlock.RESET_TIMER"
        private const val ALERT_CHANNEL_ID = "quiz_time_up_alert"
        private const val ALERT_NOTIFICATION_ID = 2001

        fun launchQuizNow(context: Context) {
            QuestionRepo.setLockedInQuiz(context, true)

            // 1. 创建高优先级通知渠道（必须为 HIGH/MAX 才能产生全屏唤醒）
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    ALERT_CHANNEL_ID,
                    "锁屏答题提醒",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "时间到期时全屏拉起做题界面"
                    setBypassDnd(true)
                }
                notificationManager.createNotificationChannel(channel)
            }

            // 2. 构建目标页面的全屏 Intent
            val intent = Intent(context, QuizActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }

            val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }

            val fullScreenPendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                pendingIntentFlags
            )

            // 3. 构建高优先级全屏通知
            val notification = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("答题时间已到")
                .setContentText("请完成题目以解锁电视")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setFullScreenIntent(fullScreenPendingIntent, true)
                .build()

            // 4. 发送通知唤起界面（双重保障：先尝试普通拉起，再触发全屏通知）
            try {
                context.startActivity(intent)
            } catch (_: Exception) {}

            notificationManager.notify(ALERT_NOTIFICATION_ID, notification)
        }
    }
}
