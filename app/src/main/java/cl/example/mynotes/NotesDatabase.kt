package cl.example.mynotes

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

// --- PARTE A: DAO (Las Instrucciones) ---
@Dao
interface NotesDao {
    // Traer todas las notas, ordenadas por ID (las últimas creadas primero)
    // Usamos Flow para que la lista se actualice sola si cambia algo
    @Query("SELECT * FROM notes_table ORDER BY id DESC")
    fun getAllNotes(): Flow<List<Note>>

    // Insertar una nota. Si ya existe (mismo ID), la reemplaza.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(note: Note)

    // Actualizar una nota existente (cambio de título o contenido)
    @Update
    suspend fun update(note: Note)

    // Borrar una nota
    @Delete
    suspend fun delete(note: Note)
}

// --- PARTE B: DATABASE (La Conexión) ---
@Database(entities = [Note::class], version = 1, exportSchema = false)
abstract class NotesDatabase : RoomDatabase() {

    abstract fun notesDao(): NotesDao

    companion object {
        // Singleton: Evita que se abran múltiples conexiones al mismo tiempo
        @Volatile
        private var INSTANCE: NotesDatabase? = null

        fun getDatabase(context: Context): NotesDatabase {
            // Si ya existe la conexión, la devolvemos
            return INSTANCE ?: synchronized(this) {
                // Si no existe, la creamos
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    NotesDatabase::class.java,
                    "mynotes_database" // Nombre del archivo real en el celular
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}