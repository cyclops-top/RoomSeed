package top.cyclops.roomseed.demo.dictionary

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "entries")
data class DictionaryEntry(@PrimaryKey val word: String, val definition: String)
