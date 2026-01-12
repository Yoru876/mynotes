package cl.example.mynotes

import java.util.UUID

sealed class NoteBlock(
    val id: String = UUID.randomUUID().toString()
) {
    data class TextBlock(
        var text: String = ""
    ) : NoteBlock()

    // MODIFICADO: Agregamos widthPercentage (100 = full, 75, 50)
    data class ImageBlock(
        var uri: String,
        var widthPercentage: Int = 100
    ) : NoteBlock()

    data class ChecklistItem(
        var text: String,
        var isChecked: Boolean,
        var imageUri: String? = null,
        // AGREGAMOS ESTO PARA QUE RECUERDE EL TAMAÑO
        var widthPercentage: Int = 100
    )
}