package com.yugentech.quill.database.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `bookmarks` (`id` TEXT NOT NULL, `bookId` TEXT NOT NULL, `locatorJson` TEXT NOT NULL, `locationKey` TEXT NOT NULL, `label` TEXT, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_bookmarks_bookId` ON `bookmarks` (`bookId`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_bookmarks_bookId_locationKey` ON `bookmarks` (`bookId`, `locationKey`)")
        db.execSQL("ALTER TABLE `aira_messages` ADD COLUMN `requiresSpoilerPermission` INTEGER NOT NULL DEFAULT 0")
        // Legacy turns have no scope provenance. Keep their transcript but do not treat it as safe evidence.
        db.execSQL("UPDATE `aira_messages` SET `requiresSpoilerPermission` = 1")
    }
}
