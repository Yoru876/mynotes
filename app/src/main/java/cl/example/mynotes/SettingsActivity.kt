package cl.example.mynotes

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.widget.ImageButton
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.materialswitch.MaterialSwitch

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // --- INICIO: LÓGICA DE SISTEMA (Edge-to-Edge) ---

        // 1. Decirle a la ventana que invada las barras de sistema
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // 2. Ajustar iconos de barra de estado según tema (Oscuro/Claro)
        val isDarkTheme = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = !isDarkTheme

        // 3. Aplicar Padding automático para no quedar tapado
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.settings_root)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // --- FIN LÓGICA DE SISTEMA ---

        // Referencias UI
        val btnBack = findViewById<ImageButton>(R.id.btn_back_settings)
        val switchImages = findViewById<MaterialSwitch>(R.id.switch_show_images)
        val radioGroupSize = findViewById<RadioGroup>(R.id.radio_group_size)
        val rbNormal = findViewById<RadioButton>(R.id.rb_size_normal)
        val rbLarge = findViewById<RadioButton>(R.id.rb_size_large)

        // Cargar Preferencias
        val prefs = getSharedPreferences("MyNotesSettings", Context.MODE_PRIVATE)

        switchImages.isChecked = prefs.getBoolean("show_images", true)

        val columns = prefs.getInt("grid_columns", 2)
        if (columns == 1) rbLarge.isChecked = true else rbNormal.isChecked = true

        // Listeners
        btnBack.setOnClickListener { finish() }

        switchImages.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("show_images", isChecked).apply()
        }

        radioGroupSize.setOnCheckedChangeListener { _, checkedId ->
            val newColumns = if (checkedId == R.id.rb_size_large) 1 else 2
            prefs.edit().putInt("grid_columns", newColumns).apply()
        }
    }
}