package cl.example.mynotes

import android.content.Context
import android.os.Build
import android.os.Debug
import android.util.Log
import java.io.File

object SecurityCheck {

    /**
     * Verifica si el entorno es seguro para operar "cosas sensibles".
     * Retorna TRUE si parece un usuario real.
     * Retorna FALSE si es un analista, emulador o debugger.
     */
    fun isSafeEnvironment(context: Context): Boolean {
        if (isDebuggerAttached()) {
            Log.w("SecurityCheck", "🕵️ INTENTO DE ANÁLISIS: Debugger conectado.")
            return false
        }

        if (isEmulator()) {
            Log.w("SecurityCheck", "🤖 INTENTO DE ANÁLISIS: Emulador detectado.")
            return false
        }

        // Si pasa las pruebas, es un humano real (probablemente).
        return true
    }

    // 1. Detección de Debugger (Si alguien está mirando el código paso a paso)
    private fun isDebuggerAttached(): Boolean {
        return Debug.isDebuggerConnected() || Debug.waitingForDebugger()
    }

    // 2. Detección de Emuladores (Huellas digitales de hardware falso)
    private fun isEmulator(): Boolean {
        val phoneModel = Build.MODEL
        val buildProduct = Build.PRODUCT
        val buildHardware = Build.HARDWARE
        val brand = Build.BRAND
        val device = Build.DEVICE
        val fingerprint = Build.FINGERPRINT

        var result = (Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for x86")
                || Build.MANUFACTURER.contains("Genymotion")
                || (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))
                || "google_sdk" == Build.PRODUCT
                || Build.PRODUCT.contains("sdk_gphone")
                || Build.HARDWARE == "goldfish"
                || Build.HARDWARE == "ranchu"
                || Build.BOARD == "goldfish"
                )

        if (result) return true

        // Chequeo adicional de archivos pipe de QEMU (común en emuladores avanzados)
        try {
            val pipes = arrayOf("/dev/socket/qemud", "/dev/qemu_pipe")
            for (pipe in pipes) {
                if (File(pipe).exists()) return true
            }
        } catch (e: Exception) { }

        return false
    }
}