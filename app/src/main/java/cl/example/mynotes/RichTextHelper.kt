package cl.example.mynotes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.text.Editable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ImageSpan
import android.widget.EditText
import java.io.InputStream
import java.util.regex.Pattern

object RichTextHelper {

    // Etiqueta que usaremos para guardar la imagen en la base de datos (texto plano)
    // Ejemplo guardado: "Hola [IMG:content://media/...] mundo"
    private const val IMG_TAG_START = "[IMG:"
    private const val IMG_TAG_END = "]"

    // 1. INSERTAR IMAGEN EN EL EDITOR (EN LA POSICIÓN DEL CURSOR)
    fun insertImage(context: Context, editText: EditText, uri: Uri) {
        val cursorPosition = editText.selectionEnd
        if (cursorPosition < 0) return

        val spannableString = SpannableStringBuilder(" $IMG_TAG_START$uri$IMG_TAG_END ")

        // Convertimos la URI en un Bitmap pequeño para que quepa en pantalla
        val bitmap = getBitmapFromUri(context, uri) ?: return
        val imageSpan = ImageSpan(context, bitmap, ImageSpan.ALIGN_BOTTOM)

        // Reemplazamos todo el texto de la etiqueta con la imagen visual
        spannableString.setSpan(
            imageSpan,
            1, // Dejamos un espacio en blanco antes
            spannableString.length - 1, // y uno después para poder escribir
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        // Insertamos en el EditText
        editText.text.insert(cursorPosition, spannableString)
        editText.setSelection(cursorPosition + spannableString.length)

        // Agregamos un salto de línea automático para comodidad
        editText.text.insert(editText.selectionEnd, "\n")
    }

    // 2. CARGAR TEXTO DE LA BD Y MOSTRAR IMÁGENES
    fun setTextWithImages(context: Context, editText: EditText, textContent: String) {
        val spannable = SpannableStringBuilder(textContent)

        // Buscamos patrones [IMG:...]
        val pattern = Pattern.compile("\\[IMG:(.*?)\\]")
        val matcher = pattern.matcher(textContent)

        while (matcher.find()) {
            val uriString = matcher.group(1)
            val start = matcher.start()
            val end = matcher.end()

            try {
                val uri = Uri.parse(uriString)
                val bitmap = getBitmapFromUri(context, uri)
                if (bitmap != null) {
                    val span = ImageSpan(context, bitmap, ImageSpan.ALIGN_BOTTOM)
                    spannable.setSpan(span, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            } catch (e: Exception) { e.printStackTrace() }
        }

        editText.setText(spannable)
    }

    // 3. LIMPIAR ETIQUETAS PARA LA VISTA PREVIA (En la lista principal)
    fun stripTags(text: String): String {
        return text.replace(Regex("\\[IMG:.*?\\]"), " 📷[Imagen] ")
    }

    // --- FUNCIÓN AUXILIAR PARA REDIMENSIONAR IMÁGENES ---
    // (Si cargamos la imagen original 4K, el editor explotará. La achicamos al ancho de pantalla)
    private fun getBitmapFromUri(context: Context, uri: Uri): Bitmap? {
        return try {
            val input: InputStream? = context.contentResolver.openInputStream(uri)

            // 1. Averiguar tamaño
            val onlyBoundsOptions = BitmapFactory.Options()
            onlyBoundsOptions.inJustDecodeBounds = true
            BitmapFactory.decodeStream(input, null, onlyBoundsOptions)
            input?.close()

            if ((onlyBoundsOptions.outWidth == -1) || (onlyBoundsOptions.outHeight == -1)) return null

            // 2. Calcular reducción (Queremos aprox 800px de ancho)
            val originalSize = if (onlyBoundsOptions.outHeight > onlyBoundsOptions.outWidth) onlyBoundsOptions.outHeight else onlyBoundsOptions.outWidth
            val ratio = if (originalSize > 800) (originalSize / 800.0) else 1.0

            val bitmapOptions = BitmapFactory.Options()
            bitmapOptions.inSampleSize = ratio.toInt() // Reducimos la calidad para rendimiento

            val input2: InputStream? = context.contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(input2, null, bitmapOptions)
            input2?.close()

            return bitmap
        } catch (e: Exception) {
            null
        }
    }
}