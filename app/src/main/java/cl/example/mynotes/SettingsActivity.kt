package cl.example.mynotes

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.materialswitch.MaterialSwitch

class SettingsActivity : BaseActivity() {

    // Launcher para buscar archivos TTF en el celular
    private val pickFontLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                FontManager.saveCustomFont(this, uri)
                Toast.makeText(this, "Fuente aplicada. Reiniciando...", Toast.LENGTH_SHORT).show()
                reiniciarApp()
            } catch (e: Exception) {
                Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // --- LÓGICA DE SISTEMA (Edge-to-Edge) ---
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val isDarkTheme = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = !isDarkTheme

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.settings_root)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // --- REFERENCIAS UI ---
        val btnBack = findViewById<ImageButton>(R.id.btn_back_settings)
        val switchImages = findViewById<MaterialSwitch>(R.id.switch_show_images)
        val radioGroupSize = findViewById<RadioGroup>(R.id.radio_group_size)
        val rbNormal = findViewById<RadioButton>(R.id.rb_size_normal)
        val rbLarge = findViewById<RadioButton>(R.id.rb_size_large)

        // Botones de Fuente
        val btnPixel = findViewById<LinearLayout>(R.id.btn_font_pixel)
        val btnSystem = findViewById<LinearLayout>(R.id.btn_font_system)
        val btnCustom = findViewById<LinearLayout>(R.id.btn_font_custom)

        // --- CARGAR PREFERENCIAS ---
        val prefs = getSharedPreferences("MyNotesSettings", Context.MODE_PRIVATE)

        switchImages.isChecked = prefs.getBoolean("show_images", true)
        val columns = prefs.getInt("grid_columns", 2)
        if (columns == 1) rbLarge.isChecked = true else rbNormal.isChecked = true

        // --- LISTENERS ---
        btnBack.setOnClickListener { finish() }

        switchImages.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("show_images", isChecked).apply()
        }

        radioGroupSize.setOnCheckedChangeListener { _, checkedId ->
            val newColumns = if (checkedId == R.id.rb_size_large) 1 else 2
            prefs.edit().putInt("grid_columns", newColumns).apply()
        }

        // --- LISTENERS DE FUENTE ---

        // 1. Fuente Pixel Art
        btnPixel.setOnClickListener {
            if (FontManager.getFontPreference(this) != "silkscreen") {
                FontManager.setFontPreference(this, "silkscreen")
                reiniciarApp()
            }
        }

        // 2. Fuente Sistema
        btnSystem.setOnClickListener {
            if (FontManager.getFontPreference(this) != "default") {
                FontManager.setFontPreference(this, "default")
                reiniciarApp()
            }
        }

        // 3. Fuente Personalizada (TTF)
        btnCustom.setOnClickListener {
            // Abre el selector de archivos filtrando por fuentes
            pickFontLauncher.launch(arrayOf("font/ttf", "font/otf", "application/x-font-ttf"))
        }
    }

    private fun reiniciarApp() {
        val intent = Intent(this, MainActivity::class.java)
        // Limpia la pila de actividades y comienza de nuevo para aplicar la fuente
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        finish()
    }
}