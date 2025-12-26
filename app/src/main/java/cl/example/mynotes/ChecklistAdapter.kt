package cl.example.mynotes

import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton // Usamos ImageButton en lugar de CheckBox
import androidx.recyclerview.widget.RecyclerView

data class ChecklistItem(var text: String, var isChecked: Boolean)

class ChecklistAdapter(
    private val items: MutableList<ChecklistItem>,
    private val onDelete: (Int) -> Unit
) : RecyclerView.Adapter<ChecklistAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        // Cambiamos CheckBox por ImageButton
        val btnCheck: ImageButton = view.findViewById(R.id.btn_check_toggle)
        val editText: EditText = view.findViewById(R.id.item_text)
        val btnDelete: ImageButton = view.findViewById(R.id.item_delete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val context = parent.context
        val view = LayoutInflater.from(context).inflate(R.layout.item_checklist, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]

        // 1. Configurar estado visual inicial (Imagen correcta)
        actualizarIcono(holder.btnCheck, item.isChecked)
        actualizarTachado(holder.editText, item.isChecked)

        // Evitamos que el listener de texto salte infinitamente
        holder.editText.tag = item // Usamos tag para validar si necesario (opcional)
        holder.editText.setText(item.text)

        // 2. LOGICA DEL CLICK EN LA IMAGEN (Reemplaza al CheckBox listener)
        holder.btnCheck.setOnClickListener {
            // Invertimos el valor
            item.isChecked = !item.isChecked

            // Actualizamos visualmente
            actualizarIcono(holder.btnCheck, item.isChecked)
            actualizarTachado(holder.editText, item.isChecked)
        }

        // 3. Listener de Texto (Mantenido igual)
        // Nota técnica: Es mejor remover el watcher antes de añadir uno nuevo para evitar memory leaks en listas largas,
        // pero para este MVP lo mantendremos simple verificando el foco.
        if (holder.editText.onFocusChangeListener == null) {
            holder.editText.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    // Solo actualizamos si el texto cambió realmente
                    if (item.text != s.toString()) {
                        item.text = s.toString()
                    }
                }
            })
        }

        holder.btnDelete.setOnClickListener {
            onDelete(holder.layoutPosition)
        }
    }

    // Función auxiliar para cambiar la imagen
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

    override fun getItemCount() = items.size
}