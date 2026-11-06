package com.will.tvquizlock

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class QuizActivity : AppCompatActivity() {

    private lateinit var tvSubjectBadge: TextView
    private lateinit var tvQuestion: TextView
    private lateinit var btnOpts: List<Button>
    private lateinit var layoutPenalty: LinearLayout
    private lateinit var tvPenaltyTimer: TextView
    private lateinit var tvExplanation: TextView

    private var currentQuestion: Question? = null
    private var penaltyTimer: CountDownTimer? = null
    private var isInPenalty = false

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_REMOTE_UNLOCK) {
                passAndFinish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_quiz)

        tvSubjectBadge = findViewById(R.id.tvSubjectBadge)
        tvQuestion = findViewById(R.id.tvQuestion)
        btnOpts = listOf(
            findViewById(R.id.btnOpt0),
            findViewById(R.id.btnOpt1),
            findViewById(R.id.btnOpt2),
            findViewById(R.id.btnOpt3)
        )
        layoutPenalty = findViewById(R.id.layoutPenalty)
        tvPenaltyTimer = findViewById(R.id.tvPenaltyTimer)
        tvExplanation = findViewById(R.id.tvExplanation)

        btnOpts.forEachIndexed { index, button ->
            button.setOnClickListener {
                onOptionSelected(index)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(unlockReceiver, IntentFilter(ACTION_REMOTE_UNLOCK), Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(unlockReceiver, IntentFilter(ACTION_REMOTE_UNLOCK))
        }

        loadNextQuestion()
        checkExistingPenalty()
    }

    override fun onResume() {
        super.onResume()
        isQuizVisible = true
    }

    override fun onPause() {
        super.onPause()
        isQuizVisible = false
    }

    override fun onDestroy() {
        penaltyTimer?.cancel()
        try {
            unregisterReceiver(unlockReceiver)
        } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun checkExistingPenalty() {
        val remainMs = QuestionRepo.getPenaltyEndTime(this) - System.currentTimeMillis()
        if (remainMs > 1000L) {
            startPenaltyLockdown(remainMs, currentQuestion?.explanation ?: "请认真复习本题知识点。")
        }
    }

    private fun loadNextQuestion() {
        isInPenalty = false
        layoutPenalty.visibility = View.GONE
        val q = QuestionRepo.getRandomQuestion(this, currentQuestion?.id ?: -1)
        currentQuestion = q

        tvSubjectBadge.text = "【${q.subject}】小学二年级闯关"
        tvQuestion.text = q.question

        val labels = listOf("A", "B", "C", "D")
        btnOpts.forEachIndexed { i, btn ->
            btn.isEnabled = true
            btn.isFocusable = true
            val optText = q.options.getOrNull(i) ?: ""
            btn.text = "${labels[i]}.  $optText"
        }

        btnOpts[0].post {
            btnOpts[0].requestFocus()
        }
    }

    private fun onOptionSelected(selectedIndex: Int) {
        if (isInPenalty) return
        val q = currentQuestion ?: return

        if (selectedIndex == q.answer) {
            Toast.makeText(this, "回答正确！太棒了，继续观看吧~", Toast.LENGTH_SHORT).show()
            passAndFinish()
        } else {
            // 答错：锁定 3 分钟 (180秒)，显示答案解析，倒计时结束后自动换下一题
            val penaltySec = QuestionRepo.getPenaltySeconds(this)
            val durationMs = penaltySec * 1000L
            QuestionRepo.setPenaltyEndTime(this, System.currentTimeMillis() + durationMs)
            val labels = listOf("A", "B", "C", "D")
            val rightLabel = labels.getOrNull(q.answer) ?: ""
            val rightContent = q.options.getOrNull(q.answer) ?: ""
            val fullExplanation = "正确答案：$rightLabel ($rightContent)\\n解析：${q.explanation}"
            startPenaltyLockdown(durationMs, fullExplanation)
        }
    }

    private fun startPenaltyLockdown(durationMs: Long, explanationText: String) {
        isInPenalty = true
        btnOpts.forEach {
            it.isEnabled = false
            it.isFocusable = false
        }
        layoutPenalty.visibility = View.VISIBLE
        tvExplanation.text = explanationText

        penaltyTimer?.cancel()
        penaltyTimer = object : CountDownTimer(durationMs, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val secLeft = (millisUntilFinished / 1000L) + 1
                val minPart = secLeft / 60
                val secPart = secLeft % 60
                tvPenaltyTimer.text = String.format("回答错误！锁定倒计时：%02d分%02d秒（倒计时结束后换下一题）", minPart, secPart)
            }

            override fun onFinish() {
                QuestionRepo.setPenaltyEndTime(this@QuizActivity, 0L)
                Toast.makeText(this@QuizActivity, "锁定结束，请作答下一道题！", Toast.LENGTH_SHORT).show()
                loadNextQuestion()
            }
        }.start()
    }

    private fun passAndFinish() {
        penaltyTimer?.cancel()
        QuestionRepo.setPenaltyEndTime(this, 0L)
        QuestionRepo.setLockedInQuiz(this, false)
        // 通知后台服务重新开始新的一轮 10 分钟计时
        val resetIntent = Intent(this, GuardService::class.java).apply {
            action = GuardService.ACTION_RESET_TIMER
        }
        startService(resetIntent)
        finish()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            showParentPasswordDialog()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun showParentPasswordDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "请输入家长密码（默认4444）"
        }
        AlertDialog.Builder(this)
            .setTitle("家长密码验证")
            .setMessage("输入密码可跳过本题或直接关闭学习守护：")
            .setView(input)
            .setPositiveButton("跳过本题继续看") { _, _ ->
                if (input.text.toString() == QuestionRepo.getPassword(this)) {
                    passAndFinish()
                } else {
                    Toast.makeText(this, "密码错误！", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("彻底关闭守护并退出") { _, _ ->
                if (input.text.toString() == QuestionRepo.getPassword(this)) {
                    QuestionRepo.setGuardActive(this, false)
                    QuestionRepo.setLockedInQuiz(this, false)
                    QuestionRepo.setPenaltyEndTime(this, 0L)
                    stopService(Intent(this, GuardService::class.java))
                    finish()
                } else {
                    Toast.makeText(this, "密码错误！", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    companion object {
        const val ACTION_REMOTE_UNLOCK = "com.will.tvquizlock.REMOTE_UNLOCK"
        var isQuizVisible = false
    }
}
