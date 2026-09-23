package com.neop2p.data.local

import android.content.Context
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory

/**
 * Builds a throwaway database at an arbitrary version with raw SQL, then lets
 * a test invoke a Migration's `migrate()` directly (F3, 2026-09-23).
 *
 * MigrationTestHelper cannot be used: it needs an exported schema JSON for the
 * START version, and AppDatabase exported no schemas before v33.
 */
object LegacySchema {

    fun open(
        context: Context,
        name: String,
        version: Int,
        createSql: List<String>
    ): SupportSQLiteDatabase {
        context.deleteDatabase(name)
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    createSql.forEach(db::execSQL)
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase
    }

    fun columns(db: SupportSQLiteDatabase, table: String): Set<String> {
        val out = mutableSetOf<String>()
        db.query("PRAGMA table_info($table)").use { c ->
            val idx = c.getColumnIndex("name")
            while (c.moveToNext()) out += c.getString(idx)
        }
        return out
    }

    fun tableExists(db: SupportSQLiteDatabase, table: String): Boolean =
        db.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
            arrayOf(table)
        ).use { it.moveToFirst() }

    fun migration(startVersion: Int): Migration =
        AppDatabase.ALL_MIGRATIONS.first { it.startVersion == startVersion }
}
