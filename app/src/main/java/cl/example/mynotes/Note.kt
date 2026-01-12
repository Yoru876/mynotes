package cl.example.mynotes

import android.os.Parcelable
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.parcelize.Parcelize

@Entity(tableName = "notes_table")
@Parcelize // <--- ESTO ES LA MAGIA (Hace que sea rápido pasar datos)
data class Note(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    var title: String,
    var content: String,
    var date: String,

    // Mantenemos estos campos aunque no se usen mucho ahora,
    // para evitar errores de migración de base de datos.
    var imagePath: String? = null,
    var color: String? = null,
    var webLink: String? = null
) : Parcelable