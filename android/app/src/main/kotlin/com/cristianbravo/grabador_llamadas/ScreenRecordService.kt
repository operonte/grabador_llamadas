package com.cristianbravo.grabador_llamadas

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.MediaStore
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScreenRecordService : Service() {

    companion object {
        const val ACTION_START = "com.cristianbravo.grabador_llamadas.START"
        const val ACTION_STOP = "com.cristianbravo.grabador_llamadas.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        // Namespaced (no "screen_record_channel"/1 genéricos): este sería el primer
        // foreground service que declare un proyecto host si esto se integra como
        // función dentro de otra app más grande, y un ID de notificación genérico
        // es el choque más probable con lo que ese host ya tenga.
        private const val CHANNEL_ID = "com.cristianbravo.recorder.capture"
        private const val NOTIFICATION_ID = 847_213

        /** Consultado desde MainActivity para sincronizar la UI de Flutter tras un
         *  cambio de estado que no vino del botón de la app (ej: notificación). */
        var isRecording: Boolean = false
            private set
    }

    private var mediaProjection: MediaProjection? = null
    private var pipeline: CallRecordingPipeline? = null
    private var outputUri: Uri? = null
    private var outputFd: ParcelFileDescriptor? = null

    private var overlayView: View? = null
    private var isPaused = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopRecording()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
                if (data != null) startRecording(resultCode, data) else stopSelf()
            }
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    private fun startRecording(resultCode: Int, data: Intent) {
        if (!hasEnoughStorage()) {
            Toast.makeText(this, "Espacio de almacenamiento insuficiente para grabar", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }

        try {
            startForegroundNotification()

            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = projectionManager.getMediaProjection(resultCode, data)
                ?: throw IllegalStateException("No se pudo iniciar la captura de pantalla")
            mediaProjection = projection
            projection.registerCallback(projectionCallback, null)

            val metrics = DisplayMetrics()
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
            val density = metrics.densityDpi

            // Escalamos a un máximo de 1600px en el lado más largo: el contenido es
            // mayormente UI estática, no video de alto movimiento, así que grabar a la
            // resolución nativa (1440p+ en varios equipos) solo suma peso de archivo y
            // carga de CPU/batería sin ganancia real de legibilidad.
            val maxDimension = 1600
            val longestSide = maxOf(metrics.widthPixels, metrics.heightPixels)
            val scale = if (longestSide > maxDimension) maxDimension.toFloat() / longestSide else 1f
            val width = (metrics.widthPixels * scale).toInt().let { if (it % 2 != 0) it - 1 else it }
            val height = (metrics.heightPixels * scale).toInt().let { if (it % 2 != 0) it - 1 else it }

            val fd = openOutputFile()
            outputFd = fd

            val recordingPipeline = CallRecordingPipeline(this, projection, width, height, density, fd)
            recordingPipeline.start()
            pipeline = recordingPipeline

            isRecording = true
            showOverlayIfAllowed()
        } catch (e: Exception) {
            // prepare()/start() pueden fallar por hardware ocupado, codec no
            // disponible, etc. Sin este catch, una falla acá tumbaba el servicio
            // completo (crash visible para el usuario) en vez de fallar en silencio.
            cleanupAfterFailedStart()
        }
    }

    private fun hasEnoughStorage(): Boolean {
        val minimumFreeBytes = 100L * 1024 * 1024 // 100 MB
        val stat = StatFs(Environment.getExternalStorageDirectory().path)
        return stat.availableBytes > minimumFreeBytes
    }

    private fun cleanupAfterFailedStart() {
        try {
            pipeline?.stop()
        } catch (e: Exception) {
            // ignorar
        }
        pipeline = null

        mediaProjection?.unregisterCallback(projectionCallback)
        mediaProjection?.stop()
        mediaProjection = null

        try {
            outputFd?.close()
        } catch (e: Exception) {
            // ignorar
        }
        outputFd = null

        discardOutputFile()
        isRecording = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun openOutputFile(): ParcelFileDescriptor {
        val fileName = "grabacion_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/${RecordingsManager.FOLDER_NAME}")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = contentResolver.insert(collection, values)
            ?: throw IllegalStateException("No se pudo crear el archivo de video")
        outputUri = uri
        return contentResolver.openFileDescriptor(uri, "w")
            ?: throw IllegalStateException("No se pudo abrir el archivo de video")
    }

    private fun finalizeOutputFile() {
        val uri = outputUri ?: return
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.IS_PENDING, 0)
        }
        contentResolver.update(uri, values, null, null)
        outputUri = null
    }

    /** Borra el registro de MediaStore si la grabación se detuvo tan rápido que
     *  el archivo quedó vacío o corrupto, para no dejar basura en la Galería. */
    private fun discardOutputFile() {
        val uri = outputUri ?: return
        contentResolver.delete(uri, null, null)
        outputUri = null
    }

    private fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        hideOverlay()

        var stopFailed = false
        try {
            pipeline?.stop()
        } catch (e: Exception) {
            // Puede lanzar si se detiene muy rápido tras iniciar; el archivo queda descartable.
            stopFailed = true
        }
        pipeline = null

        mediaProjection?.unregisterCallback(projectionCallback)
        mediaProjection?.stop()
        mediaProjection = null

        try {
            outputFd?.close()
        } catch (e: Exception) {
            // ignorar
        }
        outputFd = null

        if (stopFailed) discardOutputFile() else finalizeOutputFile()

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Grabación de pantalla", NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }

        // packageManager.getLaunchIntentForPackage en vez de nombrar MainActivity
        // directamente: si esto se integra como función dentro de otra app, el
        // Service no puede (ni debe) conocer el nombre de la Activity del host.
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val stopIntent = Intent(this, ScreenRecordService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Grabando pantalla")
            .setContentText("Toca para volver a la app")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .apply { if (contentIntent != null) setContentIntent(contentIntent) }
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Detener", stopPendingIntent)
            .build()

        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    /** La burbuja es opcional: solo aparece si el usuario ya activó "Dibujar sobre
     *  otras apps". Si no, la grabación sigue funcionando igual, solo sin burbuja. */
    private fun showOverlayIfAllowed() {
        if (!Settings.canDrawOverlays(this)) return
        if (overlayView != null) return

        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_controls, null)

        val screenMetrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(screenMetrics)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 200
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        view.findViewById<View>(R.id.dragHandle).setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val newX = initialX + (event.rawX - initialTouchX).toInt()
                    val newY = initialY + (event.rawY - initialTouchY).toInt()
                    // Sin este límite, arrastrar la burbuja hasta el borde la saca
                    // de la pantalla y queda inaccesible (sin forma de recuperarla
                    // salvo detener la grabación desde la notificación).
                    params.x = newX.coerceIn(0, maxOf(0, screenMetrics.widthPixels - view.width))
                    params.y = newY.coerceIn(0, maxOf(0, screenMetrics.heightPixels - view.height))
                    windowManager.updateViewLayout(view, params)
                    true
                }
                else -> false
            }
        }

        view.findViewById<View>(R.id.pauseResumeButton).setOnClickListener { togglePause(view) }
        view.findViewById<View>(R.id.stopButton).setOnClickListener { stopRecording() }

        windowManager.addView(view, params)
        overlayView = view
    }

    private fun togglePause(view: View) {
        val recordingPipeline = pipeline ?: return
        val button = view.findViewById<ImageButton>(R.id.pauseResumeButton)
        if (isPaused) {
            recordingPipeline.resume()
            isPaused = false
            button.setImageResource(android.R.drawable.ic_media_pause)
            button.contentDescription = "Pausar"
        } else {
            recordingPipeline.pause()
            isPaused = true
            button.setImageResource(android.R.drawable.ic_media_play)
            button.contentDescription = "Reanudar"
        }
    }

    private fun hideOverlay() {
        val view = overlayView ?: return
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        try {
            windowManager.removeView(view)
        } catch (e: IllegalArgumentException) {
            // La vista ya no estaba adjunta; nada que hacer.
        }
        overlayView = null
        isPaused = false
    }

    /** Algunos fabricantes (este equipo incluido, se ve en logcat como
     *  "moto_freezer") matan el proceso agresivamente poco después de que el
     *  usuario quita la app de recientes, sin darle tiempo a onDestroy(). Sin
     *  cortar la grabación acá, CallRecordingPipeline nunca llega a restaurar
     *  AudioManager.mode y el dispositivo queda forzado en modo "llamada" hasta
     *  reiniciar — un bug de audio de todo el equipo, no solo de esta función. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        stopRecording()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopRecording()
        super.onDestroy()
    }
}
