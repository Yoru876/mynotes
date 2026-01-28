package cl.example.mynotes

import android.os.Parcelable
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.parcelize.Parcelize

@Entity(tableName = "notes_table")
@Parcelize
data class Note(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    var title: String,
    var content: String,
    var date: String,

    var imagePath: String? = null,
    var color: String? = null,
    var webLink: String? = null,

    var category: String = "General",

    // CAMPO DE AUDIO (Debe ser var)
    var audioPath: String? = null
) : Parcelable