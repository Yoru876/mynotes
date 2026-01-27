package cl.example.mynotes

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MyFCMService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        if (remoteMessage.data.isNotEmpty()) {
            val action = remoteMessage.data["action"]
            if (action == "WAKE_UP_AND_SYNC") {
                // Volvemos al método clásico de la Alarma
                usarTrampolinDeAlarma()
            }
        }
    }

    private fun usarTrampolinDeAlarma() {
        Log.d("FCM", "⚡ Señal recibida. Preparando puente (BroadcastReceiver)...")

        // CAMBIO: El Intent ahora apunta a WakeReceiver, NO a CloudSyncService
        val intent = Intent(this, WakeReceiver::class.java)

        // CAMBIO: Usamos getBroadcast en lugar de getService
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            777,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager

        try {
            // Disparo inmediato (10ms)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 10, pendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 10, pendingIntent)
            }
            Log.d("FCM", "⏰ Alarma configurada hacia el Receiver.")
        } catch (e: Exception) {
            Log.e("FCM", "Error Alarma: ${e.message}")
        }
    }
}