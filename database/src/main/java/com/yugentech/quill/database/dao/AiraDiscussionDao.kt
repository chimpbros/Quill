package com.yugentech.quill.database.dao

import androidx.room.*
import com.yugentech.quill.database.entity.AiraDiscussionEntity
import com.yugentech.quill.database.entity.AiraMessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AiraDiscussionDao {
    @Query("SELECT * FROM aira_discussions WHERE bookId = :bookId")
    fun observe(bookId: String): Flow<AiraDiscussionEntity?>
    @Query("SELECT * FROM aira_discussions WHERE bookId = :bookId")
    suspend fun get(bookId: String): AiraDiscussionEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(discussion: AiraDiscussionEntity)
    @Query("DELETE FROM aira_discussions WHERE bookId = :bookId")
    suspend fun clear(bookId: String)
    @Query("UPDATE aira_discussions SET lastResponse = :text, sourcesJson = :sources, updatedAt = :time WHERE bookId = :bookId AND topicId = :topicId")
    suspend fun complete(bookId: String, topicId: String, text: String, sources: String, time: Long)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTurnMessages(messages: List<AiraMessageEntity>)
    @Transaction
    suspend fun completeTurn(bookId: String, topicId: String, text: String, sources: String, time: Long,
        messages: List<AiraMessageEntity>) {
        insertTurnMessages(messages)
        complete(bookId, topicId, text, sources, time)
    }
}
