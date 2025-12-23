package cl.example.mynotes

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

// Heredamos de ListAdapter para que las animaciones sean automáticas y eficientes
class NotesAdapter(private val onNoteClicked: (Note) -> Unit) :
    ListAdapter<Note, NotesAdapter.NoteViewHolder>(NotesComparator()) {

    // 1. Crea el "cascarón" visual (infla el XML)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NoteViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_note, parent, false)
        return NoteViewHolder(view)
    }

    // 2. Rellena el cascarón con los datos de la nota actual
    override fun onBindViewHolder(holder: NoteViewHolder, position: Int) {
        val currentNote = getItem(position)
        holder.bind(currentNote, onNoteClicked)
    }

    // --- CLASE INTERNA VIEWHOLDER: Mantiene las referencias a los textos ---
    class NoteViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val titleTv: TextView = itemView.findViewById(R.id.tv_item_title)
        private val contentTv: TextView = itemView.findViewById(R.id.tv_item_content)
        private val dateTv: TextView = itemView.findViewById(R.id.tv_item_date)
        private val card: CardView = itemView.findViewById(R.id.note_card_root)

        fun bind(note: Note, clickListener: (Note) -> Unit) {
            titleTv.text = note.title
            contentTv.text = note.content
            dateTv.text = note.date

            // Al hacer clic en la tarjeta, avisamos a la actividad principal
            card.setOnClickListener { clickListener(note) }
        }
    }

    // --- COMPARATOR: Ayuda a Android a saber si una nota cambió ---
    class NotesComparator : DiffUtil.ItemCallback<Note>() {
        override fun areItemsTheSame(oldItem: Note, newItem: Note): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Note, newItem: Note): Boolean {
            return oldItem == newItem
        }
    }
}