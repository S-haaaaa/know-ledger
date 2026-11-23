package net.ankio.ledger.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class AiDraftBill(
    val amountCents: Long,
    val direction: Direction,
    val merchant: String,
    val category: String,
    val occurredAt: Long,
    val note: String = "",
    val accountName: String? = null,
)

data class AiParsedReply(
    val replyText: String,
    val draft: AiDraftBill? = null,
)

/** Builds privacy-bounded prompts and extracts optional structured bookkeeping drafts from LLM replies. */
object AiChatProtocol {
    private val draftFence = Regex("```(?:ledger-draft|json)\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
    private val fieldNumber = Regex("\"(?:amount|amountYuan)\"\\s*:\\s*\"?([0-9]+(?:\\.[0-9]{1,2})?)\"?")
    private val fieldDirection = Regex("\"direction\"\\s*:\\s*\"(EXPENSE|INCOME)\"", RegexOption.IGNORE_CASE)
    private val fieldMerchant = Regex("\"merchant\"\\s*:\\s*\"([^\"]{0,80})\"")
    private val fieldCategory = Regex("\"category\"\\s*:\\s*\"([^\"]{0,40})\"")
    private val fieldNote = Regex("\"note\"\\s*:\\s*\"([^\"]{0,200})\"")
    private val fieldAccount = Regex("\"accountName\"\\s*:\\s*\"([^\"]{0,40})\"")
    private val fieldTime = Regex("\"occurredAt\"\\s*:\\s*\"([^\"]{0,32})\"")

    fun buildSystemPrompt(
        month: String,
        summaryText: String,
        accountNames: List<String>,
        expenseCategories: List<String>,
        incomeCategories: List<String>,
        todayIso: String = LocalDate.now().toString(),
    ): String {
        val accountsLine = if (accountNames.isEmpty()) "无已建账户" else accountNames.joinToString("、")
        val expenseLine = expenseCategories.joinToString("、")
        val incomeLine = incomeCategories.joinToString("、")
        return buildString {
            appendLine("你是「知账」本地记账 App 的 AI 财务与记账助手。请使用简洁自然的简体中文回复。")
            appendLine("当前日期：$todayIso；当前查看月份：$month。")
            appendLine("用户可选支出分类：$expenseLine。")
            appendLine("用户可选收入分类：$incomeLine。")
            appendLine("用户已有资产账户：$accountsLine。")
            if (summaryText.isNotBlank()) {
                appendLine("以下为本机脱敏后的账本统计摘要（仅用于回答查账与理财建议，切勿编造不存在的数据）：")
                appendLine(summaryText.trim())
            }
            appendLine()
            appendLine("当用户明确表达“记一笔收支”的意图（例如“午饭花了32.5元”、“收到报销500元微信”）时，请在回复末尾附带一个单独的代码块：")
            appendLine("```ledger-draft")
            appendLine("{\"amount\":32.5,\"direction\":\"EXPENSE\",\"merchant\":\"午餐\",\"category\":\"餐饮\",\"occurredAt\":\"YYYY-MM-DD HH:mm\",\"accountName\":\"\",\"note\":\"\"}")
            appendLine("```")
            appendLine("要求：1) direction 只能是 EXPENSE 或 INCOME；2) amount 必须为大于 0 的数字；3) category 优先从上述用户分类中选择；4) 如果用户未提及具体时间或账户，occurredAt 可留空，accountName 尽量匹配已有账户名或留空；5) 纯咨询或闲聊时不要输出 ledger-draft 代码块。")
        }.trim()
    }

    fun parseAssistantReply(rawContent: String, defaultOccurredAt: Long = System.currentTimeMillis()): AiParsedReply {
        val trimmed = rawContent.trim()
        val match = draftFence.find(trimmed) ?: return AiParsedReply(trimmed, null)
        val jsonBody = match.groupValues[1].trim()
        val draft = parseDraftJson(jsonBody, defaultOccurredAt) ?: return AiParsedReply(trimmed, null)
        val cleanedText = trimmed.replace(match.value, "").trim().ifBlank {
            val action = if (draft.direction == Direction.INCOME) "收入" else "支出"
            "已为你整理一笔${action}草稿：${draft.merchant} ¥${Money.display(draft.amountCents)}（${draft.category}），确认后即可入账。"
        }
        return AiParsedReply(cleanedText, draft)
    }

    internal fun parseDraftJson(jsonBody: String, defaultOccurredAt: Long): AiDraftBill? {
        val amountRaw = fieldNumber.find(jsonBody)?.groupValues?.get(1) ?: return null
        val amountCents = Money.parseCents(amountRaw)?.takeIf { it > 0 } ?: return null
        val dirRaw = fieldDirection.find(jsonBody)?.groupValues?.get(1)?.uppercase() ?: "EXPENSE"
        val direction = if (dirRaw == "INCOME") Direction.INCOME else Direction.EXPENSE
        val category = unescapeJsonString(fieldCategory.find(jsonBody)?.groupValues?.get(1).orEmpty())
            .trim().ifBlank { if (direction == Direction.INCOME) "其他收入" else "其他" }
        val merchant = unescapeJsonString(fieldMerchant.find(jsonBody)?.groupValues?.get(1).orEmpty())
            .trim().ifBlank { category }
        val note = unescapeJsonString(fieldNote.find(jsonBody)?.groupValues?.get(1).orEmpty()).trim()
        val accountName = unescapeJsonString(fieldAccount.find(jsonBody)?.groupValues?.get(1).orEmpty())
            .trim().takeIf { it.isNotBlank() && it != "null" }
        val occurredAt = parseTime(unescapeJsonString(fieldTime.find(jsonBody)?.groupValues?.get(1).orEmpty()).trim(), defaultOccurredAt)
        return AiDraftBill(amountCents, direction, merchant.take(80), category.take(40), occurredAt, note.take(200), accountName)
    }

    private fun parseTime(raw: String, fallback: Long): Long {
        if (raw.isBlank() || raw == "null" || raw.startsWith("YYYY")) return fallback
        val zone = ZoneId.systemDefault()
        val dateTimeFormats = listOf(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
        )
        for (formatter in dateTimeFormats) {
            val parsed = runCatching { LocalDateTime.parse(raw.replace('T', ' ').take(19), formatter).atZone(zone).toInstant().toEpochMilli() }.getOrNull()
            if (parsed != null && parsed in 946_656_000_000L..4_102_444_800_000L) return parsed
        }
        val dateOnly = runCatching {
            val date = LocalDate.parse(raw.take(10), DateTimeFormatter.ISO_LOCAL_DATE)
            val fallbackTime = Instant.ofEpochMilli(fallback).atZone(zone).toLocalTime()
            LocalDateTime.of(date, fallbackTime).atZone(zone).toInstant().toEpochMilli()
        }.getOrNull()
        return dateOnly?.takeIf { it in 946_656_000_000L..4_102_444_800_000L } ?: fallback
    }

    private fun unescapeJsonString(value: String): String = value
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")
        .replace("\\n", " ")
}
