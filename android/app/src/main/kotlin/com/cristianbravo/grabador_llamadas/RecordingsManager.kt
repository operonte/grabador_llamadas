package com.cristianbravo.grabador_llamadas

import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.util.Log

/** Consulta, abre, comparte y borra las grabaciones guardadas por la app en
 *  Movies/GrabadorLlamadas, para que el usuario no tenga que salir a la Galería. */
class RecordingsManager(private val context: Context) {

    companion object {
        /** Único lugar donde vive el nombre de la carpeta: ScreenRecordService la
         *  usa para guardar, esta clase para filtrar al listar/buscar. */
        const val FOLDER_NAME = "GrabadorLlamadas"
        private const val TAG = "RecordingsManager"
    }

    private val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    fun list(): List<Map<String, Any?>> {
        val results = mutableListOf<Map<String, Any?>>()
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE
        )
        val selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf("%$FOLDER_NAME%")
        val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

        context.contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val uri = ContentUris.withAppendedId(collection, id)
                // MediaStore a veces no tiene la duración calculada todavía justo
                // después de grabar (el escaneo es asíncrono); si viene en 0,
                // la calculamos nosotros mismos para no mostrar "00:00" engañoso.
                val durationMs = cursor.getLong(durationCol).let {
                    if (it > 0) it else readDurationFallback(uri)
                }
                results.add(
                    mapOf(
                        "uri" to uri.toString(),
                        "name" to cursor.getString(nameCol),
                        "dateAddedSeconds" to cursor.getLong(dateCol),
                        "durationMs" to durationMs,
                        "sizeBytes" to cursor.getLong(sizeCol)
                    )
                )
            }
        }
        return results
    }

    private fun readDurationFallback(uri: Uri): Long {
        return try {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(context, uri)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo leer la duración de respaldo para $uri", e)
            0L
        }
    }

    /** Sin este try/catch, un dispositivo sin ninguna app capaz de abrir video/mp4
     *  (o sin apps para compartir) tumba toda la app: una excepción sin capturar
     *  en el handler del MethodChannel de MainActivity mata el proceso. */
    fun open(uriString: String): Boolean {
        val uri = Uri.parse(uriString)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No hay app para abrir $uri", e)
            false
        }
    }

    fun share(uriString: String): Boolean {
        val uri = Uri.parse(uriString)
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(sendIntent, "Compartir grabación").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(chooser)
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No hay app para compartir $uri", e)
            false
        }
    }

    fun delete(uriString: String): Boolean {
        return try {
            context.contentResolver.delete(Uri.parse(uriString), null, null) > 0
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo borrar $uriString", e)
            false
        }
    }
}
