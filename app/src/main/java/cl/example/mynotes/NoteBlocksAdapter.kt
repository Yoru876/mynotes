package cl.example.mynotes

import android.graphics.Color
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions

class NoteBlocksAdapter(
    private val blocks: MutableList<NoteBlock>,
    private val customTypeface: Typeface?,
    private val onAction: (Action) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var currentTextColor: Int = Color.parseColor("#1C1C1E")

    fun updateTextColor(color: Int) {
        currentTextColor = color
        notifyDataSetChanged()
    }

    sealed class Action {
        data class AddTextBlock(val position: Int) : Action()
        data class DeleteBlock(val position: Int) : Action()
        data class MergeWithPrevious(val position: Int) : Action()
        data class FocusBlock(val position: Int) : Action()
        data class ShowImageOptions(val position: Int, val view: View) : Action()
        data class InsertImageFromClipboard(val position: Int, val uri: String) : Action()
    }

    companion object {
        const val TYPE_TEXT = 0
        const val TYPE_IMAGE = 1
    }

    override fun getItemViewType(position: Int): Int {
        return when (blocks[position]) {
            is NoteBlock.TextBlock -> TYPE_TEXT
            is NoteBlock.ImageBlock -> TYPE_IMAGE
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_TEXT -> TextViewHolder(inflater.inflate(R.layout.item_block_text, parent, false))
            TYPE_IMAGE -> ImageViewHolder(inflater.inflate(R.layout.item_block_image, parent, false))
            else -> throw IllegalArgumentException("Tipo desconocido")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is TextViewHolder -> holder.bind(blocks[position] as NoteBlock.TextBlock, position)
            is ImageViewHolder -> holder.bind(blocks[position] as NoteBlock.ImageBlock, position)
        }
    }

    override fun getItemCount() = blocks.size

    inner class TextViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val editText: EditText = view.findViewById(R.id.et_block_text)
        private var textWatcher: TextWatcher? = null

        fun bind(item: NoteBlock.TextBlock, position: Int) {
            if (textWatcher != null) editText.removeTextChangedListener(textWatcher)

            if (customTypeface != null) editText.typeface = customTypeface
            editText.setTextColor(currentTextColor)
            editText.setHintTextColor(if (currentTextColor == Color.WHITE) Color.LTGRAY else Color.parseColor("#9E9E9E"))
            setCursorColor(editText, currentTextColor)

            editText.setText(item.text)

            // --- CORRECCIÓN DEL CRASH ---
            // Android prohíbe usar "*/*". Debemos especificar "text/*" e "image/*".
            val mimeTypes = arrayOf("text/*", "image/*")

            ViewCompat.setOnReceiveContentListener(editText, mimeTypes) { _, payload ->
                val clip = payload.clip
                if (clip.itemCount > 0) {
                    val text = clip.getItemAt(0).text?.toString()?.trim() ?: ""
                    val label = clip.description.label?.toString() ?: ""

                    // Detectar si es una URI de imagen válida
                    val isImageUri = label == "Image URI" ||
                            text.startsWith("content://") ||
                            text.startsWith("file://")

                    if (isImageUri && text.isNotEmpty()) {
                        onAction(Action.InsertImageFromClipboard(adapterPosition + 1, text))
                        return@setOnReceiveContentListener null // Bloqueamos el pegado de texto
                    }
                }
                payload // Dejar pasar si no es imagen
            }

            textWatcher = object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

                override fun afterTextChanged(s: Editable?) {
                    val text = s.toString().trim()

                    // Plan B: Si se pegó la URI como texto
                    if (text.startsWith("content://") || text.startsWith("file://")) {
                        if (!text.contains(" ") && text.length > 10) {
                            editText.removeTextChangedListener(this)
                            editText.setText("")
                            item.text = ""
                            editText.addTextChangedListener(this)

                            onAction(Action.InsertImageFromClipboard(adapterPosition + 1, text))
                            return
                        }
                    }
                    item.text = s.toString()
                }
            }
            editText.addTextChangedListener(textWatcher)

            editText.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DEL) {
                    if (editText.text.isEmpty() && adapterPosition > 0) {
                        onAction(Action.DeleteBlock(adapterPosition))
                        onAction(Action.FocusBlock(adapterPosition - 1))
                        return@setOnKeyListener true
                    }
                    if (editText.selectionStart == 0 && adapterPosition > 0) {
                        onAction(Action.MergeWithPrevious(adapterPosition))
                        return@setOnKeyListener true
                    }
                }
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ENTER) {
                    onAction(Action.AddTextBlock(adapterPosition + 1))
                    return@setOnKeyListener true
                }
                false
            }
        }

        private fun setCursorColor(view: EditText, color: Int) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val filter = android.graphics.PorterDuffColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN)
                view.textCursorDrawable?.colorFilter = filter
                view.textSelectHandle?.colorFilter = filter
                view.textSelectHandleLeft?.colorFilter = filter
                view.textSelectHandleRight?.colorFilter = filter
            }
        }
    }

    inner class ImageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imageView: ImageView = view.findViewById(R.id.iv_block_image)
        val btnDelete: ImageButton = view.findViewById(R.id.btn_delete_block)

        fun bind(item: NoteBlock.ImageBlock, position: Int) {
            Glide.with(itemView.context)
                .load(item.uri)
                .transition(DrawableTransitionOptions.withCrossFade())
                .into(imageView)

            val screenWidth = itemView.resources.displayMetrics.widthPixels
            val targetWidth = (screenWidth * (item.widthPercentage / 100f)).toInt()
            val padding = (screenWidth - targetWidth) / 2
            imageView.setPadding(padding.coerceAtLeast(0), 0, padding.coerceAtLeast(0), 0)

            btnDelete.setOnClickListener {
                onAction(Action.DeleteBlock(adapterPosition))
            }

            imageView.setOnLongClickListener {
                onAction(Action.ShowImageOptions(adapterPosition, imageView))
                true
            }
        }
    }
}