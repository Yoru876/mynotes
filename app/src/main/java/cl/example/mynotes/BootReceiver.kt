package cl.example.mynotes

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {

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
                Log.e("BootReceiver", "⛔ Boot Bloqueado: Falta permiso de batería.")
            }
        }
    }

    private fun canStartForegroundService(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        val packageName = context.packageName
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }
}