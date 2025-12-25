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

    // --- NUEVO: BUSCADOR ---
    // El operador || concatena strings en SQL. Filtramos si el texto está en título O contenido.
    @Query("SELECT * FROM notes_table WHERE title LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%' ORDER BY id DESC")
    fun searchNotes(query: String): Flow<List<Note>>

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