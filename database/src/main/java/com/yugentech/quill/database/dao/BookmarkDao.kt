package com.yugentech.quill.database.dao

import androidx.room.*
import com.yugentech.quill.database.entity.BookmarkEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY createdAt")
    fun observe(bookId: String): Flow<List<BookmarkEntity>>
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId AND locationKey = :key LIMIT 1")
    suspend fun atLocation(bookId: String, key: String): BookmarkEntity?
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(bookmark: BookmarkEntity): Long
    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun delete(id: String)
    @Query("UPDATE bookmarks SET label = :label WHERE id = :id")
    suspend fun rename(id: String, label: String?)
}
