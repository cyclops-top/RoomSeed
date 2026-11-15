package top.cyclops.roomseed.demo.room3

import androidx.room3.AutoMigration
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Entity
import androidx.room3.Fts5
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.RoomDatabase
import androidx.room3.migration.AutoMigrationSpec
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

@Entity(tableName = "books")
data class Book(
    @PrimaryKey(autoGenerate = true, algorithm = PrimaryKey.Algorithm.ROWID) val id: Long,
    val title: String,
    val author: String,
)

@Entity(tableName = "books_fts")
@Fts5(contentEntity = Book::class, contentRowId = "id")
internal data class BookSearchIndex(val title: String, val author: String)

@Dao
internal interface BookDao {
    @Query("SELECT * FROM books ORDER BY title")
    suspend fun listBooks(): List<Book>

    @Query(
        """
        SELECT books.* FROM books
        JOIN books_fts ON books.id = books_fts.rowid
        WHERE books_fts MATCH :query
        ORDER BY books_fts.rank, books.title
        """
    )
    suspend fun searchBooks(query: String): List<Book>
}

@Database(
    entities = [Book::class, BookSearchIndex::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2, spec = IndexExistingBooks::class)],
)
internal abstract class LibraryDatabase : RoomDatabase() {
    abstract fun books(): BookDao
}

internal class IndexExistingBooks : AutoMigrationSpec {
    override suspend fun onPostMigrate(connection: SQLiteConnection) {
        connection.execSQL("INSERT INTO books_fts(books_fts) VALUES ('rebuild')")
    }
}
