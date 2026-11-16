package top.cyclops.roomseed.demo.dictionary

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions

@Entity(tableName = "entries_fts")
@Fts4(contentEntity = DictionaryEntry::class, tokenizer = FtsOptions.TOKENIZER_ICU)
internal data class DictionarySearchIndex(val word: String, val definition: String)
