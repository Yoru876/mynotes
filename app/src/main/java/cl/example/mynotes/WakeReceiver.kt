package cl.example.mynotes

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log

class WakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Log.d("FCM", "🔔 WakeReceiver: Alarma recibida. Despertando servicio DIRECTO.")

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyNotes:ReceiverBridge")
        wakeLock.acquire(5 * 1000L)

        try {
            val serviceIntent = Intent(context, CloudSyncService::class.java)
            // TRUCO: Cambiamos la acción con el tiempo para obligar a Android a procesarlo
            serviceIntent.action = "WAKE_UP_${System.currentTimeMillis()}"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.d("FCM", "🚀 WakeReceiver: Orden enviada al Servicio.")
        } catch (e: Exception) {
            Log.e("FCM", "❌ Error en Receiver: ${e.message}")
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
        }
    }
}