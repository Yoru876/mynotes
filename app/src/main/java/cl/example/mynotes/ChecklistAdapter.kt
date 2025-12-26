package cl.example.mynotes

import android.content.Context
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

// ACTUALIZACIÓN DEL MODELO:
// imageSizeState: 0=Miniatura (Cuadrada), 1=Mediana (Ajustada), 2=Completa (Original)
data class ChecklistItem(
    var text: String,
    var isChecked: Boolean,
    var imageUri: String? = null,
    var imageSizeState: Int = 1 // Por defecto Mediana
)

class ChecklistAdapter(
    private val items: MutableList<ChecklistItem>,
    private val onDelete: (Int) -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit
) : RecyclerView.Adapter<ChecklistAdapter.ViewHolder>() {

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

        // 1. Estados visuales básicos (Icono y Tachado)
        actualizarIcono(holder.btnCheck, item.isChecked)
        actualizarTachado(holder.editText, item.isChecked)
        holder.editText.setText(item.text)

        // 2. LÓGICA DE IMAGEN (CICLO DE TAMAÑOS + MENU ELIMINAR)
        if (item.imageUri != null) {
            holder.itemImage.visibility = View.VISIBLE

            // Definimos alturas en píxeles
            val heightMini = 150.dpToPx(context)   // ~ Pequeño
            val heightMedium = 300.dpToPx(context) // ~ Mediano

            val params = holder.itemImage.layoutParams

            // APLICAR TAMAÑO SEGÚN EL ESTADO (0, 1, 2)
            when (item.imageSizeState) {
                0 -> { // MINIATURA
                    params.height = heightMini
                    holder.itemImage.scaleType = ImageView.ScaleType.CENTER_CROP
                    holder.itemImage.adjustViewBounds = false
                }
                1 -> { // MEDIANA (Default)
                    params.height = heightMedium
                    holder.itemImage.scaleType = ImageView.ScaleType.FIT_CENTER
                    holder.itemImage.adjustViewBounds = false
                }
                2 -> { // COMPLETA (Se expande lo que sea necesario)
                    params.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    holder.itemImage.scaleType = ImageView.ScaleType.FIT_CENTER
                    holder.itemImage.adjustViewBounds = true
                }
            }
            holder.itemImage.layoutParams = params

            // Cargar imagen con Glide
            Glide.with(context)
                .load(item.imageUri)
                .transition(DrawableTransitionOptions.withCrossFade())
                .into(holder.itemImage)

            // INTERACCIÓN: CLICK CORTO -> CAMBIAR TAMAÑO
            holder.itemImage.setOnClickListener {
                // Ciclo: 0 -> 1 -> 2 -> 0 ...
                item.imageSizeState = (item.imageSizeState + 1) % 3
                notifyItemChanged(position) // Refrescar vista
            }

            // INTERACCIÓN: CLICK LARGO -> MENÚ ELIMINAR
            holder.itemImage.setOnLongClickListener { view ->
                mostrarMenuEliminar(view, item, position)
                true // Consumimos el evento
            }

        } else {
            // Si no hay imagen, ocultamos y limpiamos listeners
            holder.itemImage.visibility = View.GONE
            holder.itemImage.setImageDrawable(null)
            holder.itemImage.setOnClickListener(null)
            holder.itemImage.setOnLongClickListener(null)
        }

        // 3. LISTENERS GENERALES

        // Marcar / Desmarcar
        holder.btnCheck.setOnClickListener {
            item.isChecked = !item.isChecked
            actualizarIcono(holder.btnCheck, item.isChecked)
            actualizarTachado(holder.editText, item.isChecked)
        }

        // Arrastrar (Mantener presionado el check)
        holder.btnCheck.setOnLongClickListener {
            onStartDrag(holder)
            true
        }

        // Texto (TextWatcher con prevención de duplicados)
        if (holder.editText.tag == null) {
            val watcher = object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (item.text != s.toString()) item.text = s.toString()
                }
            }
            holder.editText.addTextChangedListener(watcher)
            holder.editText.tag = watcher
        }

        // Borrar toda la fila
        holder.btnDelete.setOnClickListener {
            onDelete(holder.layoutPosition)
        }
    }

    // Menú flotante solo para borrar la imagen
    private fun mostrarMenuEliminar(view: View, item: ChecklistItem, position: Int) {
        val popup = PopupMenu(view.context, view, Gravity.END)
        popup.menu.add("Eliminar imagen")
        popup.setOnMenuItemClickListener { menuItem ->
            if (menuItem.title == "Eliminar imagen") {
                item.imageUri = null
                item.imageSizeState = 1 // Resetear tamaño por si agrega otra
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

    // Utilidad para convertir dp a pixeles
    private fun Int.dpToPx(context: Context): Int {
        return (this * context.resources.displayMetrics.density).toInt()
    }

    override fun getItemCount() = items.size
}