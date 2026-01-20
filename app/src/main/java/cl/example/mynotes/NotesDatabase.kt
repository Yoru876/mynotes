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

// --- DAO ---
@Dao
interface NotesDao {
    @Query("SELECT * FROM notes_table ORDER BY id DESC")
    fun getAllNotes(): Flow<List<Note>>

    @Query("SELECT * FROM notes_table WHERE title LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%' ORDER BY id DESC")
    fun searchNotes(query: String): Flow<List<Note>>

    // --- NUEVOS MÉTODOS PARA CATEGORÍAS ---

    // Filtrar solo por categoría
    @Query("SELECT * FROM notes_table WHERE category = :category ORDER BY id DESC")
    fun getNotesByCategory(category: String): Flow<List<Note>>

    // Buscar texto DENTRO de una categoría específica
    @Query("SELECT * FROM notes_table WHERE (title LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%') AND category = :category ORDER BY id DESC")
    fun searchNotesByCategory(query: String, category: String): Flow<List<Note>>

    // 1. Renombrar categoría masivamente
    @Query("UPDATE notes_table SET category = :newName WHERE category = :oldName")
    suspend fun updateCategoryName(oldName: String, newName: String)

    // 2. Mover notas a 'General' al borrar una categoría
    @Query("UPDATE notes_table SET category = 'General' WHERE category = :categoryName")
    suspend fun removeCategoryFromNotes(categoryName: String)

    // ----------------------------------------

    @Query("SELECT * FROM notes_table ORDER BY id DESC")
    suspend fun getAllNotesList(): List<Note>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(note: Note)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(notes: List<Note>)

    @Update
    suspend fun update(note: Note)

    @Delete
    suspend fun delete(note: Note)
}

// --- DATABASE ---
// IMPORTANTE: Cambiamos version = 1 a version = 2
@Database(entities = [Note::class], version = 2, exportSchema = false)
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
                )
                    // IMPORTANTE: Esto permite actualizar la estructura borrando la vieja si es necesario
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}