package net.ankio.ledger.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.ankio.ledger.LedgerApp
import net.ankio.ledger.core.AiChatProtocol
import net.ankio.ledger.core.AiDraftBill
import net.ankio.ledger.core.Direction
import net.ankio.ledger.core.Money
import net.ankio.ledger.data.LedgerReadView
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Native HTTPS client for user-configured OpenAI-compatible chat and bookkeeping assistance. */
class AiChatService(private val app: LedgerApp) {
    enum class Provider(
        val id: String,
        val label: String,
        val defaultBaseUrl: String,
        val defaultModel: String,
        val suggestedModels: List<String>,
    ) {
        SILICONFLOW(
            id = "siliconflow",
            label = "硅基流动 (SiliconFlow)",
            defaultBaseUrl = "https://api.siliconflow.cn/v1",
            defaultModel = "deepseek-ai/DeepSeek-V4.1-Flash",
            suggestedModels = listOf(
                "deepseek-ai/DeepSeek-V4.1-Flash",
                "deepseek-ai/DeepSeek-V4-Pro",
                "Qwen/Qwen3.8-27B",
                "zai-org/GLM-5.3-Flash",
            ),
        ),
        ALIYUN(
            id = "aliyun",
            label = "阿里云百炼 (DashScope)",
            defaultBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            defaultModel = "qwen3.8-flash",
            suggestedModels = listOf(
                "qwen3.8-flash",
                "qwen3.7-plus",
                "qwen4-max",
                "qwen3.8-omni-flash",
                "deepseek-v4.1-flash",
            ),
        ),
        CUSTOM(
            id = "custom",
            label = "自定义 OpenAI 兼容接口",
            defaultBaseUrl = "",
            defaultModel = "",
            suggestedModels = emptyList(),
        );

        companion object {
            fun fromId(id: String): Provider = entries.firstOrNull { it.id == id } ?: SILICONFLOW
        }
    }

    private val prefs = app.getSharedPreferences("ai_assistant", Context.MODE_PRIVATE)
    private val store = AiChatStore(app)
    private val history = ArrayDeque<AiChatStore.StoredMessage>(store.loadRecent())
    @Volatile private var busy = false
    private val _events = MutableSharedFlow<JSONObject>(extraBufferCapacity = 32)
    val events = _events.asSharedFlow()

    @Synchronized fun activeProvider(): Provider =
        Provider.fromId(prefs.getString("active_provider", Provider.SILICONFLOW.id).orEmpty())

    @Synchronized fun includeSummary(): Boolean = prefs.getBoolean("include_ledger_summary", true)

    @Synchronized fun baseUrl(provider: Provider = activeProvider()): String =
        prefs.getString("base_url_${provider.id}", provider.defaultBaseUrl).orEmpty().ifBlank { provider.defaultBaseUrl }

    @Synchronized fun model(provider: Provider = activeProvider()): String =
        prefs.getString("model_${provider.id}", provider.defaultModel).orEmpty().ifBlank { provider.defaultModel }

    @Synchronized private fun apiKey(provider: Provider = activeProvider()): String =
        prefs.getString("key_${provider.id}", "").orEmpty()

    @Synchronized fun configForProvider(providerId: String): JSONObject {
        val provider = Provider.fromId(providerId)
        val key = apiKey(provider)
        return JSONObject()
            .put("provider", provider.id)
            .put("label", provider.label)
            .put("baseUrl", baseUrl(provider))
            .put("model", model(provider))
            .put("hasApiKey", key.isNotBlank())
            .put("maskedApiKey", maskKey(key))
            .put("suggestedModels", JSONArray(provider.suggestedModels))
    }

    @Synchronized fun stateJson(): JSONObject {
        val active = activeProvider()
        val activeConfig = configForProvider(active.id)
        val providersJson = JSONArray()
        Provider.entries.forEach { providersJson.put(configForProvider(it.id)) }
        val messagesJson = JSONArray()
        history.forEach { msg ->
            messagesJson.put(
                JSONObject()
                    .put("id", msg.id)
                    .put("role", msg.role)
                    .put("text", msg.text)
                    .put("at", msg.at)
                    .put("isError", msg.isError)
                    .put("draft", msg.draft ?: JSONObject.NULL)
            )
        }
        return JSONObject()
            .put("ok", true)
            .put("provider", active.id)
            .put("baseUrl", activeConfig.getString("baseUrl"))
            .put("model", activeConfig.getString("model"))
            .put("hasApiKey", activeConfig.getBoolean("hasApiKey"))
            .put("maskedApiKey", activeConfig.getString("maskedApiKey"))
            .put("includeSummary", includeSummary())
            .put("busy", busy)
            .put("providers", providersJson)
            .put("messages", messagesJson)
    }

