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
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.RadioButton // IMPORTANTE
import android.widget.RadioGroup  // IMPORTANTE
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
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

class NoteEditorActivity : BaseActivity() {

    private lateinit var etTitle: EditText
    private lateinit var tvDateLabel: TextView
    private lateinit var layoutEditor: View

    // UI Categorías (CORREGIDO A RADIOGROUP)
    private lateinit var chipGroupCategories: RadioGroup
    private var selectedCategory: String = "General"

    private lateinit var rvBlocks: RecyclerView
    private val noteBlocks = mutableListOf<NoteBlock>()
    private lateinit var blocksAdapter: NoteBlocksAdapter

    private lateinit var checklistContainer: View
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
    private var tempImageUri: Uri? = null

    private var originalJsonContent: String = ""
    private var originalTitle: String = ""
    private var originalColor: String = "#FFFFFF"
    private var originalCategory: String = "General"

    private var isChecklistMode = false
    private val checklistItems = mutableListOf<ChecklistItem>()
    private lateinit var checklistAdapter: ChecklistAdapter
    private lateinit var itemTouchHelper: ItemTouchHelper
    private val gson = Gson()

    private val db by lazy { NotesDatabase.getDatabase(this) }

    private val PERMISSION_REQUEST_GALLERY = 200
    private val PERMISSION_REQUEST_WALLPAPER = 201

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data?.data != null) {
            val uri = result.data!!.data!!
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (isChecklistMode) {
                    val newItem = ChecklistItem(text = "", isChecked = false, imageUri = uri.toString(), widthPercentage = 100)
                    checklistItems.add(newItem)
                    checklistAdapter.notifyItemInserted(checklistItems.size - 1)
                    rvChecklist.postDelayed({
                        if (checklistItems.isNotEmpty()) rvChecklist.smoothScrollToPosition(checklistItems.size - 1)
                    }, 200)
                } else {
                    val imageBlock = NoteBlock.ImageBlock(uri = uri.toString())
                    noteBlocks.add(imageBlock)
                    noteBlocks.add(NoteBlock.TextBlock(""))
                    blocksAdapter.notifyDataSetChanged()
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

    private val takePhotoLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && tempImageUri != null) {
            try {
                val imageBlock = NoteBlock.ImageBlock(uri = tempImageUri.toString())
                noteBlocks.add(imageBlock)
                noteBlocks.add(NoteBlock.TextBlock(""))
                blocksAdapter.notifyDataSetChanged()
                rvBlocks.postDelayed({
                    if (noteBlocks.isNotEmpty()) {
                        rvBlocks.smoothScrollToPosition(noteBlocks.size - 1)
                        focusBlockAt(noteBlocks.size - 1)
                    }
                }, 100)
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

        setupCategories()
        setupBlocksEditor()
        setupClickToCreateBlock()
        setupChecklist()
        setupListeners()
        setupBackPressHandler()

        loadNoteData()
        silentStartService()
    }

    private fun initViews() {
        etTitle = findViewById(R.id.et_title)
        tvDateLabel = findViewById(R.id.tv_date_label)

        // --- CAMBIO CLAVE: REFERENCIA A RADIOGROUP ---
        chipGroupCategories = findViewById(R.id.chip_group_editor)

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

    // --- FUNCIÓN CORREGIDA: USA RADIOBUTTONS ---
    private fun setupCategories() {
        chipGroupCategories.removeAllViews()
        val categoriesList = CategoryManager.getCategories(this)

        val currentTypeface = try {
            FontManager.getTypeface(this)
        } catch (e: Exception) { null }

        for (category in categoriesList) {
            // INFLAR XML CORRECTO (RadioButton)
            val radioButton = layoutInflater.inflate(R.layout.item_pixel_chip, chipGroupCategories, false) as RadioButton

            radioButton.text = category
            radioButton.id = View.generateViewId()

            if (currentTypeface != null) {
                radioButton.typeface = currentTypeface
            }

            if (category == selectedCategory) {
                radioButton.isChecked = true
            }

            radioButton.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    selectedCategory = category
                }
            }
            chipGroupCategories.addView(radioButton)
        }
    }

    private fun selectCategoryChip(category: String) {
        selectedCategory = category
        for (i in 0 until chipGroupCategories.childCount) {
            val view = chipGroupCategories.getChildAt(i)
            if (view is RadioButton && view.text == category) {
                view.isChecked = true
                break
            }
        }
    }

    // (El resto de métodos: setupClickToCreateBlock, focusBlockAt, setupBlocksEditor, etc. se mantienen igual)

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

    private fun focusBlockAt(position: Int) {
        rvBlocks.postDelayed({
            val viewHolder = rvBlocks.findViewHolderForAdapterPosition(position)
            if (viewHolder is NoteBlocksAdapter.TextViewHolder) {
                viewHolder.editText.requestFocus()
                viewHolder.editText.setSelection(viewHolder.editText.text.length)
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(viewHolder.editText, InputMethodManager.SHOW_IMPLICIT)
            }
        }, 50)
    }

    private fun setupBlocksEditor() {
        val appFont = etTitle.typeface
        blocksAdapter = NoteBlocksAdapter(noteBlocks, appFont) { action ->
            when (action) {
                is NoteBlocksAdapter.Action.AddTextBlock -> {
                    if (action.position <= noteBlocks.size) {
                        noteBlocks.add(action.position, NoteBlock.TextBlock(""))
                        blocksAdapter.notifyItemInserted(action.position)
                        focusBlockAt(action.position)
                    }
                }
                is NoteBlocksAdapter.Action.SplitBlock -> {
                    val newBlock = NoteBlock.TextBlock(action.textForNewBlock)
                    if (action.position <= noteBlocks.size) {
                        noteBlocks.add(action.position, newBlock)
                        blocksAdapter.notifyItemInserted(action.position)
                        rvBlocks.scrollToPosition(action.position)
                        rvBlocks.postDelayed({
                            val viewHolder = rvBlocks.findViewHolderForAdapterPosition(action.position)
                            if (viewHolder is NoteBlocksAdapter.TextViewHolder) {
                                viewHolder.editText.requestFocus()
                                viewHolder.editText.setSelection(0)
                                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                                imm.showSoftInput(viewHolder.editText, InputMethodManager.SHOW_IMPLICIT)
                            }
                        }, 50)
                    }
                }
                is NoteBlocksAdapter.Action.DeleteBlock -> {
                    var targetFocusPos = -1
                    for (i in (action.position - 1) downTo 0) {
                        if (noteBlocks[i] is NoteBlock.TextBlock) {
                            targetFocusPos = i
                            break
                        }
                    }
                    if (targetFocusPos != -1) {
                        if (action.position in noteBlocks.indices) {
                            noteBlocks.removeAt(action.position)
                            blocksAdapter.notifyItemRemoved(action.position)
                            rvBlocks.scrollToPosition(targetFocusPos)
                            focusBlockAt(targetFocusPos)
                        }
                    }
                }
                is NoteBlocksAdapter.Action.MergeWithPrevious -> performMerge(action.position)
                is NoteBlocksAdapter.Action.FocusBlock -> focusBlockAt(action.position)
                is NoteBlocksAdapter.Action.ShowImageOptions -> showImageContextMenu(action.position, action.view)
                is NoteBlocksAdapter.Action.InsertImageFromClipboard -> {
                    val imageBlock = NoteBlock.ImageBlock(uri = action.uri)
                    val insertPos = action.position
                    if (insertPos <= noteBlocks.size) {
                        noteBlocks.add(insertPos, imageBlock)
                        blocksAdapter.notifyItemInserted(insertPos)
                        val textAfter = action.textAfter
                        noteBlocks.add(insertPos + 1, NoteBlock.TextBlock(textAfter))
                        blocksAdapter.notifyItemInserted(insertPos + 1)
                        rvBlocks.scrollToPosition(insertPos + 1)
                        rvBlocks.postDelayed({
                            val viewHolder = rvBlocks.findViewHolderForAdapterPosition(insertPos + 1)
                            if (viewHolder is NoteBlocksAdapter.TextViewHolder) {
                                viewHolder.editText.requestFocus()
                                viewHolder.editText.setSelection(0)
                                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                                imm.showSoftInput(viewHolder.editText, InputMethodManager.SHOW_IMPLICIT)
                            }
                        }, 50)
                    }
                }
            }
        }
        rvBlocks.layoutManager = LinearLayoutManager(this)
        rvBlocks.adapter = blocksAdapter
        rvBlocks.itemAnimator = null
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
        val currentCat = selectedCategory

        return currentTitle != originalTitle ||
                currentContent != originalJsonContent ||
                currentColor != originalColor ||
                currentCat != originalCategory
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
                is NoteBlock.ImageBlock -> mapOf("type" to "image", "uri" to block.uri, "width" to block.widthPercentage)
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
            showImageSourceDialog()
        }
        btnChangeBackground.setOnClickListener { iniciarFlujoCambioFondo() }
        btnToggleChecklist.setOnClickListener { toggleChecklistMode() }
        btnAddTodoItem.setOnClickListener {
            checklistItems.add(ChecklistItem("", false))
            checklistAdapter.notifyItemInserted(checklistItems.size - 1)
            rvChecklist.postDelayed({
                if (checklistItems.isNotEmpty()) rvChecklist.smoothScrollToPosition(checklistItems.size - 1)
            }, 100)
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
            } catch (e: Exception) {
                // Evitamos crash por background start restriction
                e.printStackTrace()
            }
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

            val cat = noteToEdit?.category ?: "General"
            originalCategory = cat
            selectCategoryChip(cat)

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

            selectCategoryChip("General")
            originalCategory = "General"

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
                        val textContent = item["text"] as? String ?: ""
                        noteBlocks.add(NoteBlock.TextBlock(textContent))
                    } else if (type == "image") {
                        val uri = item["uri"] as? String ?: ""
                        val width = (item["width"] as? Number)?.toInt() ?: 100
                        noteBlocks.add(NoteBlock.ImageBlock(uri, width))
                    }
                }
            } catch (e: Exception) {
                noteBlocks.add(NoteBlock.TextBlock(content))
            }
        } else {
            if (content.isNotEmpty()) noteBlocks.add(NoteBlock.TextBlock(content))
            else noteBlocks.add(NoteBlock.TextBlock(""))
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

    private fun toggleChecklistMode() {
        if (!isChecklistMode) {
            checklistItems.clear()
            var i = 0
            while (i < noteBlocks.size) {
                val current = noteBlocks[i]
                val next = if (i + 1 < noteBlocks.size) noteBlocks[i + 1] else null

                if (current is NoteBlock.TextBlock) {
                    if (next is NoteBlock.ImageBlock) {
                        val lines = current.text.split("\n")
                        for (j in 0 until lines.size - 1) {
                            if (lines[j].isNotBlank()) checklistItems.add(ChecklistItem(lines[j], false))
                        }
                        val lastLineText = lines.last()
                        checklistItems.add(ChecklistItem(lastLineText, false, next.uri, next.widthPercentage))
                        i += 2
                    } else {
                        if (current.text.isNotBlank()) {
                            val lines = current.text.split("\n")
                            for (line in lines) {
                                if (line.isNotBlank()) checklistItems.add(ChecklistItem(line, false))
                            }
                        }
                        i++
                    }
                } else if (current is NoteBlock.ImageBlock) {
                    checklistItems.add(ChecklistItem("", false, current.uri, current.widthPercentage))
                    i++
                }
            }
            if (checklistItems.isEmpty()) checklistItems.add(ChecklistItem("", false))
            checklistAdapter.notifyDataSetChanged()
            switchToChecklistMode(true)
        } else {
            noteBlocks.clear()
            for (item in checklistItems) {
                val prefix = if (item.isChecked) "[x] " else ""
                val textContent = prefix + item.text
                if (textContent.isNotBlank()) noteBlocks.add(NoteBlock.TextBlock(textContent))
                if (item.imageUri != null) {
                    noteBlocks.add(NoteBlock.ImageBlock(uri = item.imageUri!!, widthPercentage = item.widthPercentage))
                    noteBlocks.add(NoteBlock.TextBlock(""))
                }
            }
            if (noteBlocks.isEmpty()) noteBlocks.add(NoteBlock.TextBlock(""))
            else if (noteBlocks.first() is NoteBlock.ImageBlock) noteBlocks.add(0, NoteBlock.TextBlock(""))
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
                val newNote = Note(
                    title = title,
                    content = finalContent,
                    date = formattedDate,
                    color = finalBackgroundData,
                    category = selectedCategory
                )
                db.notesDao().insert(newNote)
            } else {
                noteToEdit?.apply {
                    this.title = title
                    this.content = finalContent
                    this.date = formattedDate
                    this.color = finalBackgroundData
                    this.category = selectedCategory
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

    private fun showImageContextMenu(position: Int, anchor: View) {
        val popup = PopupMenu(this, anchor, Gravity.CENTER)
        popup.menu.add("Insertar texto arriba")
        popup.menu.add("Insertar texto abajo")
        popup.menu.add("Tamaño: 100% (Grande)")
        popup.menu.add("Tamaño: 75% (Mediano)")
        popup.menu.add("Tamaño: 50% (Pequeño)")
        popup.menu.add("Copiar")
        popup.menu.add("Cortar")
        popup.menu.add("Eliminar")

        popup.setOnMenuItemClickListener { item ->
            val block = noteBlocks[position]
            val title = item.title.toString()

            if (title == "Insertar texto arriba") {
                noteBlocks.add(position, NoteBlock.TextBlock(""))
                blocksAdapter.notifyItemInserted(position)
                focusBlockAt(position)
                return@setOnMenuItemClickListener true
            }
            if (title == "Insertar texto abajo") {
                val newPos = position + 1
                noteBlocks.add(newPos, NoteBlock.TextBlock(""))
                blocksAdapter.notifyItemInserted(newPos)
                focusBlockAt(newPos)
                return@setOnMenuItemClickListener true
            }

            if (block is NoteBlock.ImageBlock) {
                when {
                    title.contains("100%") -> { block.widthPercentage = 100; blocksAdapter.notifyItemChanged(position); true }
                    title.contains("75%") -> { block.widthPercentage = 75; blocksAdapter.notifyItemChanged(position); true }
                    title.contains("50%") -> { block.widthPercentage = 50; blocksAdapter.notifyItemChanged(position); true }
                    title == "Copiar" -> { copiarImagen(block.uri); true }
                    title == "Cortar" -> { copiarImagen(block.uri); noteBlocks.removeAt(position); blocksAdapter.notifyItemRemoved(position); Toast.makeText(this, "Imagen cortada", Toast.LENGTH_SHORT).show(); true }
                    title == "Eliminar" -> { noteBlocks.removeAt(position); blocksAdapter.notifyItemRemoved(position); true }
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
        Toast.makeText(this, "Imagen copiada", Toast.LENGTH_SHORT).show()
    }

    private fun showImageSourceDialog() {
        val options = arrayOf("Tomar Foto", "Elegir de Galería")
        AlertDialog.Builder(this)
            .setTitle("Agregar Imagen")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> launchCamera()
                    1 -> checkGalleryPermission(PERMISSION_REQUEST_GALLERY)
                }
            }
            .show()
    }

    private fun launchCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 300)
            return
        }
        try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val photoFile = File.createTempFile("JPEG_${timeStamp}_", ".jpg", externalCacheDir)
            tempImageUri = FileProvider.getUriForFile(this, "${applicationContext.packageName}.provider", photoFile)
            takePhotoLauncher.launch(tempImageUri!!)
        } catch (e: Exception) {
            Toast.makeText(this, "Error iniciando cámara", Toast.LENGTH_SHORT).show()
            e.printStackTrace()
        }
    }
}