package cl.example.mynotes

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
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions

data class ChecklistItem(
    var text: String,
    var isChecked: Boolean,
    var imageUri: String? = null,
    var imageSizeState: Int = 1
)

class ChecklistAdapter(
    private val items: MutableList<ChecklistItem>,
    private val onDelete: (Int) -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit
) : RecyclerView.Adapter<ChecklistAdapter.ViewHolder>() {

    // Color por defecto (Negro). Se actualiza vía updateTextColor()
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

        // 1. INYECTAR TIPOGRAFÍA (Pixel Art / Sistema / Custom)
        val typeface = FontManager.getTypeface(context)
        holder.editText.typeface = typeface

        // 2. APLICAR COLOR AL TEXTO Y CURSOR
        // Este color viene de la variable currentTextColor que definimos abajo
        holder.editText.setTextColor(currentTextColor)
        holder.editText.setCursorColor(currentTextColor)
        holder.editText.setHintTextColor(adjustAlpha(currentTextColor, 0.5f)) // Hint un poco más transparente

        // 3. ICONOS (Check y Borrar)
        // IMPORTANTE: Limpiamos filtros en AMBOS para respetar tus diseños originales
        holder.btnCheck.clearColorFilter()
        holder.btnDelete.clearColorFilter()

        // 4. LÓGICA VISUAL CHECK
        actualizarIcono(holder.btnCheck, item.isChecked)
        actualizarTachado(holder.editText, item.isChecked)

        // TextWatcher (Evita bucles infinitos)
        if (holder.editText.tag is TextWatcher) {
            holder.editText.removeTextChangedListener(holder.editText.tag as TextWatcher)
        }
        holder.editText.setText(item.text)

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (item.text != s.toString()) item.text = s.toString()
            }
        }
        holder.editText.addTextChangedListener(watcher)
        holder.editText.tag = watcher

        // 5. IMAGEN
        if (item.imageUri != null) {
            holder.itemImage.visibility = View.VISIBLE
            val heightMini = 150.dpToPx(context)
            val heightMedium = 300.dpToPx(context)
            val params = holder.itemImage.layoutParams

            when (item.imageSizeState) {
                0 -> {
                    params.height = heightMini
                    holder.itemImage.scaleType = ImageView.ScaleType.CENTER_CROP
                    holder.itemImage.adjustViewBounds = false
                }
                1 -> {
                    params.height = heightMedium
                    holder.itemImage.scaleType = ImageView.ScaleType.FIT_CENTER
                    holder.itemImage.adjustViewBounds = false
                }
                2 -> {
                    params.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    holder.itemImage.scaleType = ImageView.ScaleType.FIT_CENTER
                    holder.itemImage.adjustViewBounds = true
                }
            }
            holder.itemImage.layoutParams = params

            Glide.with(context)
                .load(item.imageUri)
                .transition(DrawableTransitionOptions.withCrossFade())
                .into(holder.itemImage)

            holder.itemImage.setOnClickListener {
                item.imageSizeState = (item.imageSizeState + 1) % 3
                notifyItemChanged(position)
            }

            holder.itemImage.setOnLongClickListener { view ->
                mostrarMenuEliminar(view, item, position)
                true
            }

        } else {
            holder.itemImage.visibility = View.GONE
            holder.itemImage.setImageDrawable(null)
            holder.itemImage.setOnClickListener(null)
            holder.itemImage.setOnLongClickListener(null)
        }

        // LISTENERS
        holder.btnCheck.setOnClickListener {
            item.isChecked = !item.isChecked
            actualizarIcono(holder.btnCheck, item.isChecked)
            actualizarTachado(holder.editText, item.isChecked)
        }

        holder.btnCheck.setOnLongClickListener {
            onStartDrag(holder)
            true
        }

        holder.btnDelete.setOnClickListener {
            onDelete(holder.layoutPosition)
        }
    }

    /**
     * IMPORTANTE:
     * Para que las letras cambien de color, DEBES llamar a esta función
     * desde tu NoteEditorActivity cada vez que cambie el fondo.
     */
    fun updateTextColor(newColor: Int) {
        this.currentTextColor = newColor
        notifyDataSetChanged() // Refresca toda la lista con el nuevo color
    }

    private fun mostrarMenuEliminar(view: View, item: ChecklistItem, position: Int) {
        val popup = PopupMenu(view.context, view, Gravity.END)
        popup.menu.add("Eliminar imagen")
        popup.setOnMenuItemClickListener { menuItem ->
            if (menuItem.title == "Eliminar imagen") {
                item.imageUri = null
                item.imageSizeState = 1
                notifyItemChanged(position)
                true
            } else {
                false
            }
        }
        popup.show()
    }

    private fun actualizarIcono(btn: ImageButton, isChecked: Boolean) {
        val iconRes = if (isChecked) R.drawable.checkbox_checklist else R.drawable.checkbox_vacio
        btn.setImageResource(iconRes)
        btn.clearColorFilter() // Mantiene colores originales
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

    private fun Int.dpToPx(context: Context): Int {
        return (this * context.resources.displayMetrics.density).toInt()
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

    // Función auxiliar para dar transparencia al Hint
    private fun adjustAlpha(color: Int, factor: Float): Int {
        val alpha = (Color.alpha(color) * factor).toInt()
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }

    override fun getItemCount() = items.size
}