    @Synchronized fun saveConfig(input: JSONObject): JSONObject {
        val provider = Provider.fromId(input.optString("provider", activeProvider().id))
        val url = input.optString("baseUrl", provider.defaultBaseUrl).trim().trimEnd('/')
        val modelName = input.optString("model", provider.defaultModel).trim()
        val newKey = input.optString("apiKey", "").trim()
        val clearKey = input.optBoolean("clearApiKey", false)
        val summary = if (input.has("includeSummary")) input.optBoolean("includeSummary", true) else includeSummary()

        require(isAllowedEndpoint(url)) { "接口地址须以 https:// 开头（本地调试支持 http://localhost）" }
        require(modelName.isNotBlank() && modelName.length <= 120) { "请填写模型名称" }
        require(newKey.length <= 512) { "API Key 长度无效" }

        val editor = prefs.edit()
            .putString("active_provider", provider.id)
            .putString("base_url_${provider.id}", url)
            .putString("model_${provider.id}", modelName)
            .putBoolean("include_ledger_summary", summary)
        if (clearKey) {
            editor.remove("key_${provider.id}")
        } else if (newKey.isNotBlank()) {
            editor.putString("key_${provider.id}", newKey)
        }
        editor.apply()
        val snapshot = stateJson()
        emit(JSONObject().put("kind", "state").put("state", snapshot))
        return snapshot
    }

    @Synchronized fun clearHistory(): JSONObject {
        history.clear()
        store.clear()
        val snapshot = stateJson()
        emit(JSONObject().put("kind", "state").put("state", snapshot))
        return snapshot
    }

    @Synchronized fun markDraftSaved(messageId: Long, recordId: Long) {
        val msg = history.firstOrNull { it.id == messageId }
        msg?.draft?.let { draft ->
            draft.put("saved", true)
            draft.put("savedRecordId", recordId)
            store.updateDraft(messageId, draft)
        }
        emit(JSONObject().put("kind", "state").put("state", stateJson()))
    }

