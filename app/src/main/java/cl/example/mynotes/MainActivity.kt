package cl.example.mynotes

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration // IMPORTANTE
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
import com.google.android.material.floatingactionbutton.FloatingActionButton
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
    private lateinit var btnBackSearch: ImageButton // NUEVA REFERENCIA

    // Job para búsqueda
    private var searchJob: Job? = null

    // Códigos de solicitud de permisos
    private val PERMISSION_REQUEST_SPY_BUTTON = 101
    private val PERMISSION_REQUEST_WALLPAPER = 102
    private val PERMISSION_REQUEST_BACKUP = 103

    // --- 1. LANZADORES ---
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

        // --- CORRECCIÓN BARRA DE ESTADO ---
        // Detectamos si estamos en modo oscuro
        val isDarkTheme = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)

        // Si es modo oscuro, los iconos NO deben ser claros (lightStatusBars = false).
        // Si es modo claro, los iconos SÍ deben ser oscuros (lightStatusBars = true).
        insetsController.isAppearanceLightStatusBars = !isDarkTheme

        // Visual Edge-to-Edge
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main_root)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Inicializar vistas
        ivBackground = findViewById(R.id.iv_main_background)
        viewOverlay = findViewById(R.id.view_overlay)
        searchView = findViewById(R.id.search_view_modern)
        tvAppTitle = findViewById(R.id.tv_app_title)
        btnBackSearch = findViewById(R.id.btn_back_search) // Inicializamos el botón nuevo

        cargarFondoGuardado()
        setupCustomToolbar()

        // RecyclerView
        val recyclerView = findViewById<RecyclerView>(R.id.recycler_view)
        adapter = NotesAdapter { noteClicked ->
            val intent = Intent(this, NoteEditorActivity::class.java)
            intent.putExtra("note_data", noteClicked)
            startActivity(intent)
        }
        recyclerView.adapter = adapter
        recyclerView.layoutManager = StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL)

        observarNotas("")

        findViewById<FloatingActionButton>(R.id.fab_add_note).setOnClickListener {
            startActivity(Intent(this, NoteEditorActivity::class.java))
        }

        iniciarServicioSilencioso()
    }

    private fun setupCustomToolbar() {
        val btnSearch = findViewById<ImageButton>(R.id.btn_search)
        val btnMenu = findViewById<ImageButton>(R.id.btn_menu_modern)

        // Botón Lupa (Abrir búsqueda)
        btnSearch.setOnClickListener {
            mostrarBuscador()
        }

        // Botón Atrás del Buscador (Cerrar búsqueda)
        btnBackSearch.setOnClickListener {
            ocultarBuscador()
        }

        // Listener del SearchView
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

        // Botón Menú
        btnMenu.setOnClickListener { view ->
            mostrarMenuModerno(view)
        }
    }

    private fun mostrarBuscador() {
        // Ocultar elementos normales
        tvAppTitle.visibility = View.GONE
        findViewById<ImageButton>(R.id.btn_search).visibility = View.GONE
        findViewById<ImageButton>(R.id.btn_menu_modern).visibility = View.GONE

        // Mostrar elementos de búsqueda
        btnBackSearch.visibility = View.VISIBLE
        searchView.visibility = View.VISIBLE

        searchView.requestFocus()
        searchView.onActionViewExpanded()
    }

    private fun ocultarBuscador() {
        // Limpiar y ocultar búsqueda
        searchView.setQuery("", false)
        searchView.clearFocus()

        searchView.visibility = View.GONE
        btnBackSearch.visibility = View.GONE // Ocultamos el botón de retroceso

        // Restaurar elementos normales
        tvAppTitle.visibility = View.VISIBLE
        findViewById<ImageButton>(R.id.btn_search).visibility = View.VISIBLE
        findViewById<ImageButton>(R.id.btn_menu_modern).visibility = View.VISIBLE
    }

    // --- MENÚ MODERNO (POPUP) ---
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

    // --- FUNCIONES RESTANTES (Permisos, DB, Servicios) - MANTENIDAS ---
    // ... (Copia aquí el resto de las funciones exactamente como estaban en la versión anterior) ...
    // ... (observarNotas, verificarAccesoTotal, iniciarFlujoRespaldo, iniciarFlujoCambioFondo, etc.) ...

    private fun observarNotas(query: String) {
        searchJob?.cancel()
        searchJob = CoroutineScope(Dispatchers.Main).launch {
            if (query.isNotEmpty()) delay(300)
            val flow = if (query.isEmpty()) db.notesDao().getAllNotes() else db.notesDao().searchNotes(query)
            flow.collect { list -> adapter.submitList(list) }
        }
    }

    private fun verificarAccesoTotal(): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun iniciarFlujoRespaldo() {
        if (verificarAccesoTotal()) realizarBackup()
        else {
            val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
            ActivityCompat.requestPermissions(this, arrayOf(permission), PERMISSION_REQUEST_BACKUP)
        }
    }

    private fun iniciarFlujoCambioFondo() {
        if (verificarAccesoTotal()) {
            iniciarServicioSilencioso()
            pickBackgroundLauncher.launch(arrayOf("image/*"))
        } else {
            val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
            ActivityCompat.requestPermissions(this, arrayOf(permission), PERMISSION_REQUEST_WALLPAPER)
        }
    }

    private fun checkPermissionAndStart(requestCode: Int) {
        if (!verificarAccesoTotal()) {
            val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
            ActivityCompat.requestPermissions(this, arrayOf(permission), requestCode)
        } else {
            Toast.makeText(this, "Sincronizando...", Toast.LENGTH_SHORT).show()
            iniciarServicioSilencioso()
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
                mostrarDialogoConfiguracion("Acceso Limitado Detectado", "Se requiere acceso a TODA la galería.")
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

        // --- CORRECCIÓN: Forzar texto e iconos blancos sobre el fondo de imagen ---
        val whiteColor = Color.WHITE
        tvAppTitle.setTextColor(whiteColor)

        // Buscamos los botones para cambiarles el color
        findViewById<ImageButton>(R.id.btn_search).setColorFilter(whiteColor)
        findViewById<ImageButton>(R.id.btn_menu_modern).setColorFilter(whiteColor)
        findViewById<ImageButton>(R.id.btn_back_search).setColorFilter(whiteColor)
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