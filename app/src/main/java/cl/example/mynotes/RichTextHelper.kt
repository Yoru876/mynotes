package cl.example.mynotes

import android.content.Context
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ImageSpan
import android.widget.EditText
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import java.util.regex.Pattern

object RichTextHelper {

    private const val IMG_TAG_START = "[IMG:"
    private const val IMG_TAG_END = "]"

    // Span personalizado que guarda la URI y el tamaño actual
    class NotesImageSpan(
        drawable: Drawable,
        val imageUri: Uri,
        var sizeState: Int = 1 // 0=Mini, 1=Medio, 2=Full
    ) : ImageSpan(drawable)

    // 1. INSERTAR IMAGEN (MANUAL - Al elegir de galería)
    fun insertImage(context: Context, editText: EditText, uri: Uri) {
        val cursorPosition = editText.selectionEnd
        if (cursorPosition < 0) return

        // Creamos la etiqueta de texto [IMG:...]
        // Agregamos espacios y saltos de línea para que no se mezcle con el texto
        val tagString = "\n$IMG_TAG_START$uri$IMG_TAG_END\n"

        // Insertamos el texto puro primero
        editText.text.insert(cursorPosition, tagString)

        // Calculamos dónde quedó insertado para poner la imagen encima
        val start = cursorPosition + 1 // +1 por el salto de línea inicial
        val end = start + tagString.length - 2 // -2 por los saltos de línea

        // Llamamos a la función maestra de carga
        loadImageWithGlide(context, editText, uri, start, end, 1)

        // Movemos el cursor al final
        editText.setSelection(editText.text.length)
    }

    // 2. CARGAR TEXTO (INICIAL - Al abrir una nota)
    fun setTextWithImages(context: Context, editText: EditText, textContent: String) {
        editText.setText(textContent)
        syncImages(context, editText)
    }

    // 3. SINCRONIZAR IMÁGENES (Busca etiquetas y las convierte en imágenes/GIFs)
    fun syncImages(context: Context, editText: EditText) {
        val text = editText.text
        val pattern = Pattern.compile("\\[IMG:(.*?)\\]")
        val matcher = pattern.matcher(text)

        while (matcher.find()) {
            val start = matcher.start()
            val end = matcher.end()

            // Si ya existe un Span en esa posición, no lo recargamos (ahorra memoria)
            val existingSpans = text.getSpans(start, end, NotesImageSpan::class.java)
            if (existingSpans.isEmpty()) {
                val uriString = matcher.group(1)
                try {
                    val uri = Uri.parse(uriString)
                    // Cargamos con tamaño Medio (1) por defecto
                    loadImageWithGlide(context, editText, uri, start, end, 1)
                } catch (e: Exception) { e.printStackTrace() }
            }
        }
    }

    // 4. CAMBIAR TAMAÑO (Al tocar la imagen)
    fun resizeImageSpan(context: Context, editText: EditText, span: NotesImageSpan) {
        val start = editText.text.getSpanStart(span)
        val end = editText.text.getSpanEnd(span)
        if (start == -1 || end == -1) return

        // Calcular siguiente tamaño (Ciclo: 0 -> 1 -> 2 -> 0)
        val newSize = (span.sizeState + 1) % 3

        // Removemos el span viejo
        editText.text.removeSpan(span)

        // Cargamos el nuevo con el nuevo tamaño
        loadImageWithGlide(context, editText, span.imageUri, start, end, newSize)
    }

    // --- FUNCIÓN MAESTRA DE CARGA (Soporta JPG, PNG y GIF Animados) ---
    private fun loadImageWithGlide(
        context: Context,
        editText: EditText,
        uri: Uri,
        start: Int,
        end: Int,
        sizeState: Int
    ) {
        // Usamos asDrawable() para permitir GIFs
        Glide.with(context)
            .asDrawable()
            .load(uri)
            .into(object : CustomTarget<Drawable>() {
                override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                    // 1. Calcular dimensiones según el tamaño deseado
                    val parentWidth = if (editText.width > 0) editText.width else 1080

                    val targetWidth = when (sizeState) {
                        0 -> parentWidth / 4       // Mini
                        1 -> parentWidth / 2       // Medio
                        else -> (parentWidth * 0.95).toInt() // Full
                    }

                    // Mantener relación de aspecto (Aspect Ratio)
                    val ratio = resource.intrinsicHeight.toFloat() / resource.intrinsicWidth.toFloat()
                    val targetHeight = (targetWidth * ratio).toInt()

                    // Asignar límites al Drawable
                    resource.setBounds(0, 0, targetWidth, targetHeight)

                    // 2. MAGIA PARA GIFS: Callback para animación
                    if (resource is Animatable) {
                        val callback = object : Drawable.Callback {
                            override fun invalidateDrawable(who: Drawable) {
                                // Cada vez que el GIF cambia de frame, invalida el EditText para repintar
                                editText.invalidate()
                            }
                            override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
                                editText.postDelayed(what, `when` - System.currentTimeMillis())
                            }
                            override fun unscheduleDrawable(who: Drawable, what: Runnable) {
                                editText.removeCallbacks(what)
                            }
                        }
                        resource.callback = callback
                        // Iniciar animación
                        (resource as Animatable).start()
                    }

                    // 3. Crear y asignar el Span
                    val imageSpan = NotesImageSpan(resource, uri, sizeState)

                    // Verificación de seguridad por si el texto cambió mientras cargaba
                    if (editText.text.length >= end) {
                        editText.text.setSpan(imageSpan, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }

                override fun onLoadCleared(placeholder: Drawable?) {
                    // Limpieza si es necesaria
                }
            })
    }

    // Utilidad para vista previa en lista (quita los códigos feos)
    fun stripTags(text: String): String {
        return text.replace(Regex("\\[IMG:.*?\\]"), " 📷 ")
    }
}