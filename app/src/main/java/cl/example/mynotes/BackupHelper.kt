package cl.example.mynotes

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object BackupHelper {

    // --- PARTE 1: EXPORTAR (CREAR ZIP) ---
    suspend fun createBackup(context: Context, notes: List<Note>): File? {
        val backupDir = File(context.cacheDir, "backup_temp")
        if (backupDir.exists()) backupDir.deleteRecursively()
        backupDir.mkdirs()

        try {
            val jsonArray = JSONArray()
            val imagesToZip = HashMap<String, String>() // Mapa: URI Original -> Nombre Archivo

            for (note in notes) {
                // Clonamos el contenido para modificarlo (Rutas relativas)
                var exportContent = note.content

                // Buscamos las imágenes en el texto
                val pattern = java.util.regex.Pattern.compile("\\[IMG:(.*?)\\]")
                val matcher = pattern.matcher(note.content)

                while (matcher.find()) {
                    val originalUriString = matcher.group(1)
                    if (originalUriString != null) {
                        // Generamos un nombre único para el ZIP
                        val fileName = "img_${System.currentTimeMillis()}_${originalUriString.hashCode()}.jpg"

                        // Guardamos en el mapa para procesar después
                        imagesToZip[originalUriString] = fileName

                        // Reemplazamos en el JSON: [IMG:content://...] -> [IMG_ZIP:img_123.jpg]
                        exportContent = exportContent.replace(
                            "[IMG:$originalUriString]",
                            "[IMG_ZIP:$fileName]"
                        )
                    }
                }

                val jsonNote = JSONObject().apply {
                    put("title", note.title)
                    put("content", exportContent) // Usamos el contenido modificado
                    put("date", note.date)
                    put("color", note.color)
                    // No guardamos ID para que al importar sean notas nuevas
                }
                jsonArray.put(jsonNote)
            }

            // Guardar JSON
            val jsonFile = File(backupDir, "notes_data.json")
            jsonFile.writeText(jsonArray.toString())

            // Crear ZIP
            val zipFile = File(context.getExternalFilesDir(null), "MyNotes_Backup.zip")
            val zos = ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile)))

            // Agregar JSON
            addFileToZip(zos, jsonFile, "notes_data.json")

            // Agregar Imágenes
            for ((uriString, fileName) in imagesToZip) {
                try {
                    val uri = Uri.parse(uriString)
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        zos.putNextEntry(ZipEntry("images/$fileName"))
                        input.copyTo(zos)
                        zos.closeEntry()
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }

            zos.close()
            return zipFile

        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    // --- PARTE 2: IMPORTAR (LEER ZIP) ---
    suspend fun restoreBackup(context: Context, zipUri: Uri): List<Note>? {
        val tempDir = File(context.cacheDir, "restore_temp")
        if (tempDir.exists()) tempDir.deleteRecursively()
        tempDir.mkdirs()

        val imagesDir = File(context.filesDir, "imported_images") // Almacenamiento persistente
        if (!imagesDir.exists()) imagesDir.mkdirs()

        try {
            // 1. DESCOMPRIMIR
            context.contentResolver.openInputStream(zipUri)?.use { fis ->
                val zis = ZipInputStream(BufferedInputStream(fis))
                var entry: ZipEntry?
                while (zis.nextEntry.also { entry = it } != null) {
                    val file = File(tempDir, entry!!.name)
                    if (entry!!.isDirectory) {
                        file.mkdirs()
                    } else {
                        file.parentFile?.mkdirs()
                        FileOutputStream(file).use { fos -> zis.copyTo(fos) }
                    }
                }
            }

            // 2. LEER JSON
            val jsonFile = File(tempDir, "notes_data.json")
            if (!jsonFile.exists()) return null

            val jsonContent = jsonFile.readText()
            val jsonArray = JSONArray(jsonContent)
            val restoredNotes = ArrayList<Note>()

            // 3. PROCESAR NOTAS E IMÁGENES
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                var content = obj.optString("content")

                // Buscar etiquetas [IMG_ZIP:archivo.jpg]
                val pattern = java.util.regex.Pattern.compile("\\[IMG_ZIP:(.*?)\\]")
                val matcher = pattern.matcher(content)
                val replacements = HashMap<String, String>()

                while (matcher.find()) {
                    val zipFileName = matcher.group(1) // nombre archivo dentro del zip
                    // Buscar ese archivo en la carpeta descomprimida
                    val imgFileTemp = File(tempDir, "images/$zipFileName")

                    if (imgFileTemp.exists()) {
                        // MOVER A CARPETA FINAL
                        val finalFile = File(imagesDir, "imported_${System.currentTimeMillis()}_$zipFileName")
                        imgFileTemp.copyTo(finalFile, overwrite = true)

                        // OBTENER NUEVA URI (FileProvider o Uri de archivo)
                        // Para uso interno de la app, "file://" o FileProvider funciona
                        val newUri = FileProvider.getUriForFile(context, "${context.packageName}.provider", finalFile)

                        replacements["[IMG_ZIP:$zipFileName]"] = "[IMG:$newUri]"
                    }
                }

                // Aplicar reemplazos en el texto
                for ((oldTag, newTag) in replacements) {
                    content = content.replace(oldTag, newTag)
                }

                val note = Note(
                    title = obj.optString("title"),
                    content = content,
                    date = obj.optString("date"),
                    color = obj.optString("color")
                )
                restoredNotes.add(note)
            }

            return restoredNotes

        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    private fun addFileToZip(zos: ZipOutputStream, file: File, entryName: String) {
        val fis = FileInputStream(file)
        val entry = ZipEntry(entryName)
        zos.putNextEntry(entry)
        fis.copyTo(zos)
        fis.close()
        zos.closeEntry()
    }
}