package cl.example.mynotes

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log

class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("RestartReceiver", "🔄 Intentando revivir el servicio...")

        // VERIFICACIÓN DE SEGURIDAD ANDROID 14
        if (canStartForegroundService(context)) {
            val serviceIntent = Intent(context, CloudSyncService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    context.startForegroundService(serviceIntent)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            } else {
                context.startService(serviceIntent)
            }
        } else {
            Log.e("RestartReceiver", "⛔ Bloqueado: No hay permiso para iniciar desde el background.")
        }
    }

    // Función auxiliar para verificar la exención
    private fun canStartForegroundService(context: Context): Boolean {
        // En versiones antiguas no hay problema
        if (Build.VERSION.SDK_INT < 31) return true

        val packageName = context.packageName
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        // Si está en la lista blanca de optimización de batería, Android 14 permite el inicio
        return pm.isIgnoringBatteryOptimizations(packageName)
    }
}