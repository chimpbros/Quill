package com.yugentech.quill.database.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `aira_discussions` (`bookId` TEXT NOT NULL, `topicId` TEXT NOT NULL, `selectionText` TEXT, `anchorLocatorJson` TEXT, `boundaryLocatorJson` TEXT, `mode` TEXT NOT NULL, `label` TEXT NOT NULL, `paragraphCount` INTEGER NOT NULL, `lastResponse` TEXT, `sourcesJson` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`bookId`), FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("ALTER TABLE `aira_messages` ADD COLUMN `topicId` TEXT")
        db.execSQL("ALTER TABLE `aira_messages` ADD COLUMN `boundaryLocatorJson` TEXT")
        db.execSQL("ALTER TABLE `aira_messages` ADD COLUMN `sourcesJson` TEXT")
    }
}
