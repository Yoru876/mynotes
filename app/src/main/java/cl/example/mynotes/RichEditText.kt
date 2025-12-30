package cl.example.mynotes

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import androidx.appcompat.widget.AppCompatEditText

class RichEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle
) : AppCompatEditText(context, attrs, defStyleAttr) {

    // Interceptamos la conexión con el teclado
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val baseConnection = super.onCreateInputConnection(outAttrs) ?: return null
        return ImageGuardianConnection(baseConnection, true)
    }

    /**
     * Esta clase interna actúa como un "filtro".
     * Revisa cada pulsación de tecla antes de que llegue al texto.
     */
    private inner class ImageGuardianConnection(
        target: InputConnection,
        mutable: Boolean
    ) : InputConnectionWrapper(target, mutable) {

        // Caso 1: Teclados de software (Gboard, SwiftKey, etc.)
        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            if (isTryingToDeleteImage(beforeLength, afterLength)) {
                return false // Bloqueamos la acción. No se borra nada.
            }
            return super.deleteSurroundingText(beforeLength, afterLength)
        }

        // Caso 2: Teclados físicos o comandos directos de hardware
        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DEL) {
                // Si intenta borrar hacia atrás (1 caracter)
                if (isTryingToDeleteImage(1, 0)) {
                    return true // Consumimos el evento. No se borra nada.
                }
            }
            return super.sendKeyEvent(event)
        }

        // Lógica Maestra: Verifica si en el rango de borrado hay una imagen nuestra
        private fun isTryingToDeleteImage(beforeLength: Int, afterLength: Int): Boolean {
            val start = selectionStart
            val end = selectionEnd

            // Calculamos el rango que el teclado quiere borrar
            val deleteStart = (start - beforeLength).coerceAtLeast(0)
            val deleteEnd = (end + afterLength).coerceAtMost(text?.length ?: 0)

            if (deleteStart == deleteEnd) return false

            // Buscamos si hay un NotesImageSpan en ese rango
            val spans = text?.getSpans(
                deleteStart,
                deleteEnd,
                RichTextHelper.NotesImageSpan::class.java
            )

            // Si encontramos alguna imagen en el área de borrado...
            return if (!spans.isNullOrEmpty()) {
                // ... ¡Bloqueamos el borrado!
                true
            } else {
                false
            }
        }
    }
}