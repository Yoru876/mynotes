package cl.example.mynotes

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object BackupManager {

    private val gson = Gson()

    // Clase auxiliar simple para leer el JSON sin lógica de negocio
    data class BackupNote(
        val title: String,
        val date: String,
        val color: String, // Asumimos que siempre habrá un string (aunque sea el default)
        var content: String
    )

    // ============================================================================================
    // PARTE 1: EXPORTAR (CREAR ZIP)
    // ============================================================================================
    fun exportBackup(context: Context, notes: List<Note>, destUri: Uri): Boolean {
        val tempDir = File(context.cacheDir, "backup_temp")
        val imagesDir = File(tempDir, "images")

        tempDir.deleteRecursively()
        tempDir.mkdirs()
        imagesDir.mkdirs()

        try {
            val backupList = mutableListOf<BackupNote>()

            for (note in notes) {
                // 1. Procesar contenido
                val processedContent = processContentForExport(context, note.content, imagesDir)

                // 2. Procesar Fondo (CORRECCIÓN AQUÍ)
                // Si note.color es null, usamos "#FFFFFF" por defecto.
                // Esto elimina los errores de "nullable receiver"
                var processedColor: String = note.color ?: "#FFFFFF"

                if (processedColor.startsWith("content://") || processedColor.startsWith("file://")) {
                    val bgName = "bg_${UUID.randomUUID()}.jpg"
                    val destBgFile = File(imagesDir, bgName)
                    copyUriToFile(context, Uri.parse(processedColor), destBgFile)
                    processedColor = "img_backup://$bgName"
                }

                // Ahora processedColor es String (no null), así que no da error aquí
                backupList.add(BackupNote(note.title, note.date, processedColor, processedContent))
            }

            // 3. Guardar el JSON maestro
            val jsonFile = File(tempDir, "notes_data.json")
            jsonFile.writeText(gson.toJson(backupList))

            // 4. Crear el ZIP final
            context.contentResolver.openOutputStream(destUri)?.use { outputStream ->
                ZipOutputStream(BufferedOutputStream(outputStream)).use { zipOut ->
                    zipFolder(tempDir, zipOut, tempDir.absolutePath.length + 1)
                }
            }

            tempDir.deleteRecursively()
            return true

        } catch (e: Exception) {
            e.printStackTrace()
            tempDir.deleteRecursively()
            return false
        }
    }

    // ============================================================================================
    // PARTE 2: IMPORTAR (LEER ZIP)
    // ============================================================================================
    fun importBackup(context: Context, sourceUri: Uri): List<Note>? {
        val tempDir = File(context.cacheDir, "restore_temp")
        tempDir.deleteRecursively()
        tempDir.mkdirs()

        try {
            // 1. Descomprimir ZIP
            context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                ZipInputStream(BufferedInputStream(inputStream)).use { zipIn ->
                    var entry = zipIn.nextEntry
                    while (entry != null) {
                        val filePath = File(tempDir, entry.name)
                        if (!entry.isDirectory) {
                            filePath.parentFile?.mkdirs()
                            FileOutputStream(filePath).use { fos -> zipIn.copyTo(fos) }
                        }
                        zipIn.closeEntry()
                        entry = zipIn.nextEntry
                    }
                }
            }

            // 2. Leer JSON
            val jsonFile = File(tempDir, "notes_data.json")
            if (!jsonFile.exists()) return null

            val type = object : TypeToken<List<BackupNote>>() {}.type
            val backupList: List<BackupNote> = gson.fromJson(jsonFile.readText(), type)

            val restoredNotes = mutableListOf<Note>()
            val imagesDir = File(tempDir, "images")

            // 3. Reconstruir notas
            for (backupNote in backupList) {
                val restoredContent = processContentForImport(context, backupNote.content, imagesDir)

                var restoredColor = backupNote.color
                if (restoredColor.startsWith("img_backup://")) {
                    val bgName = restoredColor.replace("img_backup://", "")
                    val sourceBg = File(imagesDir, bgName)
                    if (sourceBg.exists()) {
                        val newUri = saveToInternalStorage(context, sourceBg)
                        restoredColor = newUri.toString()
                    } else {
                        restoredColor = "#FFFFFF"
                    }
                }

                restoredNotes.add(Note(
                    title = backupNote.title,
                    content = restoredContent,
                    date = backupNote.date,
                    color = restoredColor
                ))
            }

            tempDir.deleteRecursively()
            return restoredNotes

        } catch (e: Exception) {
            e.printStackTrace()
            tempDir.deleteRecursively()
            return null
        }
    }

    // ============================================================================================
    // FUNCIONES AUXILIARES
    // ============================================================================================

    private fun processContentForExport(context: Context, content: String, destDir: File): String {
        val isChecklist = content.startsWith("{checklist:true}")
        val jsonString = if (isChecklist) content.replace("{checklist:true}", "") else content

        val listType = object : TypeToken<List<Map<String, Any>>>() {}.type
        val rawList: MutableList<MutableMap<String, Any>> = gson.fromJson(jsonString, listType)

        for (item in rawList) {
            val uriKey = if (item.containsKey("uri")) "uri" else if (item.containsKey("imageUri")) "imageUri" else null

            if (uriKey != null) {
                val uriStr = item[uriKey] as? String
                if (uriStr != null && (uriStr.startsWith("content://") || uriStr.startsWith("file://"))) {
                    val fileName = "img_${UUID.randomUUID()}.jpg"
                    val destFile = File(destDir, fileName)
                    copyUriToFile(context, Uri.parse(uriStr), destFile)
                    item[uriKey] = "img_backup://$fileName"
                }
            }
        }

        val newJson = gson.toJson(rawList)
        return if (isChecklist) "{checklist:true}$newJson" else newJson
    }

    private fun processContentForImport(context: Context, content: String, sourceDir: File): String {
        val isChecklist = content.startsWith("{checklist:true}")
        val jsonString = if (isChecklist) content.replace("{checklist:true}", "") else content

        val listType = object : TypeToken<List<Map<String, Any>>>() {}.type
        val rawList: MutableList<MutableMap<String, Any>> = gson.fromJson(jsonString, listType)

        for (item in rawList) {
            val uriKey = if (item.containsKey("uri")) "uri" else if (item.containsKey("imageUri")) "imageUri" else null

            if (uriKey != null) {
                val relativePath = item[uriKey] as? String
                if (relativePath != null && relativePath.startsWith("img_backup://")) {
                    val imageName = relativePath.replace("img_backup://", "")
                    val sourceImg = File(sourceDir, imageName)

                    if (sourceImg.exists()) {
                        val newUri = saveToInternalStorage(context, sourceImg)
                        item[uriKey] = newUri.toString()
                    }
                }
            }
        }

        val newJson = gson.toJson(rawList)
        return if (isChecklist) "{checklist:true}$newJson" else newJson
    }

    private fun copyUriToFile(context: Context, uri: Uri, destFile: File) {
        try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return
            val outputStream = FileOutputStream(destFile)
            inputStream.use { input ->
                outputStream.use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun saveToInternalStorage(context: Context, sourceFile: File): Uri {
        val newName = "restored_${System.currentTimeMillis()}_${UUID.randomUUID()}.jpg"
        val destFile = File(context.filesDir, newName)
        sourceFile.copyTo(destFile, overwrite = true)
        return Uri.fromFile(destFile)
    }

    private fun zipFolder(fileToZip: File, zipOut: ZipOutputStream, basePathLength: Int) {
        if (fileToZip.isDirectory) {
            val children = fileToZip.listFiles() ?: return
            for (childFile in children) {
                zipFolder(childFile, zipOut, basePathLength)
            }
            return
        }
        val fis = FileInputStream(fileToZip)
        val entryName = fileToZip.path.substring(basePathLength)
        val zipEntry = ZipEntry(entryName)
        zipOut.putNextEntry(zipEntry)
        val bytes = ByteArray(1024)
        var length: Int
        while (fis.read(bytes).also { length = it } >= 0) {
            zipOut.write(bytes, 0, length)
        }
        fis.close()
    }
}