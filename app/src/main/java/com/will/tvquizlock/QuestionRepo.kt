package com.will.tvquizlock

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.random.Random

data class Question(
    val id: Int,
    val subject: String,
    val question: String,
    val options: List<String>,
    val answer: Int,
    val explanation: String
)

object QuestionRepo {
    private const val CUSTOM_FILE = "custom_questions.json"
    private const val PREFS_NAME = "tv_quiz_prefs"
    private const val KEY_PASSWORD = "parent_password"
    private const val KEY_INTERVAL_MIN = "quiz_interval_min"
    private const val KEY_PENALTY_SEC = "penalty_seconds"
    private const val KEY_GUARD_ACTIVE = "guard_active"
    private const val KEY_LOCKED_IN_QUIZ = "locked_in_quiz"
    private const val KEY_PENALTY_END_TIME = "penalty_end_time"

    fun getPassword(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_PASSWORD, "5555") ?: "5555"
    }

    fun setPassword(context: Context, pwd: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_PASSWORD, pwd.trim()).apply()
    }

    fun getIntervalMinutes(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_INTERVAL_MIN, 10)
    }

    fun setIntervalMinutes(context: Context, minutes: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_INTERVAL_MIN, minutes.coerceAtLeast(1)).apply()
    }

    fun getPenaltySeconds(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_PENALTY_SEC, 180) // 默认3分钟 = 180秒
    }

    fun setPenaltySeconds(context: Context, seconds: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_PENALTY_SEC, seconds.coerceAtLeast(5)).apply()
    }

    fun isGuardActive(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_GUARD_ACTIVE, false)
    }

    fun setGuardActive(context: Context, active: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_GUARD_ACTIVE, active).apply()
    }

    fun isLockedInQuiz(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_LOCKED_IN_QUIZ, false)
    }

    fun setLockedInQuiz(context: Context, locked: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_LOCKED_IN_QUIZ, locked).apply()
    }

    fun getPenaltyEndTime(context: Context): Long {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_PENALTY_END_TIME, 0L)
    }

    fun loadQuestionsJsonString(context: Context): String {
        val customFile = File(context.filesDir, CUSTOM_FILE)
        if (customFile.exists()) {
            try {
                val content = customFile.readText(Charsets.UTF_8)
                if (JSONArray(content).length() > 0) {
                    return content
                }
            } catch (_: Exception) {
                // 如果本地缓存文件损坏或格式不对，直接删掉它，防止无限闪退或读取旧题！
                customFile.delete()
            }
        }
        return context.assets.open("default_questions.json")
            .bufferedReader(Charsets.UTF_8).use { 
                it.readText() 
            }
    } 

    fun saveCustomQuestionsJson(context: Context, jsonStr: String): Int {
        val arr = JSONArray(jsonStr)
        if (arr.length() == 0) throw IllegalArgumentException("题库不能为空")
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            if (!obj.has("question") || !obj.has("options") || !obj.has("answer")) {
                throw IllegalArgumentException("第 ${i + 1} 题缺少必填字段 (question/options/answer)")
            }
        }
        File(context.filesDir, CUSTOM_FILE).writeText(arr.toString(2), Charsets.UTF_8)
        return arr.length()
    }

    fun resetToDefaultQuestions(context: Context) {
        val customFile = File(context.filesDir, CUSTOM_FILE)
        if (customFile.exists()) customFile.delete()
    }

    fun getAllQuestions(context: Context): List<Question> {
        val jsonStr = loadQuestionsJsonString(context)
        val arr = JSONArray(jsonStr)
        val list = mutableListOf<Question>()
        for (i in 0 until arr.length()) {
            val obj: JSONObject = arr.getJSONObject(i)
            val optsArr = obj.getJSONArray("options")
            val opts = mutableListOf<String>()
            for (j in 0 until optsArr.length()) {
                opts.add(optsArr.getString(j))
            }
            list.add(
                Question(
                    id = obj.optInt("id", i + 1),
                    subject = obj.optString("subject", "综合"),
                    question = obj.getString("question"),
                    options = opts,
                    answer = obj.getInt("answer"),
                    explanation = obj.optString("explanation", "请仔细审题思考哦！")
                )
            )
        }
        return list
    }

    fun getRandomQuestion(context: Context, excludeId: Int = -1): Question {
        val all = getAllQuestions(context)
        if (all.isEmpty()) {
            return Question(0, "数学", "7 × 8 = ?", listOf("54", "56", "58", "64"), 1, "七八五十六")
        }
        val candidates = if (all.size > 1 && excludeId != -1) all.filter { it.id != excludeId } else all
        return candidates[Random.nextInt(candidates.size)]
    }
}
