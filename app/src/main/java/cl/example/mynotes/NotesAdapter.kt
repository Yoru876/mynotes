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
        private val imageView: ImageView = itemView.findViewById(R.id.iv_note_image) // Asegúrate que este ID exista en item_note.xml

        // Instancia para procesar JSON
        private val gson = Gson()

        fun bind(note: Note, clickListener: (Note) -> Unit) {
            titleTv.text = note.title
            dateTv.text = note.date

            // 1. LÓGICA DE CONTENIDO (Texto vs Checklist)
            if (note.content.startsWith("{checklist:true}")) {
                // Es una lista: Generar vista previa bonita
                contentTv.text = generarVistaPreviaChecklist(note.content)
            } else {
                // Es texto normal: Limpiar HTML/Tags
                contentTv.text = RichTextHelper.stripTags(note.content)
            }

            // 2. LÓGICA DE FONDO (Color vs Imagen)
            // Reseteamos estados por el reciclaje de vistas
            imageView.visibility = View.GONE

            val backgroundInfo = note.color // Puede ser Hex (#FFFFFF) o URI (content://...)

            if (!backgroundInfo.isNullOrEmpty()) {
                if (backgroundInfo.startsWith("content://") || backgroundInfo.startsWith("file://")) {
                    // CASO A: IMAGEN DE FONDO
                    imageView.visibility = View.VISIBLE
                    Glide.with(itemView.context)
                        .load(backgroundInfo)
                        .centerCrop()
                        .transition(DrawableTransitionOptions.withCrossFade())
                        .into(imageView)

                    // Fondo de tarjeta negro para que resalte la imagen
                    card.setCardBackgroundColor(Color.BLACK)
                    ajustarColorTexto(esFondoOscuro = true)

                } else {
                    // CASO B: COLOR SÓLIDO
                    try {
                        val colorInt = Color.parseColor(backgroundInfo)
                        card.setCardBackgroundColor(colorInt)
                        // Calculamos si el color es oscuro para cambiar el texto a blanco
                        ajustarColorTexto(isColorDark(colorInt))
                    } catch (e: Exception) {
                        card.setCardBackgroundColor(Color.WHITE)
                        ajustarColorTexto(esFondoOscuro = false)
                    }
                }
            } else {
                // CASO C: SIN INFORMACIÓN (Blanco por defecto)
                card.setCardBackgroundColor(Color.WHITE)
                ajustarColorTexto(esFondoOscuro = false)
            }

            card.setOnClickListener { clickListener(note) }
        }

        // Parsea el JSON y crea un string tipo: "☑ Pan \n ☐ Leche"
        private fun generarVistaPreviaChecklist(json: String): String {
            return try {
                val cleanJson = json.replace("{checklist:true}", "")
                val type = object : TypeToken<List<ChecklistItem>>() {}.type
                val list: List<ChecklistItem> = gson.fromJson(cleanJson, type)

                val sb = StringBuilder()
                // Mostramos máximo 4 items para no saturar la tarjeta
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

        // Cambia el color del texto según el fondo para que se lea
        private fun ajustarColorTexto(esFondoOscuro: Boolean) {
            val color = if (esFondoOscuro) Color.WHITE else Color.parseColor("#1C1C1E")
            val dateColor = if (esFondoOscuro) Color.LTGRAY else Color.GRAY

            titleTv.setTextColor(color)
            contentTv.setTextColor(color)
            dateTv.setTextColor(dateColor)
        }

        // Fórmula matemática para saber si un color es oscuro
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