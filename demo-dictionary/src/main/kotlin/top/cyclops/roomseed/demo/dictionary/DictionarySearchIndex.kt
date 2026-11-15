package top.cyclops.roomseed.demo.dictionary

import androidx.room.Entity
import androidx.room.Fts4

@Entity(tableName = "entries_fts")
@Fts4(contentEntity = DictionaryEntry::class)
internal data class DictionarySearchIndex(val word: String, val definition: String)
