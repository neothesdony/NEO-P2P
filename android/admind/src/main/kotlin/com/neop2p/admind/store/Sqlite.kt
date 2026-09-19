package com.neop2p.admind.store

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/**
 * Shared JDBC plumbing for the daemon's two stores. Both mirror the Room DDL
 * (`AppDatabase.kt`) column-for-column so a future Phase-3 unification needs no
 * transform. A connection is opened per call — the daemon is single-threaded
 * and SQLite opens are cheap; this avoids holding a lock across the ingest loop.
 */
internal object Sqlite {

    fun open(dbPath: Path): Connection {
        dbPath.parent?.let { Files.createDirectories(it) }
        return DriverManager.getConnection("jdbc:sqlite:$dbPath")
    }

    inline fun <T> withConnection(dbPath: Path, block: (Connection) -> T): T =
        open(dbPath).use(block)

    fun setNullableLong(ps: java.sql.PreparedStatement, index: Int, value: Long?) {
        if (value == null) ps.setNull(index, java.sql.Types.INTEGER) else ps.setLong(index, value)
    }
}
