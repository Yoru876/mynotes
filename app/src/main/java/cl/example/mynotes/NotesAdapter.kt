package cl.example.mynotes

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class NotesAdapter(private val onNoteClicked: (Note) -> Unit) :
    ListAdapter<Note, NotesAdapter.NoteViewHolder>(NotesComparator()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NoteViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_note, parent, false)
        return NoteViewHolder(view)
    }

    override fun onBindViewHolder(holder: NoteViewHolder, position: Int) {
        holder.bind(getItem(position), onNoteClicked)
    }

    class NoteViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val titleTv: TextView = itemView.findViewById(R.id.tv_item_title)
        private val contentTv: TextView = itemView.findViewById(R.id.tv_item_content)
        private val dateTv: TextView = itemView.findViewById(R.id.tv_item_date)
        private val card: CardView = itemView.findViewById(R.id.note_card_root)

        // Referencias nuevas del diseño en capas
        private val ivBackground: ImageView = itemView.findViewById(R.id.iv_note_background)
        private val viewOverlay: View = itemView.findViewById(R.id.view_overlay)

        private val gson = Gson()

        fun bind(note: Note, clickListener: (Note) -> Unit) {
            titleTv.text = note.title
            dateTv.text = note.date

            // 1. VISTA PREVIA DEL CONTENIDO (Checklist o Texto)
            if (note.content.startsWith("{checklist:true}")) {
                contentTv.text = generarVistaPreviaChecklist(note.content)
            } else {
                contentTv.text = RichTextHelper.stripTags(note.content)
            }

            // 2. MANEJO DE FONDO (IMAGEN O COLOR)
            val backgroundInfo = note.color

            // Resetear visibilidad
            ivBackground.visibility = View.GONE
            viewOverlay.visibility = View.GONE

            if (!backgroundInfo.isNullOrEmpty()) {
                if (backgroundInfo.startsWith("content://") || backgroundInfo.startsWith("file://")) {
                    // --- ES UNA IMAGEN ---
                    ivBackground.visibility = View.VISIBLE
                    viewOverlay.visibility = View.VISIBLE // Activamos la sombra oscura

                    Glide.with(itemView.context)
                        .load(backgroundInfo)
                        .centerCrop()
                        .transition(DrawableTransitionOptions.withCrossFade())
                        .into(ivBackground)

                    // Al haber imagen, el texto SIEMPRE debe ser blanco para leerse sobre la sombra
                    card.setCardBackgroundColor(Color.BLACK) // Color base por si la imagen tarda
                    aplicarColoresTexto(true) // Forzar texto blanco

                } else {
                    // --- ES UN COLOR SÓLIDO ---
                    try {
                        val colorInt = Color.parseColor(backgroundInfo)
                        card.setCardBackgroundColor(colorInt)

                        // Calculamos si necesitamos texto blanco o negro según el color
                        val esOscuro = isColorDark(colorInt)
                        aplicarColoresTexto(esOscuro)
                    } catch (e: Exception) {
                        card.setCardBackgroundColor(Color.WHITE)
                        aplicarColoresTexto(false)
                    }
                }
            } else {
                // --- POR DEFECTO (BLANCO) ---
                card.setCardBackgroundColor(Color.WHITE)
                aplicarColoresTexto(false)
            }

            card.setOnClickListener { clickListener(note) }
        }

        private fun generarVistaPreviaChecklist(json: String): String {
            return try {
                val cleanJson = json.replace("{checklist:true}", "")
                val type = object : TypeToken<List<ChecklistItem>>() {}.type
                val list: List<ChecklistItem> = gson.fromJson(cleanJson, type)

                val sb = StringBuilder()
                val limit = minOf(list.size, 4)
                for (i in 0 until limit) {
                    val item = list[i]
                    val symbol = if (item.isChecked) "☑" else "☐"
                    sb.append("$symbol ${item.text}")
                    if (i < limit - 1) sb.append("\n")
                }
                if (list.size > limit) sb.append("\n...")
                sb.toString()
            } catch (e: Exception) {
                "Lista de tareas"
            }
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