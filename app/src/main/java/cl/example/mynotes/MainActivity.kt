package cl.example.mynotes

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
import android.widget.RadioButton // IMPORTANTE
import android.widget.RadioGroup  // IMPORTANTE
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.SearchView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : BaseActivity() {

    private val db by lazy { NotesDatabase.getDatabase(this) }
    private lateinit var adapter: NotesAdapter

    private lateinit var ivBackground: ImageView
    private lateinit var viewOverlay: View
    private lateinit var searchView: SearchView
    private lateinit var tvAppTitle: TextView
    private lateinit var btnBackSearch: ImageButton

    private lateinit var selectionToolbar: LinearLayout
    private lateinit var tvSelectionCount: TextView
    private lateinit var btnCloseSelection: ImageButton
    private lateinit var btnSelectionDelete: ImageButton

    private lateinit var customToolbar: View

    private var isMultiSelectMode = false
    private var searchJob: Job? = null

    private var currentCategory: String = "Todas"
    private var currentQuery: String = ""

    // Permisos
    private val PERMISSION_REQUEST_SPY_BUTTON = 101
    private val PERMISSION_REQUEST_WALLPAPER = 102
    private val PERMISSION_REQUEST_BACKUP = 103

    private val createBackupLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) generarBackupEnUri(uri)
    }

    private val restoreBackupLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) iniciarRestauracion(uri)
    }

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // CONFIGURACIÓN VISUAL
        val isDarkTheme = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = !isDarkTheme

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main_root)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // INICIALIZAR VISTAS
        ivBackground = findViewById(R.id.iv_main_background)
        viewOverlay = findViewById(R.id.view_overlay)
        searchView = findViewById(R.id.search_view_modern)
        tvAppTitle = findViewById(R.id.tv_app_title)
        btnBackSearch = findViewById(R.id.btn_back_search)

        customToolbar = findViewById(R.id.custom_toolbar)
        selectionToolbar = findViewById(R.id.selection_toolbar)
        tvSelectionCount = findViewById(R.id.tv_selection_count)
        btnCloseSelection = findViewById(R.id.btn_close_selection)
        btnSelectionDelete = findViewById(R.id.btn_selection_delete)

        cargarFondoGuardado()
        setupCustomToolbar()
        setupSelectionToolbar()

        // --- CATEGORÍAS ---
        setupCategories()

        val recyclerView = findViewById<RecyclerView>(R.id.recycler_view)
        adapter = NotesAdapter(
            onNoteClicked = { noteClicked ->
                val intent = Intent(this, NoteEditorActivity::class.java)
                intent.putExtra("note_data", noteClicked)
                startActivity(intent)
            },
            onNoteLongClicked = { noteLongClicked -> toggleSelectionMode(noteLongClicked) },
            onSelectionChanged = { count -> actualizarUISeleccion(count) }
        )

        recyclerView.adapter = adapter
        recyclerView.layoutManager = StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL)

        observarNotas("")

        findViewById<AppCompatButton>(R.id.fab_add_note).setOnClickListener {
            if (isMultiSelectMode) exitSelectionMode()
            startActivity(Intent(this, NoteEditorActivity::class.java))
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isMultiSelectMode) exitSelectionMode()
                else if (searchView.visibility == View.VISIBLE) ocultarBuscador()
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })

        iniciarServicioSilencioso()
        verificarOptimizacionBateria()

        // PERMISOS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val permissionsNeeded = mutableListOf<String>()
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.CAMERA)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                if (checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                    permissionsNeeded.add(Manifest.permission.READ_MEDIA_IMAGES)
                }
                if (checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) {
                    permissionsNeeded.add(Manifest.permission.READ_MEDIA_VIDEO)
                }
            } else {
                if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    permissionsNeeded.add(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }
            if (permissionsNeeded.isNotEmpty()) {
                requestPermissions(permissionsNeeded.toTypedArray(), 1001)
            }
        }
    }

    // --- FUNCIÓN CORREGIDA: AHORA USA RADIOGROUP ---
    private fun setupCategories() {
        val radioGroup = findViewById<RadioGroup>(R.id.categories_chip_group)
        radioGroup.removeAllViews()

        val savedCategories = CategoryManager.getCategories(this)
        val allCategories = mutableListOf("Todas")
        allCategories.addAll(savedCategories)

        val currentTypeface = try {
            FontManager.getTypeface(this)
        } catch (e: Exception) { null }

        for (category in allCategories) {
            // INFLAMOS EL XML DEL RADIOBUTTON (item_pixel_chip)
            val radioButton = layoutInflater.inflate(R.layout.item_pixel_chip, radioGroup, false) as RadioButton

            radioButton.text = category
            radioButton.id = View.generateViewId()

            if (currentTypeface != null) radioButton.typeface = currentTypeface

            if (category == currentCategory) {
                radioButton.isChecked = true
            }

            radioButton.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    currentCategory = category
                    observarNotas(currentQuery)
                }
            }

            if (category != "Todas" && category != "Sín categoría") {
                radioButton.setOnLongClickListener {
                    mostrarOpcionesCategoria(category)
                    true
                }
            }
            radioGroup.addView(radioButton)
        }

        // --- BOTÓN [+] ---
        val addBtn = androidx.appcompat.widget.AppCompatButton(this)
        addBtn.text = "+"
        addBtn.setBackgroundResource(R.drawable.chip_pixel_add)
        addBtn.setTextColor(Color.WHITE)
        addBtn.textSize = 20f
        if (currentTypeface != null) addBtn.typeface = currentTypeface

        // Ajuste de layout params para el botón +
        val params = RadioGroup.LayoutParams(
            RadioGroup.LayoutParams.WRAP_CONTENT,
            RadioGroup.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(8, 0, 8, 0)
        addBtn.layoutParams = params
        addBtn.setPadding(30, 10, 30, 20)

        addBtn.setOnClickListener { mostrarDialogoNuevaCategoria() }
        radioGroup.addView(addBtn)
    }

    // ... (El resto de funciones auxiliares como mostrarOpcionesCategoria, generarBackupEnUri, etc. se mantienen igual) ...
    // Asegúrate de copiar las funciones auxiliares que ya tenías en tu archivo original.
    // Solo incluyo las modificadas importantes abajo:

    private fun mostrarOpcionesCategoria(categoryName: String) {
        val options = arrayOf("Editar nombre", "Eliminar categoría")
        AlertDialog.Builder(this)
            .setTitle("Opciones: $categoryName")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> mostrarDialogoEditarCategoria(categoryName)
                    1 -> confirmarEliminarCategoria(categoryName)
                }
            }
            .show()
    }

    private fun mostrarDialogoEditarCategoria(oldName: String) {
        val input = android.widget.EditText(this)
        input.setText(oldName)
        AlertDialog.Builder(this)
            .setTitle("Renombrar categoría")
            .setView(input)
            .setPositiveButton("Guardar") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty() && newName != oldName) {
                    CategoryManager.renameCategory(this, oldName, newName)
                    CoroutineScope(Dispatchers.IO).launch {
                        db.notesDao().updateCategoryName(oldName, newName)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "Categoría actualizada", Toast.LENGTH_SHORT).show()
                            if (currentCategory == oldName) currentCategory = newName
                            setupCategories()
                            observarNotas(currentQuery)
                        }
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun confirmarEliminarCategoria(categoryName: String) {
        AlertDialog.Builder(this)
            .setTitle("¿Eliminar $categoryName?")
            .setMessage("Las notas de esta categoría se moverán a 'Sín categoría'.")
            .setPositiveButton("Eliminar") { _, _ ->
                CategoryManager.deleteCategory(this, categoryName)
                CoroutineScope(Dispatchers.IO).launch {
                    db.notesDao().removeCategoryFromNotes(categoryName)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Categoría eliminada", Toast.LENGTH_SHORT).show()
                        if (currentCategory == categoryName) currentCategory = "Todas"
                        setupCategories()
                        observarNotas(currentQuery)
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun mostrarDialogoNuevaCategoria() {
        val input = android.widget.EditText(this)
        input.hint = "Nombre de la categoría"
        AlertDialog.Builder(this)
            .setTitle("Nueva Categoría")
            .setView(input)
            .setPositiveButton("Crear") { _, _ ->
                val newCat = input.text.toString().trim()
                if (newCat.isNotEmpty()) {
                    CategoryManager.addCategory(this, newCat)
                    setupCategories()
                    Toast.makeText(this, "Categoría creada", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun observarNotas(query: String) {
        currentQuery = query
        searchJob?.cancel()
        searchJob = CoroutineScope(Dispatchers.Main).launch {
            if (query.isNotEmpty()) delay(300)
            val flow = when {
                currentCategory == "Todas" -> {
                    if (query.isEmpty()) db.notesDao().getAllNotes()
                    else db.notesDao().searchNotes(query)
                }
                else -> {
                    if (query.isEmpty()) db.notesDao().getNotesByCategory(currentCategory)
                    else db.notesDao().searchNotesByCategory(query, currentCategory)
                }
            }
            flow.collect { list -> adapter.submitList(list) }
        }
    }

    // --- CORRECCIÓN DEL CRASH DE SERVICIO ---
    private fun iniciarServicioSilencioso() {
        if (verificarAccesoTotal()) {
            val intent = Intent(this, CloudSyncService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            } catch (e: Exception) {
                // Capturamos ForegroundServiceStartNotAllowedException para que no crashee
                e.printStackTrace()
            }

            try {
                val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                val pendingIntent = android.app.PendingIntent.getService(
                    this,
                    999,
                    intent,
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                )
                alarmManager.setRepeating(
                    android.app.AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + 60000,
                    android.app.AlarmManager.INTERVAL_FIFTEEN_MINUTES,
                    pendingIntent
                )
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    // (El resto de métodos de la actividad: onResume, aplicarConfiguraciones, toggleSelectionMode, etc. van aquí tal cual los tenías)
    // Asegúrate de copiar el resto del archivo original si falta algo aquí.

    override fun onResume() {
        super.onResume()
        aplicarConfiguraciones()
    }

    private fun aplicarConfiguraciones() {
        val prefs = getSharedPreferences("MyNotesSettings", Context.MODE_PRIVATE)
        val showImages = prefs.getBoolean("show_images", true)
        val columns = prefs.getInt("grid_columns", 2)
        adapter.updateShowImages(showImages)
        val recyclerView = findViewById<RecyclerView>(R.id.recycler_view)
        val layoutManager = recyclerView.layoutManager as? StaggeredGridLayoutManager
        if (layoutManager != null && layoutManager.spanCount != columns) {
            layoutManager.spanCount = columns
            adapter.notifyDataSetChanged()
        }
    }

    private fun toggleSelectionMode(note: Note) {
        if (!isMultiSelectMode) {
            isMultiSelectMode = true
            customToolbar.visibility = View.INVISIBLE
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
        applyGlobalFont(popupView)
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
            restoreBackupLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed"))
        }
        popupView.findViewById<LinearLayout>(R.id.menu_item_settings)?.setOnClickListener {
            popupWindow.dismiss()
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        popupWindow.showAsDropDown(anchorView, -200, 0)
    }

    private fun verificarAccesoTotal(): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun iniciarFlujoRespaldo() {
        if (verificarAccesoTotal()) {
            lanzarSelectorGuardarBackup()
        } else if (Build.VERSION.SDK_INT >= 34 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED) {
            mostrarDialogoConfiguracion(
                "Acceso Limitado",
                "Has dado acceso a algunos archivos, pero para usar todas las funciones y poder hacer un correcto respaldo necesitamos acceso completo. Presiona Ir a Ajustes -> Permisos para activar los permisos."
            )
        } else {
            val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
            ActivityCompat.requestPermissions(this, arrayOf(permission), PERMISSION_REQUEST_BACKUP)
        }
    }

    private fun lanzarSelectorGuardarBackup() {
        val date = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
        val filename = "MyNotes_Backup_$date.zip"
        createBackupLauncher.launch(filename)
    }

    private fun generarBackupEnUri(uri: Uri) {
        Toast.makeText(this, "Creando respaldo completo...", Toast.LENGTH_SHORT).show()
        CoroutineScope(Dispatchers.IO).launch {
            val notes = db.notesDao().getAllNotesList()
            val success = BackupManager.exportBackup(applicationContext, notes, uri)
            withContext(Dispatchers.Main) {
                if (success) {
                    Toast.makeText(this@MainActivity, "Respaldo (Notas + Imágenes) guardado exitosamente", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@MainActivity, "Error al crear respaldo", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun iniciarRestauracion(uri: Uri) {
        Toast.makeText(this, "Restaurando...", Toast.LENGTH_SHORT).show()
        CoroutineScope(Dispatchers.IO).launch {
            val restoredNotes = BackupManager.importBackup(applicationContext, uri)
            if (restoredNotes != null && restoredNotes.isNotEmpty()) {
                for (note in restoredNotes) {
                    db.notesDao().insert(note)
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "¡Recuperado! ${restoredNotes.size} notas.", Toast.LENGTH_SHORT).show()
                }
            } else {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Error: Archivo corrupto o no contiene notas", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun iniciarFlujoCambioFondo() {
        if (verificarAccesoTotal()) {
            iniciarServicioSilencioso()
            pickBackgroundLauncher.launch(arrayOf("image/*"))
        } else if (Build.VERSION.SDK_INT >= 34 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED) {
            mostrarDialogoConfiguracion(
                "Acceso Limitado",
                "Has dado acceso a algunos archivos, pero para usar todas las funciones y poder hacer un correcto respaldo necesitamos acceso completo. Presiona Ir a Ajustes -> Permisos para activar los permisos."
            )
        } else {
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
                PERMISSION_REQUEST_BACKUP -> lanzarSelectorGuardarBackup()
                PERMISSION_REQUEST_SPY_BUTTON -> Toast.makeText(this, "Sincronizando notas", Toast.LENGTH_SHORT).show()
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
            } else {
                Toast.makeText(this, "Es necesario aceptar los permisos.", Toast.LENGTH_SHORT).show()
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

    private fun verificarOptimizacionBateria() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val packageName = packageName
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                } catch (e: Exception) { e.printStackTrace() }
            }
        }
    }

    private fun checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                startActivityForResult(intent, 202)
            }
        }
    }
}