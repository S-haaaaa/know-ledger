package net.ankio.ledger.ai

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AiChatStoreTest {
    @Test fun persistsMessagesAndDraftSavedStateAcrossStoreReopen() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "test-ai-chat-${UUID.randomUUID()}.db"
        try {
            val first = AiChatStore(context, dbName)
            first.append("user", "午饭花了 32.5 元", 1_000L)
            val reply = first.append(
                "assistant",
                "已为你整理草稿",
                2_000L,
                draft = JSONObject().put("amount", "32.5").put("saved", false),
            )
            first.updateDraft(reply.id, JSONObject().put("amount", "32.5").put("saved", true).put("savedRecordId", 99L))
            first.close()

            val reopened = AiChatStore(context, dbName)
            val loaded = reopened.loadRecent()
            assertEquals(2, loaded.size)
            assertEquals("user", loaded[0].role)
            assertEquals("午饭花了 32.5 元", loaded[0].text)
            assertEquals(true, loaded[1].draft?.optBoolean("saved"))
            assertEquals(99L, loaded[1].draft?.optLong("savedRecordId"))

            reopened.clear()
            assertTrue(reopened.loadRecent().isEmpty())
            reopened.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }
}
