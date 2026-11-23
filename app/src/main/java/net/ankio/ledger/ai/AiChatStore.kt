package net.ankio.ledger.ai

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

/** Persistent local SQLite storage for AI chat messages and bookkeeping draft cards. */
internal class AiChatStore(context: Context, name: String = "ai-chat.db") :
    SQLiteOpenHelper(context, name, null, 1) {

    data class StoredMessage(
        val id: Long,
        val role: String,
        val text: String,
        val at: Long,
        val draft: JSONObject? = null,
        val isError: Boolean = false,
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                role TEXT NOT NULL,
                text TEXT NOT NULL,
                at INTEGER NOT NULL,
                draft_json TEXT,
                is_error INTEGER NOT NULL DEFAULT 0
            )"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized fun loadRecent(limit: Int = MAX_STORED_MESSAGES): List<StoredMessage> {
        val rows = ArrayList<StoredMessage>()
        readableDatabase.rawQuery(
            "SELECT id, role, text, at, draft_json, is_error FROM messages ORDER BY id DESC LIMIT ?",
            arrayOf(limit.coerceIn(1, MAX_STORED_MESSAGES).toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val draft = cursor.getString(4)?.takeIf { it.isNotBlank() }?.let { raw ->
                    runCatching { JSONObject(raw) }.getOrNull()
                }
                rows.add(
                    StoredMessage(
                        id = cursor.getLong(0),
                        role = cursor.getString(1),
                        text = cursor.getString(2),
                        at = cursor.getLong(3),
                        draft = draft,
                        isError = cursor.getInt(5) == 1,
                    )
                )
            }
        }
        rows.reverse()
        return rows
    }

    @Synchronized fun append(
        role: String,
        text: String,
        at: Long,
        draft: JSONObject? = null,
        isError: Boolean = false,
    ): StoredMessage {
        val db = writableDatabase
        val id = db.insertOrThrow(
            "messages",
            null,
            ContentValues().apply {
                put("role", role)
                put("text", text)
                put("at", at)
                if (draft != null) put("draft_json", draft.toString()) else putNull("draft_json")
                put("is_error", if (isError) 1 else 0)
            },
        )
        db.execSQL(
            "DELETE FROM messages WHERE id NOT IN (SELECT id FROM messages ORDER BY id DESC LIMIT ?)",
            arrayOf(MAX_STORED_MESSAGES.toString()),
        )
        return StoredMessage(id, role, text, at, draft, isError)
    }

    @Synchronized fun updateDraft(messageId: Long, draft: JSONObject) {
        writableDatabase.update(
            "messages",
            ContentValues().apply { put("draft_json", draft.toString()) },
            "id = ?",
            arrayOf(messageId.toString()),
        )
    }

    @Synchronized fun clear() {
        writableDatabase.delete("messages", null, null)
    }

    companion object {
        const val MAX_STORED_MESSAGES = 200
    }
}