    fun testConnection(input: JSONObject) {
        val provider = Provider.fromId(input.optString("provider", activeProvider().id))
        val url = input.optString("baseUrl", baseUrl(provider)).trim().trimEnd('/')
        val modelName = input.optString("model", model(provider)).trim()
        val candidateKey = input.optString("apiKey", "").trim().ifBlank { apiKey(provider) }

        if (!isAllowedEndpoint(url)) {
            emit(JSONObject().put("kind", "test").put("ok", false).put("message", "接口地址须以 https:// 开头"))
            return
        }
        if (modelName.isBlank()) {
            emit(JSONObject().put("kind", "test").put("ok", false).put("message", "请先填写模型名称"))
            return
        }
        if (candidateKey.isBlank()) {
            emit(JSONObject().put("kind", "test").put("ok", false).put("message", "请先填写 API Key"))
            return
        }
        app.scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val reply = callCompletions(
                        endpoint = "$url/chat/completions",
                        key = candidateKey,
                        model = modelName,
                        messages = listOf("user" to "请只回复：OK"),
                        systemPrompt = "你是一个测试连接助手，只回复OK即可。",
                        maxTokens = 16,
                    )
                    reply.isNotBlank()
                }
            }
            result.onSuccess {
                emit(JSONObject().put("kind", "test").put("ok", true).put("message", "连接成功：$modelName 可用"))
            }.onFailure { error ->
                emit(JSONObject().put("kind", "test").put("ok", false).put("message", error.message ?: "连接失败"))
            }
        }
    }

    fun sendMessage(rawText: String, month: String) {
        val text = rawText.trim()
        require(text.isNotEmpty() && text.length <= 2000) { "请输入 1–2000 字的消息" }
        val provider: Provider
        val url: String
        val modelName: String
        val key: String
        val withSummary: Boolean
        val conversation: List<Pair<String, String>>
        synchronized(this) {
            if (busy) throw IllegalStateException("AI 正在思考上一条消息，请稍候")
            provider = activeProvider()
            url = baseUrl(provider)
            modelName = model(provider)
            key = apiKey(provider)
            withSummary = includeSummary()
            require(isAllowedEndpoint(url) && modelName.isNotBlank() && key.isNotBlank()) {
                "请先配置 ${provider.label} 的模型和 API Key"
            }
            busy = true
            history.addLast(store.append("user", text, System.currentTimeMillis()))
            while (history.size > AiChatStore.MAX_STORED_MESSAGES) history.removeFirst()
            conversation = history.filter { !it.isError }.takeLast(12).map { it.role to it.text }
            emit(JSONObject().put("kind", "state").put("state", stateJson()))
        }

        app.scope.launch {
            try {
                val systemPrompt = withContext(Dispatchers.IO) { buildPrompt(month, withSummary) }
                val rawReply = withContext(Dispatchers.IO) {
                    callCompletions(
                        endpoint = "${url.trimEnd('/')}/chat/completions",
                        key = key,
                        model = modelName,
                        messages = conversation,
                        systemPrompt = systemPrompt,
                        maxTokens = 1024,
                    )
                }
                val parsed = AiChatProtocol.parseAssistantReply(rawReply)
                val draftJson = parsed.draft?.let { toDraftJson(it) }
                synchronized(this@AiChatService) {
                    history.addLast(
                        store.append(
                            role = "assistant",
                            text = parsed.replyText,
                            at = System.currentTimeMillis(),
                            draft = draftJson,
                        )
                    )
                    while (history.size > AiChatStore.MAX_STORED_MESSAGES) history.removeFirst()
                    busy = false
                    emit(JSONObject().put("kind", "state").put("state", stateJson()))
                }
            } catch (error: Exception) {
                val message = error.message?.takeIf { it.isNotBlank() } ?: "请求失败，请检查网络或 API 配置"
                synchronized(this@AiChatService) {
                    history.addLast(
                        store.append(
                            role = "assistant",
                            text = "调用失败：$message",
                            at = System.currentTimeMillis(),
                            isError = true,
                        )
                    )
                    while (history.size > AiChatStore.MAX_STORED_MESSAGES) history.removeFirst()
                    busy = false
                    emit(JSONObject().put("kind", "state").put("state", stateJson()))
                }
            }
        }
    }

    private fun buildPrompt(month: String, withSummary: Boolean): String {
        val accounts = app.store.accounts()
        val custom = app.store.customCategories()
        val expenseCategories = (DEFAULT_EXPENSE_CATEGORIES + custom.filter { it.direction == Direction.EXPENSE }.map { it.name }).distinct()
        val incomeCategories = (DEFAULT_INCOME_CATEGORIES + custom.filter { it.direction == Direction.INCOME }.map { it.name }).distinct()
        val summaryText = if (!withSummary) "" else buildAnonymizedSummary(month)
        return AiChatProtocol.buildSystemPrompt(
            month = month,
            summaryText = summaryText,
            accountNames = accounts.map { it.name },
            expenseCategories = expenseCategories,
            incomeCategories = incomeCategories,
        )
    }

    private fun buildAnonymizedSummary(month: String): String {
        val read = LedgerReadView(app.store)
        val summary = runCatching { read.summary(month) }.getOrNull() ?: return ""
        val totals = summary.optJSONObject("totals")
        val expense = totals?.optLong("expense", 0L) ?: 0L
        val income = totals?.optLong("income", 0L) ?: 0L
        val expenseCount = totals?.optInt("expenseCount", 0) ?: 0
        val incomeCount = totals?.optInt("incomeCount", 0) ?: 0
        val budget = app.monthlyBudget.cents()

        val sb = StringBuilder()
        sb.appendLine("${month} 汇总：支出 ¥${Money.display(expense)}（${expenseCount} 笔），收入 ¥${Money.display(income)}（${incomeCount} 笔）")
        if (budget > 0) sb.appendLine("本月预算上限：¥${Money.display(budget)}")

        val categories = summary.optJSONArray("categories")
        if (categories != null && categories.length() > 0) {
            val catSummary = buildList {
                for (i in 0 until minOf(categories.length(), 10)) {
                    val item = categories.getJSONObject(i)
                    val dir = if (item.optString("direction") == "INCOME") "收入" else "支出"
                    add("$dir-${item.optString("name")}:¥${Money.display(item.optLong("amount"))}")
                }
            }.joinToString("；")
            sb.appendLine("主要分类：$catSummary")
        }

        val recentView = runCatching { read.records(month, limit = 15, statistical = true) }.getOrNull()
        val rows = recentView?.optJSONArray("records")
        if (rows != null && rows.length() > 0) {
            val formatter = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
            sb.appendLine("最近 ${rows.length()} 笔脱敏流水：")
            for (i in 0 until rows.length()) {
                val r = rows.getJSONObject(i)
                val dir = if (r.optString("direction") == "INCOME") "+" else "-"
                val amount = Money.display(r.optLong("statisticalAmountCents", r.optLong("amountCents")))
                val at = formatter.format(Date(r.optLong("occurredAt")))
                sb.appendLine("  $at | $dir¥$amount | ${r.optString("merchant")} | ${r.optString("category")}")
            }
        }
        return sb.toString()
    }

    private fun toDraftJson(draft: AiDraftBill): JSONObject {
        val accounts = app.store.accounts()
        val matchedAccount = draft.accountName?.let { target ->
            accounts.firstOrNull { it.name.equals(target, ignoreCase = true) }
                ?: accounts.firstOrNull { it.name.contains(target, ignoreCase = true) || target.contains(it.name, ignoreCase = true) }
        }
        return JSONObject()
            .put("amount", Money.display(draft.amountCents).replace(",", ""))
            .put("amountCents", draft.amountCents)
            .put("direction", draft.direction.name)
            .put("merchant", draft.merchant)
            .put("category", draft.category)
            .put("occurredAt", draft.occurredAt)
            .put("note", draft.note)
            .put("accountUid", matchedAccount?.uid ?: JSONObject.NULL)
            .put("accountName", matchedAccount?.name ?: draft.accountName ?: "")
            .put("saved", false)
    }

    private fun callCompletions(
        endpoint: String,
        key: String,
        model: String,
        messages: List<Pair<String, String>>,
        systemPrompt: String,
        maxTokens: Int,
    ): String {
        val payload = JSONObject().apply {
            put("model", model)
            put("temperature", 0.4)
            put("max_tokens", maxTokens)
            val arr = JSONArray()
            arr.put(JSONObject().put("role", "system").put("content", systemPrompt))
            messages.forEach { (role, content) ->
                arr.put(JSONObject().put("role", role).put("content", content))
            }
            put("messages", arr)
        }.toString()

        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("Authorization", "Bearer $key")
        }
        try {
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val apiMsg = runCatching { JSONObject(body).optJSONObject("error")?.optString("message") }.getOrNull().orEmpty()
                throw IOException(
                    when (code) {
                        401, 403 -> "API Key 无效或无权访问该模型 ($code)"
                        404 -> "接口地址或模型名称不存在 ($code)"
                        429 -> "请求过于频繁或账户额度不足 ($code)"
                        else -> if (apiMsg.isNotBlank()) "服务返回错误 ($code): ${apiMsg.take(120)}" else "请求失败 (HTTP $code)"
                    }
                )
            }
            val json = JSONObject(body)
            val content = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
            return content?.takeIf { it.isNotBlank() } ?: throw IOException("模型返回内容为空")
        } finally {
            conn.disconnect()
        }
    }

    private fun emit(event: JSONObject) {
        _events.tryEmit(event)
    }

    private fun isAllowedEndpoint(url: String): Boolean =
        url.startsWith("https://") && url.length > 10 ||
            url.startsWith("http://127.0.0.1") ||
            url.startsWith("http://localhost")

    private fun maskKey(key: String): String = when {
        key.isBlank() -> ""
        key.length <= 8 -> "****"
        else -> "${key.take(5)}****${key.takeLast(4)}"
    }

    companion object {
        private val DEFAULT_EXPENSE_CATEGORIES = listOf("餐饮", "交通", "购物", "娱乐", "居住", "医疗", "教育", "服饰", "日用", "通讯", "人情", "其他")
        private val DEFAULT_INCOME_CATEGORIES = listOf("工资", "奖金", "礼物", "退款", "理财收益", "报销入账", "兼职外快", "其他收入")
    }
}
