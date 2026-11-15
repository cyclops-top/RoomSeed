package top.cyclops.roomseed.demo.dictionary

import android.content.Context
import androidx.room.Room
import java.io.Closeable

class Dictionary(context: Context) : Closeable {
    private val database =
        Room.databaseBuilder(context.applicationContext, DictionaryDatabase::class.java, "dictionary.db")
            .createFromAsset("databases/dictionary.db")
            .build()

    fun search(query: String): List<DictionaryEntry> {
        val term = query.trim()
        val dao = database.entries()
        // Bind literal text as an FTS4 phrase.
        return if (term.isEmpty()) dao.listEntries()
        else dao.searchEntries("\"${term.replace("\"", "\"\"")}\"")
    }

    override fun close() = database.close()
}
