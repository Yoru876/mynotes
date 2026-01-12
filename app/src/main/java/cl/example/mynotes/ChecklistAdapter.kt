package cl.example.mynotes

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.os.Build
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions

data class ChecklistItem(
    var text: String,
    var isChecked: Boolean,
    var imageUri: String? = null,
    var widthPercentage: Int = 100
)

class ChecklistAdapter(
    private val items: MutableList<ChecklistItem>,
    private val onDelete: (Int) -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit
) : RecyclerView.Adapter<ChecklistAdapter.ViewHolder>() {

    private var currentTextColor: Int = Color.BLACK

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val btnCheck: ImageButton = view.findViewById(R.id.btn_check_toggle)
        val editText: EditText = view.findViewById(R.id.item_text)
        val btnDelete: ImageButton = view.findViewById(R.id.item_delete)
        val itemImage: ImageView = view.findViewById(R.id.item_image)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val context = parent.context
        val view = LayoutInflater.from(context).inflate(R.layout.item_checklist, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val context = holder.itemView.context

        // 1. ESTILOS (Fuentes y Colores)
        try {
            val typeface = FontManager.getTypeface(context)
            holder.editText.typeface = typeface
        } catch (e: Exception) { }

        holder.editText.setTextColor(currentTextColor)
        holder.editText.setCursorColor(currentTextColor)
        holder.editText.setHintTextColor(adjustAlpha(currentTextColor, 0.5f))

        holder.btnCheck.clearColorFilter()
        holder.btnDelete.clearColorFilter()

        // 2. ESTADO VISUAL
        actualizarIcono(holder.btnCheck, item.isChecked)
        actualizarTachado(holder.editText, item.isChecked)

        // 3. TEXTO Y LÓGICA DE PEGADO (INTERCEPTOR + REGEX)
        if (holder.editText.tag is TextWatcher) {
            holder.editText.removeTextChangedListener(holder.editText.tag as TextWatcher)
        }
        holder.editText.setText(item.text)

        // --- A. INTERCEPTOR (CAPTURA PEGADO DE IMAGEN) ---
        val mimeTypes = arrayOf("text/*", "image/*")
        ViewCompat.setOnReceiveContentListener(holder.editText, mimeTypes) { _, payload ->
            val clip = payload.clip
            if (clip.itemCount > 0) {
                val text = clip.getItemAt(0).text?.toString()?.trim() ?: ""
                val label = clip.description.label?.toString() ?: ""

                val isImageUri = label == "Image URI" ||
                        text.startsWith("content://") ||
                        text.startsWith("file://")

                if (isImageUri && text.isNotEmpty()) {
                    val currentPos = holder.bindingAdapterPosition
                    if (currentPos != RecyclerView.NO_POSITION) {
                        items[currentPos].imageUri = text
                        items[currentPos].widthPercentage = 100
                        notifyItemChanged(currentPos)
                    }
                    return@setOnReceiveContentListener null // Bloquea el pegado del texto
                }
            }
            payload
        }

        // --- B. TEXT WATCHER (PLAN B CON REGEX) ---
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val currentPos = holder.bindingAdapterPosition
                if (currentPos != RecyclerView.NO_POSITION) {
                    val fullText = s.toString()

                    // Regex para encontrar "content://..." o "file://..." aunque haya texto antes
                    val pattern = "(content://[^\\s]+|file://[^\\s]+)".toRegex()
                    val match = pattern.find(fullText)

                    if (match != null) {
                        // ¡URL encontrada en el texto!
                        val uriFound = match.value

                        // Limpiamos el texto visualmente (quitamos la URL)
                        val cleanText = fullText.replace(uriFound, "").trim()

                        holder.editText.removeTextChangedListener(this)
                        holder.editText.setText(cleanText)

                        // Restauramos cursor al final si estábamos escribiendo
                        if (holder.editText.hasFocus()) {
                            holder.editText.setSelection(cleanText.length)
                        }

                        items[currentPos].text = cleanText
                        holder.editText.addTextChangedListener(this)

                        // Asignamos la imagen
                        items[currentPos].imageUri = uriFound
                        notifyItemChanged(currentPos)
                        return
                    }

                    // Guardado normal
                    items[currentPos].text = fullText
                }
            }
        }
        holder.editText.addTextChangedListener(watcher)
        holder.editText.tag = watcher

        // 4. IMAGEN (VISUALIZACIÓN Y TAMAÑO)
        if (item.imageUri != null) {
            holder.itemImage.visibility = View.VISIBLE

            Glide.with(context)
                .load(item.imageUri)
                .transition(DrawableTransitionOptions.withCrossFade())
                .into(holder.itemImage)

            val screenWidth = context.resources.displayMetrics.widthPixels
            val safePercentage = if (item.widthPercentage > 0) item.widthPercentage else 100
            val targetWidth = (screenWidth * (safePercentage / 100f)).toInt()
            val padding = (screenWidth - targetWidth) / 2

            holder.itemImage.setPadding(padding.coerceAtLeast(0), 0, padding.coerceAtLeast(0), 0)
            holder.itemImage.adjustViewBounds = true
            holder.itemImage.scaleType = ImageView.ScaleType.FIT_CENTER

            // MENÚ CONTEXTUAL
            holder.itemImage.setOnLongClickListener { view ->
                val currentPos = holder.bindingAdapterPosition
                if (currentPos != RecyclerView.NO_POSITION) {
                    mostrarMenuImagen(view, items[currentPos], currentPos)
                }
                true
            }

        } else {
            holder.itemImage.visibility = View.GONE
            holder.itemImage.setImageDrawable(null)
            holder.itemImage.setOnLongClickListener(null)
        }

        // 5. CLICK LISTENERS
        holder.btnCheck.setOnClickListener {
            val currentPos = holder.bindingAdapterPosition
            if (currentPos != RecyclerView.NO_POSITION) {
                val currentItem = items[currentPos]
                currentItem.isChecked = !currentItem.isChecked
                actualizarIcono(holder.btnCheck, currentItem.isChecked)
                actualizarTachado(holder.editText, currentItem.isChecked)
            }
        }

        holder.btnCheck.setOnLongClickListener {
            onStartDrag(holder)
            true
        }

        holder.btnDelete.setOnClickListener {
            val currentPos = holder.bindingAdapterPosition
            if (currentPos != RecyclerView.NO_POSITION) {
                onDelete(currentPos)
            }
        }
    }

    fun updateTextColor(newColor: Int) {
        this.currentTextColor = newColor
        notifyDataSetChanged()
    }

    private fun mostrarMenuImagen(view: View, item: ChecklistItem, position: Int) {
        val popup = PopupMenu(view.context, view, Gravity.END)
        popup.menu.add("Tamaño: 100%")
        popup.menu.add("Tamaño: 75%")
        popup.menu.add("Tamaño: 50%")
        popup.menu.add("Copiar")
        popup.menu.add("Cortar")
        popup.menu.add("Eliminar imagen")

        popup.setOnMenuItemClickListener { menuItem ->
            val title = menuItem.title.toString()
            when {
                title.contains("100%") -> {
                    item.widthPercentage = 100
                    notifyItemChanged(position)
                    true
                }
                title.contains("75%") -> {
                    item.widthPercentage = 75
                    notifyItemChanged(position)
                    true
                }
                title.contains("50%") -> {
                    item.widthPercentage = 50
                    notifyItemChanged(position)
                    true
                }
                title == "Copiar" -> {
                    item.imageUri?.let { uri -> copiarImagen(view.context, uri) }
                    true
                }
                title == "Cortar" -> {
                    item.imageUri?.let { uri ->
                        copiarImagen(view.context, uri)
                        item.imageUri = null
                        item.widthPercentage = 100
                        notifyItemChanged(position)
                        Toast.makeText(view.context, "Imagen cortada", Toast.LENGTH_SHORT).show()
                    }
                    true
                }
                title == "Eliminar imagen" -> {
                    item.imageUri = null
                    item.widthPercentage = 100
                    notifyItemChanged(position)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun copiarImagen(context: Context, uriString: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Image URI", uriString)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "Imagen copiada al portapapeles", Toast.LENGTH_SHORT).show()
    }

    private fun actualizarIcono(btn: ImageButton, isChecked: Boolean) {
        val iconRes = if (isChecked) R.drawable.checkbox_checklist else R.drawable.checkbox_vacio
        btn.setImageResource(iconRes)
        btn.clearColorFilter()
    }

    private fun actualizarTachado(editText: EditText, isChecked: Boolean) {
        if (isChecked) {
            editText.paintFlags = editText.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
            editText.alpha = 0.5f
        } else {
            editText.paintFlags = editText.paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
            editText.alpha = 1.0f
        }
    }

    private fun EditText.setCursorColor(color: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val filter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
            textCursorDrawable?.colorFilter = filter
            textSelectHandle?.colorFilter = filter
            textSelectHandleLeft?.colorFilter = filter
            textSelectHandleRight?.colorFilter = filter
        }
    }

    private fun adjustAlpha(color: Int, factor: Float): Int {
        val alpha = (Color.alpha(color) * factor).toInt()
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }

    override fun getItemCount() = items.size
}