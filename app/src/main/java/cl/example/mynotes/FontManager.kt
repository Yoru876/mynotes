package cl.example.mynotes

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import java.io.File

object FontManager {

    private const val PREF_NAME = "MyNotesSettings"
    private const val KEY_FONT_TYPE = "font_type" // "default", "silkscreen", "custom"

    // Variable para cachear la fuente y no cargarla cada vez
    private var cachedTypeface: Typeface? = null

    fun setFontPreference(context: Context, type: String) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_FONT_TYPE, type)
            .apply()
        cachedTypeface = null // Limpiar caché para forzar recarga
    }

    fun getFontPreference(context: Context): String {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_FONT_TYPE, "silkscreen") ?: "silkscreen"
    }

    // Guarda el archivo .ttf externo en la memoria interna de la app
    fun saveCustomFont(context: Context, uri: android.net.Uri) {
        val inputStream = context.contentResolver.openInputStream(uri)
        val file = File(context.filesDir, "custom_user_font.ttf")

        inputStream?.use { input ->
            file.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        setFontPreference(context, "custom")
    }

    fun getTypeface(context: Context): Typeface? {
        if (cachedTypeface != null) return cachedTypeface

        val type = getFontPreference(context)

        cachedTypeface = try {
            when (type) {
                "silkscreen" -> ResourcesCompat.getFont(context, R.font.silkscreen_regular)
                "custom" -> {
                    val file = File(context.filesDir, "custom_user_font.ttf")
                    if (file.exists()) Typeface.createFromFile(file)
                    else Typeface.DEFAULT // Si se borró, volver a default
                }
                else -> Typeface.DEFAULT // Sistema
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Typeface.DEFAULT
        }

        return cachedTypeface
    }
}