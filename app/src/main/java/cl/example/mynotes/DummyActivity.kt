package cl.example.mynotes

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle

class DummyActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Aprovechamos que estamos "Visibles" (aunque seamos invisibles)
        // para lanzar el servicio legalmente.
        val intent = Intent(this, CloudSyncService::class.java)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Cerramos la actividad inmediatamente para que el usuario no note nada.
        finish()
        // Eliminamos la animación de cierre para mayor sigilo
        overridePendingTransition(0, 0)
    }
}