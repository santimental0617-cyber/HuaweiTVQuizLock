package com.will.tvquizlock

import android.content.Context
import android.content.Intent
import fi.iki.elonen.NanoHTTPD

class WebServer(private val context: Context, port: Int = 8888) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        if (method == Method.POST) {
            val files = HashMap<String, String>()
            session.parseBody(files)
            val params = session.parameters

            when (uri) {
                "/api/save_questions" -> {
                    val json = params["json"]?.firstOrNull() ?: ""
                    return try {
                        val count = QuestionRepo.saveCustomQuestionsJson(context, json)
                        newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", "保存成功！当前题库共 $count 道题。")
                    } catch (e: Exception) {
                        newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain; charset=utf-8", "保存失败：${e.message}")
                    }
                }
                "/api/reset_questions" -> {
                    QuestionRepo.resetToDefaultQuestions(context)
                    return newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", "已恢复默认二年级内置题库！")
                }
                "/api/save_settings" -> {
                    val pwd = params["password"]?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: "5555"
                    val interval = params["interval"]?.firstOrNull()?.toIntOrNull() ?: 10
                    val penalty = params["penalty"]?.firstOrNull()?.toIntOrNull() ?: 180
                    QuestionRepo.setPassword(context, pwd)
                    QuestionRepo.setIntervalMinutes(context, interval)
                    QuestionRepo.setPenaltySeconds(context, penalty)
                    return newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", "设置已保存生效！")
                }
                "/api/unlock_now" -> {
                    QuestionRepo.setLockedInQuiz(context, false)
                    QuestionRepo.setPenaltyEndTime(context, 0L)
                    context.sendBroadcast(Intent(QuizActivity.ACTION_REMOTE_UNLOCK))
                    return newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", "已远程放行本次答题锁屏！")
                }
                "/api/trigger_quiz" -> {
                    GuardService.launchQuizNow(context)
                    return newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", "已在电视端触发弹题！")
                }
            }
        }

        val currentJson = QuestionRepo.loadQuestionsJsonString(context)
        val currentPwd = QuestionRepo.getPassword(context)
        val currentInterval = QuestionRepo.getIntervalMinutes(context)
        val currentPenalty = QuestionRepo.getPenaltySeconds(context)

        val html = """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>华为智慧屏 · 学习守护管理后台</title>
                <style>
                    body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; background: #0f172a; color: #f8fafc; max-width: 860px; margin: 0 auto; padding: 20px; }
                    .card { background: #1e293b; border: 1px solid #334155; border-radius: 12px; padding: 20px; margin-bottom: 20px; }
                    h2 { margin-top: 0; color: #38bdf8; }
                    label { display: block; margin: 10px 0 4px; color: #cbd5e1; font-size: 14px; }
                    input { width: 100%; padding: 10px; border-radius: 8px; border: 1px solid #475569; background: #0f172a; color: #fff; box-sizing: border-box; }
                    textarea { width: 100%; height: 360px; padding: 12px; border-radius: 8px; border: 1px solid #475569; background: #0f172a; color: #a7f3d0; font-family: monospace; font-size: 13px; box-sizing: border-box; }
                    button { background: #2563eb; color: #fff; border: none; padding: 10px 18px; border-radius: 8px; font-weight: bold; cursor: pointer; margin-right: 10px; margin-top: 12px; }
                    button.warn { background: #d97706; }
                    button.success { background: #059669; }
                    .msg { margin-top: 10px; color: #facc15; font-weight: bold; }
                </style>
            </head>
            <body>
                <h1>华为智慧屏 · 学习答题守护后台</h1>
                
                <div class="card">
                    <h2>快捷远程控制</h2>
                    <button class="success" onclick="postAction('/api/unlock_now')">一键远程解锁放行 (跳过当前题目)</button>
                    <button class="warn" onclick="postAction('/api/trigger_quiz')">立即在电视上弹出一道题</button>
                    <div id="ctrlMsg" class="msg"></div>
                </div>

                <div class="card">
                    <h2>规则与密码设置</h2>
                    <label>家长退出密码（默认 5555）：</label>
                    <input id="pwd" type="text" value="$currentPwd" />
                    <label>弹题间隔时间（分钟，默认 10 分钟）：</label>
                    <input id="interval" type="number" value="$currentInterval" />
                    <label>答错惩罚锁定时间（秒，默认 180 秒即 3 分钟）：</label>
                    <input id="penalty" type="number" value="$currentPenalty" />
                    <button onclick="saveSettings()">保存参数设置</button>
                    <div id="setMsg" class="msg"></div>
                </div>

                <div class="card">
                    <h2>题库管理 (JSON 格式，支持语文/数学/英语)</h2>
                    <p style="font-size:13px;color:#94a3b8;">说明：answer 为正确选项下标（0代表第1项A，1代表第2项B，2代表第3项C，3代表第4项D）。</p>
                    <textarea id="jsonBox">$currentJson</textarea>
                    <button onclick="saveQuestions()">保存并更新题库</button>
                    <button class="warn" onclick="resetQuestions()">恢复内置二年级默认题库</button>
                    <div id="qMsg" class="msg"></div>
                </div>

                <script>
                    async function postAction(url) {
                        const res = await fetch(url, { method: 'POST' });
                        document.getElementById('ctrlMsg').innerText = await res.text();
                    }
                    async function saveSettings() {
                        const body = new URLSearchParams();
                        body.append('password', document.getElementById('pwd').value);
                        body.append('interval', document.getElementById('interval').value);
                        body.append('penalty', document.getElementById('penalty').value);
                        const res = await fetch('/api/save_settings', { method: 'POST', body });
                        document.getElementById('setMsg').innerText = await res.text();
                    }
                    async function saveQuestions() {
                        const body = new URLSearchParams();
                        body.append('json', document.getElementById('jsonBox').value);
                        const res = await fetch('/api/save_questions', { method: 'POST', body });
                        document.getElementById('qMsg').innerText = await res.text();
                    }
                    async function resetQuestions() {
                        if(!confirm('确定要恢复内置二年级题库吗？')) return;
                        const res = await fetch('/api/reset_questions', { method: 'POST' });
                        document.getElementById('qMsg').innerText = await res.text();
                        setTimeout(() => location.reload(), 800);
                    }
                </script>
            </body>
            </html>
        """.trimIndent()

        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }
}
