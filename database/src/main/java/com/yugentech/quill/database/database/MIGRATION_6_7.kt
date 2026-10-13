package com.yugentech.quill.database.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `highlights` ADD COLUMN `segmentsJson` TEXT")
        db.execSQL("ALTER TABLE `highlights` ADD COLUMN `selectedText` TEXT")
    }
}
