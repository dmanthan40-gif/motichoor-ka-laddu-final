package com.motichoorkaladdu.assistant.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.memoryDataStore by preferencesDataStore(name = "memory_anchors")

/**
 * Fixed relationship facts that should never depend on a network round trip.
 * These seed the system prompt directly (see SystemPrompt.kt) and are mirrored
 * into DataStore only so the on-device UI can display / let Duggu's boyfriend
 * edit them without touching code.
 */
object MemoryAnchors {
    const val DUGGU_BIRTHDAY = "May 14, 2010"
    const val ANNIVERSARY = "May 2, 2026"

    private val KEY_DUGGU_BIRTHDAY = stringPreferencesKey("duggu_birthday")
    private val KEY_ANNIVERSARY = stringPreferencesKey("anniversary")

    fun observe(context: Context): Flow<Pair<String, String>> =
        context.memoryDataStore.data.map { prefs ->
            (prefs[KEY_DUGGU_BIRTHDAY] ?: DUGGU_BIRTHDAY) to
                (prefs[KEY_ANNIVERSARY] ?: ANNIVERSARY)
        }

    suspend fun seedDefaults(context: Context) {
        context.memoryDataStore.edit { prefs ->
            if (prefs[KEY_DUGGU_BIRTHDAY] == null) prefs[KEY_DUGGU_BIRTHDAY] = DUGGU_BIRTHDAY
            if (prefs[KEY_ANNIVERSARY] == null) prefs[KEY_ANNIVERSARY] = ANNIVERSARY
        }
    }
}

// ---------------------------------------------------------------------------
// Room — rolling conversation memory, so the assistant has short-term recall
// across app restarts without re-sending the whole history to Groq every time.
// ---------------------------------------------------------------------------

@Entity(tableName = "conversation_turns")
data class ConversationTurn(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "role") val role: String, // "user" | "assistant"
    @ColumnInfo(name = "content") val content: String,
    @ColumnInfo(name = "timestamp") val timestamp: Long = System.currentTimeMillis()
)

@Dao
interface ConversationDao {
    @Insert
    suspend fun insert(turn: ConversationTurn)

    @Query("SELECT * FROM conversation_turns ORDER BY id DESC LIMIT :limit")
    suspend fun recent(limit: Int = 20): List<ConversationTurn>

    @Query("DELETE FROM conversation_turns")
    suspend fun clear()
}

@Database(entities = [ConversationTurn::class], version = 1, exportSchema = false)
abstract class MemoryDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao

    companion object {
        @Volatile private var instance: MemoryDatabase? = null

        fun get(context: Context): MemoryDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MemoryDatabase::class.java,
                    "motichoor_memory.db"
                ).build().also { instance = it }
            }
    }
}
