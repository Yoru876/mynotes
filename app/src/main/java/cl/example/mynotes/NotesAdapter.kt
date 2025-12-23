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
        private val imageView: ImageView = itemView.findViewById(R.id.iv_note_image)

        fun bind(note: Note, clickListener: (Note) -> Unit) {
            titleTv.text = note.title

            // LIMPIEZA: Convertimos "[IMG:...]" en un emoji de cámara para la vista previa
            contentTv.text = RichTextHelper.stripTags(note.content)

            dateTv.text = note.date

            if (note.color != null) {
                card.setCardBackgroundColor(Color.parseColor(note.color))
            } else {
                card.setCardBackgroundColor(Color.WHITE)
            }

            // Ocultamos la imagen de cabecera antigua, ya que ahora las imagenes están dentro del texto
            imageView.visibility = View.GONE

            card.setOnClickListener { clickListener(note) }
        }
    }

    class NotesComparator : DiffUtil.ItemCallback<Note>() {
        override fun areItemsTheSame(oldItem: Note, newItem: Note) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Note, newItem: Note) = oldItem == newItem
    }
}