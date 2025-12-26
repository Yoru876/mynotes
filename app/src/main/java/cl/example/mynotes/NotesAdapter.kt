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
import com.google.android.material.card.MaterialCardView // IMPORTANTE: Usar MaterialCardView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class NotesAdapter(
    private val onNoteClicked: (Note) -> Unit,
    private val onNoteLongClicked: (Note) -> Unit,
    private val onSelectionChanged: (Int) -> Unit
) : ListAdapter<Note, NotesAdapter.NoteViewHolder>(NotesComparator()) {

    // LÓGICA DE SELECCIÓN MÚLTIPLE
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
            onNoteLongClicked
        )
    }

    class NoteViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val titleTv: TextView = itemView.findViewById(R.id.tv_item_title)
        private val contentTv: TextView = itemView.findViewById(R.id.tv_item_content)
        private val dateTv: TextView = itemView.findViewById(R.id.tv_item_date)

        // CORRECCIÓN AQUÍ: Definir como MaterialCardView para acceder a strokeWidth/Color
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
            longClickListener: (Note) -> Unit
        ) {
            titleTv.text = note.title
            dateTv.text = note.date

            // 1. Contenido
            if (note.content.startsWith("{checklist:true}")) {
                contentTv.text = generarVistaPreviaChecklist(note.content)
            } else {
                contentTv.text = RichTextHelper.stripTags(note.content)
            }

            // 2. Fondo
            val backgroundInfo = note.color
            ivBackground.visibility = View.GONE
            viewOverlay.visibility = View.GONE

            if (!backgroundInfo.isNullOrEmpty()) {
                if (backgroundInfo.startsWith("content://") || backgroundInfo.startsWith("file://")) {
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

            // 3. ESTADO DE SELECCIÓN (Ahora sí funcionará strokeWidth)
            if (isSelected) {
                selectionOverlay.visibility = View.VISIBLE
                card.strokeWidth = 6
                card.strokeColor = Color.parseColor("#2196F3")
            } else {
                selectionOverlay.visibility = View.GONE
                card.strokeWidth = 0
            }

            // 4. LISTENERS
            card.setOnClickListener {
                if (isMultiSelect) {
                    longClickListener(note)
                } else {
                    clickListener(note)
                }
            }

            card.setOnLongClickListener {
                longClickListener(note)
                true
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