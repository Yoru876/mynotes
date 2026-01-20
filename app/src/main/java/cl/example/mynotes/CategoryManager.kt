package cl.example.mynotes

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object CategoryManager {
    private const val PREFS_NAME = "MyNotesCategories"
    private const val KEY_CATEGORIES = "user_categories"
    private val gson = Gson()

    // Categorías protegidas que no se pueden borrar ni editar
    private val DEFAULT_CATEGORIES = listOf("Sín categoría")

    fun getCategories(context: Context): MutableList<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_CATEGORIES, null)

        return if (json != null) {
            val type = object : TypeToken<List<String>>() {}.type
            gson.fromJson(json, type)
        } else {
            DEFAULT_CATEGORIES.toMutableList()
        }
    }

    fun addCategory(context: Context, newCategory: String) {
        val list = getCategories(context)
        if (newCategory.isNotBlank() && !list.contains(newCategory)) {
            list.add(newCategory)
            saveList(context, list)
        }
    }

    fun renameCategory(context: Context, oldName: String, newName: String) {
        val list = getCategories(context)
        val index = list.indexOf(oldName)
        if (index != -1 && !list.contains(newName)) {
            list[index] = newName
            saveList(context, list)
        }
    }

    fun deleteCategory(context: Context, categoryName: String) {
        val list = getCategories(context)
        if (list.remove(categoryName)) {
            saveList(context, list)
        }
    }

    private fun saveList(context: Context, list: List<String>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_CATEGORIES, gson.toJson(list)).apply()
    }
}