package cl.example.mynotes

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object BackupHelper {

    // Función principal que se llama desde la Activity
    suspend fun createBackup(context: Context, notes: List<Note>): File? {
        val backupDir = File(context.cacheDir, "backup_temp")
        if (backupDir.exists()) backupDir.deleteRecursively()
        backupDir.mkdirs()

        try {
            // 1. Crear el archivo JSON con los textos
            val jsonArray = JSONArray()
            val imagesToZip = ArrayList<String>()

            for (note in notes) {
                val jsonNote = JSONObject().apply {
                    put("id", note.id)
                    put("title", note.title)
                    put("content", note.content)
                    put("date", note.date)
                    put("color", note.color)
                }
                jsonArray.put(jsonNote)

                // Buscar imágenes dentro del contenido (Rich Text)
                val pattern = java.util.regex.Pattern.compile("\\[IMG:(.*?)\\]")
                val matcher = pattern.matcher(note.content)
                while (matcher.find()) {
                    val uriString = matcher.group(1)
                    if (uriString != null) imagesToZip.add(uriString)
                }
            }

            // Guardar JSON en disco
            val jsonFile = File(backupDir, "notes_data.json")
            jsonFile.writeText(jsonArray.toString())

            // 2. Crear el archivo ZIP final
            val zipFile = File(context.getExternalFilesDir(null), "MyNotes_Backup.zip")
            val zos = ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile)))

            // A. Agregar el JSON al ZIP
            addFileToZip(zos, jsonFile, "notes_data.json")

            // B. Agregar las imágenes al ZIP
            for (uriString in imagesToZip) {
                try {
                    val uri = Uri.parse(uriString)
                    // Copiamos la imagen desde su origen al ZIP
                    val inputStream = context.contentResolver.openInputStream(uri)
                    if (inputStream != null) {
                        // Usamos el nombre del archivo original o uno genérico
                        val fileName = "img_${System.currentTimeMillis()}_${uri.lastPathSegment ?: "image.jpg"}"
                        val origin = BufferedInputStream(inputStream)

                        val entry = ZipEntry("images/$fileName")
                        zos.putNextEntry(entry)

                        origin.copyTo(zos)

                        origin.close()
                        inputStream.close()
                        zos.closeEntry()
                    }
                } catch (e: Exception) {
                    Log.e("Backup", "Error zipping image: $uriString", e)
                }
            }

            zos.close()
            return zipFile

        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    private fun addFileToZip(zos: ZipOutputStream, file: File, entryName: String) {
        val buffer = ByteArray(1024)
        val fis = FileInputStream(file)
        val origin = BufferedInputStream(fis, 1024)
        val entry = ZipEntry(entryName)
        zos.putNextEntry(entry)
        var count: Int
        while (origin.read(buffer, 0, 1024).also { count = it } != -1) {
            zos.write(buffer, 0, count)
        }
        origin.close()
    }
}