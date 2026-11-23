package net.ankio.ledger.core

import org.junit.Assert.*
import org.junit.Test

class AiChatProtocolTest {
    @Test fun extractsExpenseDraftAndStripsRawCodeBlock() {
        val raw = """
            好的，已为你识别这笔消费，请确认后入账：
            ```ledger-draft
            {"amount":32.50,"direction":"EXPENSE","merchant":"菜市场买菜","category":"餐饮","occurredAt":"2026-11-21 10:15","accountName":"微信零钱","note":"买青菜"}
            ```
        """.trimIndent()
        val parsed = AiChatProtocol.parseAssistantReply(raw, 1_760_000_000_000L)
        assertEquals("好的，已为你识别这笔消费，请确认后入账：", parsed.replyText)
        val draft = requireNotNull(parsed.draft)
        assertEquals(3250L, draft.amountCents)
        assertEquals(Direction.EXPENSE, draft.direction)
        assertEquals("菜市场买菜", draft.merchant)
        assertEquals("餐饮", draft.category)
        assertEquals("微信零钱", draft.accountName)
        assertEquals("买青菜", draft.note)
    }

    @Test fun keepsPlainConversationalReplyWithoutDraft() {
        val raw = "你本月餐饮一共支出 ¥ 850.00，占总支出的 42%。"
        val parsed = AiChatProtocol.parseAssistantReply(raw, 1_760_000_000_000L)
        assertEquals(raw, parsed.replyText)
        assertNull(parsed.draft)
    }

    @Test fun rejectsInvalidOrZeroAmountDraft() {
        val raw = """
            请看：
            ```ledger-draft
            {"amount":0,"direction":"EXPENSE","merchant":"测试","category":"其他"}
            ```
        """.trimIndent()
        val parsed = AiChatProtocol.parseAssistantReply(raw, 1_760_000_000_000L)
        assertNull(parsed.draft)
    }
}
