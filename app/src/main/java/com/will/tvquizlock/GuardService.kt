package com.will.tvquizlock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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

        fun launchQuizNow(context: Context) {
            QuestionRepo.setLockedInQuiz(context, true)
            val intent = Intent(context, QuizActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(intent)
        }
    }
}
