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

// --- DAO (Instrucciones de Base de Datos) ---
@Dao
interface NotesDao {
    // Para mostrar en la UI en tiempo real
    @Query("SELECT * FROM notes_table ORDER BY id DESC")
    fun getAllNotes(): Flow<List<Note>>

    // --- NUEVO: FUNCIÓN PARA BUSCAR NOTAS ---
    // Busca coincidencias en el título O en el contenido
    @Query("SELECT * FROM notes_table WHERE title LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%' ORDER BY id DESC")
    fun searchNotes(query: String): Flow<List<Note>>

    // Para obtener la lista estática (Backup)
    @Query("SELECT * FROM notes_table ORDER BY id DESC")
    suspend fun getAllNotesList(): List<Note>

    // Para insertar una sola nota
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(note: Note)

    // Para insertar muchas notas de golpe (Restauración)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(notes: List<Note>)

    @Update
    suspend fun update(note: Note)

    @Delete
    suspend fun delete(note: Note)
}

// --- DATABASE (Conexión Singleton) ---
@Database(entities = [Note::class], version = 1, exportSchema = false)
abstract class NotesDatabase : RoomDatabase() {

    abstract fun notesDao(): NotesDao

    companion object {
        @Volatile
        private var INSTANCE: NotesDatabase? = null

        fun getDatabase(context: Context): NotesDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    NotesDatabase::class.java,
                    "mynotes_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}