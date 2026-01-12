package cl.example.mynotes

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.google.android.material.card.MaterialCardView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class NotesAdapter(
    private val onNoteClicked: (Note) -> Unit,
    private val onNoteLongClicked: (Note) -> Unit,
    private val onSelectionChanged: (Int) -> Unit
) : ListAdapter<Note, NotesAdapter.NoteViewHolder>(NotesComparator()) {

    private var showImages: Boolean = true

    fun updateShowImages(show: Boolean) {
        this.showImages = show
        notifyDataSetChanged()
    }

    private val selectedItems = HashSet<Int>()
    private var isMultiSelectMode = false

    fun setMultiSelectMode(enabled: Boolean) {
        isMultiSelectMode = enabled
        if (!enabled) {
            selectedItems.clear()
            notifyDataSetChanged()
        }
    }

    fun toggleSelection(noteId: Int) {
        if (selectedItems.contains(noteId)) {
            selectedItems.remove(noteId)
        } else {
            selectedItems.add(noteId)
        }
        notifyDataSetChanged()
        onSelectionChanged(selectedItems.size)
    }

    fun getSelectedNotes(): List<Note> {
        return currentList.filter { selectedItems.contains(it.id) }
    }

    fun getSelectedCount(): Int = selectedItems.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NoteViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_note, parent, false)
        return NoteViewHolder(view)
    }

    override fun onBindViewHolder(holder: NoteViewHolder, position: Int) {
        val note = getItem(position)
        val isSelected = selectedItems.contains(note.id)

        holder.bind(
            note,
            isSelected,
            isMultiSelectMode,
            onNoteClicked,
            onNoteLongClicked,
            showImages
        )
    }

    class NoteViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val titleTv: TextView = itemView.findViewById(R.id.tv_item_title)
        private val contentTv: TextView = itemView.findViewById(R.id.tv_item_content)
        private val dateTv: TextView = itemView.findViewById(R.id.tv_item_date)
        private val card: MaterialCardView = itemView.findViewById(R.id.note_card_root)
        private val ivBackground: ImageView = itemView.findViewById(R.id.iv_note_background)
        private val viewOverlay: View = itemView.findViewById(R.id.view_overlay)
        private val selectionOverlay: FrameLayout = itemView.findViewById(R.id.view_selection_overlay)

        private val gson = Gson()

        fun bind(
            note: Note,
            isSelected: Boolean,
            isMultiSelect: Boolean,
            clickListener: (Note) -> Unit,
            longClickListener: (Note) -> Unit,
            showImages: Boolean
        ) {
            // --- APLICAR FUENTE (Tu código original) ---
            try {
                // Envuelto en try-catch por si FontManager no está inicializado
                val typeface = FontManager.getTypeface(itemView.context)
                titleTv.typeface = typeface
                contentTv.typeface = typeface
                dateTv.typeface = typeface
            } catch (e: Exception) { }

            // Asignar textos
            titleTv.text = note.title
            dateTv.text = note.date

            // --- AQUÍ ESTÁ EL CAMBIO CLAVE ---
            // Detectamos qué tipo de nota es para mostrar la vista previa correcta
            if (note.content.startsWith("{checklist:true}")) {
                contentTv.text = generarVistaPreviaChecklist(note.content)
            } else {
                // Aquí usamos la nueva función que lee JSON de Bloques
                contentTv.text = parseBlockContentForPreview(note.content)
            }
            // --------------------------------

            // --- LÓGICA DE FONDO Y COLOR (Tu código original intacto) ---
            val backgroundInfo = note.color
            ivBackground.visibility = View.GONE
            viewOverlay.visibility = View.GONE

            if (!backgroundInfo.isNullOrEmpty()) {
                if (backgroundInfo.startsWith("content://") || backgroundInfo.startsWith("file://")) {
                    if (showImages) {
                        ivBackground.visibility = View.VISIBLE
                        viewOverlay.visibility = View.VISIBLE
                        Glide.with(itemView.context)
                            .load(backgroundInfo)
                            .centerCrop()
                            .transition(DrawableTransitionOptions.withCrossFade())
                            .into(ivBackground)

                        card.setCardBackgroundColor(Color.BLACK)
                        aplicarColoresTexto(true)
                    } else {
                        card.setCardBackgroundColor(Color.WHITE)
                        aplicarColoresTexto(false)
                    }

                } else {
                    try {
                        val colorInt = Color.parseColor(backgroundInfo)
                        card.setCardBackgroundColor(colorInt)
                        aplicarColoresTexto(isColorDark(colorInt))
                    } catch (e: Exception) {
                        card.setCardBackgroundColor(Color.WHITE)
                        aplicarColoresTexto(false)
                    }
                }
            } else {
                card.setCardBackgroundColor(Color.WHITE)
                aplicarColoresTexto(false)
            }

            // --- ESTILO DE SELECCIÓN (Tu código original) ---
            if (isSelected) {
                selectionOverlay.visibility = View.VISIBLE
                card.strokeWidth = dpToPx(3)
                card.strokeColor = Color.parseColor("#2196F3")
            } else {
                selectionOverlay.visibility = View.GONE
                card.strokeWidth = dpToPx(2)
                card.strokeColor = Color.parseColor("#808080")
            }

            card.setOnClickListener {
                if (isMultiSelect) longClickListener(note) else clickListener(note)
            }

            card.setOnLongClickListener {
                longClickListener(note)
                true
            }
        }

        private fun dpToPx(dp: Int): Int {
            return (dp * itemView.context.resources.displayMetrics.density).toInt()
        }

        // --- NUEVA FUNCIÓN: Traduce el JSON de bloques a texto plano para el preview  ---
        private fun parseBlockContentForPreview(content: String): String {
            // 1. Si es formato antiguo (texto plano sin corchetes), devolver tal cual
            if (!content.trim().startsWith("[")) {
                return content // O RichTextHelper.stripTags(content) si aún lo usas
            }

            // 2. Si es formato nuevo (JSON de bloques)
            return try {
                val listType = object : TypeToken<List<Map<String, Any>>>() {}.type
                val blocks: List<Map<String, Any>> = gson.fromJson(content, listType)

                val sb = StringBuilder()
                var hasImages = false

                for (block in blocks) {
                    val type = block["type"] as? String

                    if (type == "text") {
                        val text = block["text"] as? String
                        if (!text.isNullOrBlank()) {
                            sb.append(text).append(" ")
                        }
                    } else if (type == "image") {
                        hasImages = true
                    }

                    // Cortamos si es muy largo para no saturar la vista previa
                    if (sb.length > 150) break
                }

                val finalString = sb.toString().trim()

                when {
                    finalString.isNotEmpty() -> finalString
                    hasImages -> "📷 [Imagen]"
                    else -> "Sin texto"
                }
            } catch (e: Exception) {
                "Nota" // Fallback por si el JSON está corrupto
            }
        }

        private fun generarVistaPreviaChecklist(json: String): String {
            return try {
                val cleanJson = json.replace("{checklist:true}", "")
                val type = object : TypeToken<List<ChecklistItem>>() {}.type
                val items: List<ChecklistItem> = gson.fromJson(cleanJson, type)
                val sb = StringBuilder()
                val limit = minOf(items.size, 4)
                for (i in 0 until limit) {
                    val item = items[i]
                    val symbol = if (item.isChecked) "☑" else "☐"
                    sb.append("$symbol ${item.text}")
                    if (i < limit - 1) sb.append("\n")
                }
                if (items.size > limit) sb.append("\n...")
                sb.toString()
            } catch (e: Exception) { "Lista de tareas" }
        }

        private fun aplicarColoresTexto(esFondoOscuro: Boolean) {
            val colorTexto = if (esFondoOscuro) Color.WHITE else Color.parseColor("#1C1C1E")
            val colorFecha = if (esFondoOscuro) Color.parseColor("#B0B0B0") else Color.GRAY
            titleTv.setTextColor(colorTexto)
            contentTv.setTextColor(colorTexto)
            dateTv.setTextColor(colorFecha)
        }

        private fun isColorDark(color: Int): Boolean {
            val darkness = 1 - (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255
            return darkness >= 0.5
        }
    }

    class NotesComparator : DiffUtil.ItemCallback<Note>() {
        override fun areItemsTheSame(oldItem: Note, newItem: Note) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Note, newItem: Note) = oldItem == newItem
    }
}