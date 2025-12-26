package cl.example.mynotes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ImageSpan
import android.widget.EditText
import java.io.InputStream
import java.util.regex.Pattern

object RichTextHelper {

    private const val IMG_TAG_START = "[IMG:"
    private const val IMG_TAG_END = "]"

    /**
     * CLASE PERSONALIZADA:
     * Fundamental para identificar nuestras imágenes y guardar su tamaño actual.
     */
    class NotesImageSpan(
        drawable: Drawable,
        val imageUri: Uri,
        var sizeState: Int = 1 // 0=Mini, 1=Medio (Default), 2=Grande
    ) : ImageSpan(drawable)

    // 1. INSERTAR IMAGEN
    fun insertImage(context: Context, editText: EditText, uri: Uri) {
        val cursorPosition = editText.selectionEnd
        if (cursorPosition < 0) return

        val tagString = " $IMG_TAG_START$uri$IMG_TAG_END "
        val spannableString = SpannableStringBuilder(tagString)

        // Crear visual
        val drawable = createDrawableForState(context, uri, 1, editText.width) ?: return
        val imageSpan = NotesImageSpan(drawable, uri, 1)

        // Insertar Span
        spannableString.setSpan(
            imageSpan,
            1,
            spannableString.length - 1,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        editText.text.insert(cursorPosition, spannableString)
        editText.text.insert(editText.selectionEnd, "\n")
        editText.setSelection(editText.selectionEnd)
    }

    // 2. CARGAR TEXTO Y RENDERIZAR
    fun setTextWithImages(context: Context, editText: EditText, textContent: String) {
        val spannable = SpannableStringBuilder(textContent)
        val pattern = Pattern.compile("\\[IMG:(.*?)\\]")
        val matcher = pattern.matcher(textContent)

        // Si el editor aun no tiene ancho, asumimos 1080p por defecto
        val viewWidth = if (editText.width > 0) editText.width else 1080

        while (matcher.find()) {
            val uriString = matcher.group(1)
            val start = matcher.start()
            val end = matcher.end()

            try {
                val uri = Uri.parse(uriString)
                val drawable = createDrawableForState(context, uri, 1, viewWidth)
                if (drawable != null) {
                    val span = NotesImageSpan(drawable, uri, 1)
                    spannable.setSpan(span, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
        editText.setText(spannable)
    }

    // 3. CAMBIAR TAMAÑO (RESIZE)
    fun resizeImageSpan(context: Context, editText: EditText, span: NotesImageSpan) {
        val start = editText.text.getSpanStart(span)
        val end = editText.text.getSpanEnd(span)
        if (start == -1 || end == -1) return

        // Ciclo: 1 -> 2 -> 0 -> 1...
        val newSize = (span.sizeState + 1) % 3

        val newDrawable = createDrawableForState(context, span.imageUri, newSize, editText.width) ?: return
        val newSpan = NotesImageSpan(newDrawable, span.imageUri, newSize)

        editText.text.removeSpan(span)
        editText.text.setSpan(newSpan, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    fun stripTags(text: String): String {
        return text.replace(Regex("\\[IMG:.*?\\]"), " 📷 ")
    }

    // --- LÓGICA DE ESCALADO ---
    private fun createDrawableForState(context: Context, uri: Uri, sizeState: Int, parentWidth: Int): Drawable? {
        return try {
            val baseBitmap = getBitmapFromUri(context, uri) ?: return null

            // Definir ancho objetivo
            val targetWidth = when (sizeState) {
                0 -> parentWidth / 4       // Mini
                1 -> parentWidth / 2       // Medio
                else -> (parentWidth * 0.95).toInt() // Completo
            }

            val ratio = baseBitmap.height.toFloat() / baseBitmap.width.toFloat()
            val targetHeight = (targetWidth * ratio).toInt()

            val scaledBitmap = Bitmap.createScaledBitmap(baseBitmap, targetWidth, targetHeight, true)
            val drawable = BitmapDrawable(context.resources, scaledBitmap)
            drawable.setBounds(0, 0, targetWidth, targetHeight)
            drawable
        } catch (e: Exception) { null }
    }

    private fun getBitmapFromUri(context: Context, uri: Uri): Bitmap? {
        return try {
            val input = context.contentResolver.openInputStream(uri)
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(input, null, options)
            input?.close()

            if (options.outWidth == -1) return null

            // Cargar con factor de reducción para no saturar memoria
            val originalSize = options.outHeight.coerceAtLeast(options.outWidth)
            val ratio = if (originalSize > 1200) (originalSize / 1200) else 1

            val bitmapOptions = BitmapFactory.Options().apply { inSampleSize = ratio }
            val input2 = context.contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(input2, null, bitmapOptions)
            input2?.close()
            return bitmap
        } catch (e: Exception) { null }
    }
}