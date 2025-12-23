package cl.example.mynotes // Asegúrate que coincida con tu paquete real

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.io.Serializable

// @Entity: Esto convierte la clase en una Tabla de base de datos
@Entity(tableName = "notes_table")
data class Note(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0, // ID único autogenerado (1, 2, 3...)

    var title: String,
    var content: String,
    var date: String,

    // --- PREPARADO PARA EL FUTURO (FASE 2) ---
    var imagePath: String? = null, // Ruta de foto adjunta DENTRO de la nota
    var color: String? = null,      // Color o ruta del fondo personalizado
    var webLink: String? = null     // Por si quieres guardar links
) : Serializable // Permite pasar la nota de una pantalla a otra