package top.cyclops.roomseed.demo.dictionary

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [DictionaryEntry::class, DictionarySearchIndex::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2, spec = IndexExistingEntries::class)],
)
internal abstract class DictionaryDatabase : RoomDatabase() {
    abstract fun entries(): DictionaryDao
}

internal class IndexExistingEntries : AutoMigrationSpec {
    override fun onPostMigrate(db: SupportSQLiteDatabase) {
        db.execSQL("INSERT INTO entries_fts(entries_fts) VALUES ('rebuild')")
    }
}
