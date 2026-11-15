package top.cyclops.roomseed.demo.room3

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.Closeable

class Library(context: Context) : Closeable {
    private val database = Room.databaseBuilder<LibraryDatabase>(context.applicationContext, "library.db")
        .setDriver(BundledSQLiteDriver())
        .createFromAsset("databases/library.db")
        .build()

    suspend fun search(query: String): List<Book> {
        val term = query.trim()
        val dao = database.books()
        // Bind literal text as an FTS5 phrase.
        return if (term.isEmpty()) dao.listBooks()
        else dao.searchBooks("\"${term.replace("\"", "\"\"")}\"")
    }

    override fun close() = database.close()
}
