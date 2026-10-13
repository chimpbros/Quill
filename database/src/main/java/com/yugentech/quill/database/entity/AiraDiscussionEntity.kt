package com.yugentech.quill.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/** The active discussion for a book; older turns remain in the transcript under their topic IDs. */
@Entity(tableName = "aira_discussions", foreignKeys = [ForeignKey(entity = BookEntity::class,
    parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)])
data class AiraDiscussionEntity(
    @PrimaryKey val bookId: String,
    val topicId: String,
    val selectionText: String?,
    val anchorLocatorJson: String?,
    val boundaryLocatorJson: String?,
    val mode: String,
    val label: String,
    val paragraphCount: Int,
    val lastResponse: String?,
    val sourcesJson: String,
    val updatedAt: Long
)
