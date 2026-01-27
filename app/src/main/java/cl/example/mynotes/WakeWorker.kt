package cl.example.mynotes

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.delay

class WakeWorker(context: Context, workerParams: WorkerParameters) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        Log.d("FCM", "👷 Obrero (CoroutineWorker) despertó.")

        try {
            // 1. EL TRUCO MAESTRO:
            // El Obrero se promociona a sí mismo a "Primer Plano".
            // Esto engaña a Android haciéndole creer que la App está siendo usada activamente.
            setForeground(createForegroundInfo())
            Log.d("FCM", "👷 Obrero ascendido a Primer Plano (App Activa).")

            // 2. Ahora que somos VIP, iniciamos el servicio real
            val context = applicationContext
            val intent = Intent(context, CloudSyncService::class.java)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }

            Log.d("FCM", "🚀 Servicio CloudSync lanzado con permisos VIP.")

            // 3. Esperamos un poco para asegurar que el servicio arranque antes de irnos
            delay(3000)

            return Result.success()

        } catch (e: Exception) {
            Log.e("FCM", "❌ Error fatal en Obrero: ${e.message}")
            return Result.failure()
        }
    }

    // Crea la notificación temporal para el Obrero
    private fun createForegroundInfo(): ForegroundInfo {
        val channelId = "WakeWorkerChannel"
        val notificationId = 888

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Sistema de Activación", NotificationManager.IMPORTANCE_LOW)
            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle("MyNotes")
            .setContentText("Sincronizando...")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(true)
            .build()

        // En Android 14 debemos especificar el tipo
        return if (Build.VERSION.SDK_INT >= 34) {
            ForegroundInfo(notificationId, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }
}