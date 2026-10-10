package com.yugentech.quill.database.database
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
val MIGRATION_3_4 = object:Migration(3,4) {
 override fun migrate(db:SupportSQLiteDatabase) { db.execSQL("ALTER TABLE aira_messages ADD COLUMN chapterIndex INTEGER");db.execSQL("DROP TABLE IF EXISTS quotas") }
}
