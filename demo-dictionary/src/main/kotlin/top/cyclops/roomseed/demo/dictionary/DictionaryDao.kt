package top.cyclops.roomseed.demo.dictionary

import androidx.room.Dao
import androidx.room.Query

@Dao
internal interface DictionaryDao {
    @Query("SELECT * FROM entries ORDER BY word") fun listEntries(): List<DictionaryEntry>

    @Query(
        """
        SELECT entries.* FROM entries
        JOIN entries_fts ON entries.rowid = entries_fts.rowid
        WHERE entries_fts MATCH :query
        ORDER BY entries.word
        """
    )
    fun searchEntries(query: String): List<DictionaryEntry>

    @Query("SELECT * FROM entries WHERE word = :word") fun find(word: String): DictionaryEntry?
}
