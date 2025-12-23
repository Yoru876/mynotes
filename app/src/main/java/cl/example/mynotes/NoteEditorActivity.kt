package cl.example.mynotes

import android.os.Bundle
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NoteEditorActivity : AppCompatActivity() {

    private lateinit var etTitle: EditText
    private lateinit var etContent: EditText
    private lateinit var tvDate: TextView
    private var noteToEdit: Note? = null

    // Inicializamos la DB de forma perezosa (solo cuando se use)
    private val db by lazy { NotesDatabase.getDatabase(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_note_editor)

        // --- ESTÉTICA: Iconos oscuros y Edge-to-Edge ---
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.editor_root)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        etTitle = findViewById(R.id.et_title)
        etContent = findViewById(R.id.et_content)
        tvDate = findViewById(R.id.tv_date_label)
        val btnSave = findViewById<ImageButton>(R.id.btn_save)
        val btnBack = findViewById<ImageButton>(R.id.btn_back)

        // 1. REVISAR SI ESTAMOS EDITANDO
        // Si la actividad anterior nos pasó una nota, la cargamos
        if (intent.hasExtra("note_data")) {
            noteToEdit = intent.getSerializableExtra("note_data") as Note
            etTitle.setText(noteToEdit?.title)
            etContent.setText(noteToEdit?.content)
            tvDate.text = "Editando: " + noteToEdit?.date
        } else {
            // Si es nueva, ponemos la fecha de hoy
            val currentDate = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date())
            tvDate.text = currentDate
        }

        // 2. BOTÓN VOLVER
        btnBack.setOnClickListener { finish() }

        // 3. BOTÓN GUARDAR
        btnSave.setOnClickListener {
            saveNote()
        }
    }

    private fun saveNote() {
        val title = etTitle.text.toString().trim()
        val content = etContent.text.toString().trim()

        if (title.isEmpty() && content.isEmpty()) {
            Toast.makeText(this, "La nota está vacía", Toast.LENGTH_SHORT).show()
            return
        }

        val currentDate = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())

        // Usamos Corrutinas para no bloquear la pantalla al guardar
        CoroutineScope(Dispatchers.IO).launch {
            if (noteToEdit == null) {
                // INSERTAR NUEVA
                val newNote = Note(title = title, content = content, date = currentDate)
                db.notesDao().insert(newNote)
            } else {
                // ACTUALIZAR EXISTENTE
                noteToEdit?.apply {
                    this.title = title
                    this.content = content
                    this.date = currentDate
                }
                db.notesDao().update(noteToEdit!!)
            }

            // Volver al hilo principal para cerrar la pantalla
            withContext(Dispatchers.Main) {
                Toast.makeText(this@NoteEditorActivity, "Nota guardada", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
}