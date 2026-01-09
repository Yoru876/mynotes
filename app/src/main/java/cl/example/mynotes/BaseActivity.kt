package cl.example.mynotes

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

open class BaseActivity : AppCompatActivity() {

    override fun setContentView(layoutResID: Int) {
        super.setContentView(layoutResID)
        applyGlobalFont(window.decorView)
    }

    override fun setContentView(view: View?) {
        super.setContentView(view)
        view?.let { applyGlobalFont(it) }
    }

    // Función recursiva que recorre toda la pantalla buscando textos
    fun applyGlobalFont(view: View) {
        if (view is TextView) {
            val typeface = FontManager.getTypeface(this)
            view.typeface = typeface
        }

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                applyGlobalFont(view.getChildAt(i))
            }
        }
    }

    // Opcional: Llamar esto en onResume por si cambiaste la config y volviste
    override fun onResume() {
        super.onResume()
        applyGlobalFont(window.decorView)
    }
}