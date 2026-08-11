package com.cristianbravo.grabador_llamadas

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import androidx.annotation.NonNull
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val channelName = "grabador_llamadas/screen_record"
    private val requestCodeScreenCapture = 1001
    private val requestCodePermissions = 1002
    private var pendingResult: MethodChannel.Result? = null
    private var pendingPermissionResult: MethodChannel.Result? = null
    private val recordingsManager by lazy { RecordingsManager(this) }

    override fun configureFlutterEngine(@NonNull flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channelName).setMethodCallHandler { call, result ->
            when (call.method) {
                "requestPermissions" -> {
                    pendingPermissionResult = result
                    requestNeededPermissions()
                }
                "startRecording" -> {
                    pendingResult = result
                    val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    startActivityForResult(manager.createScreenCaptureIntent(), requestCodeScreenCapture)
                }
                "stopRecording" -> {
                    startService(Intent(this, ScreenRecordService::class.java).apply {
                        action = ScreenRecordService.ACTION_STOP
                    })
                    result.success(true)
                }
                "isRecording" -> result.success(ScreenRecordService.isRecording)
                "openBatterySettings" -> {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", packageName, null)
                    })
                    result.success(true)
                }
                "canDrawOverlays" -> result.success(Settings.canDrawOverlays(this))
                "requestOverlayPermission" -> {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                    result.success(true)
                }
                "isAccessibilityServiceEnabled" -> result.success(isAccessibilityServiceEnabled())
                "openAccessibilitySettings" -> {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    result.success(true)
                }
                "listRecordings" -> {
                    // list() puede hacer I/O bloqueante (MediaMetadataRetriever como
                    // respaldo de duración) para grabaciones que MediaStore todavía
                    // no escaneó; se saca del hilo principal para no arriesgar un ANR.
                    Thread {
                        val recordings = recordingsManager.list()
                        runOnUiThread { result.success(recordings) }
                    }.start()
                }
                "openRecording" -> result.success(recordingsManager.open(call.argument<String>("uri")!!))
                "shareRecording" -> result.success(recordingsManager.share(call.argument<String>("uri")!!))
                "deleteRecording" -> result.success(recordingsManager.delete(call.argument<String>("uri")!!))
                else -> result.notImplemented()
            }
        }
    }

    /** Android no expone una API directa para esto: hay que revisar la lista de
     *  servicios de accesibilidad habilitados en Settings.Secure. */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = ComponentName(this, RecorderAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':').apply { setString(enabled) }
        while (splitter.hasNext()) {
            if (splitter.next().equals(expected, ignoreCase = true)) return true
        }
        return false
    }

    private fun requestNeededPermissions() {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            pendingPermissionResult?.success(true)
            pendingPermissionResult = null
        } else {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), requestCodePermissions)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != requestCodePermissions) return
        val micIndex = permissions.indexOf(Manifest.permission.RECORD_AUDIO)
        val micGranted = micIndex == -1 || grantResults.getOrNull(micIndex) == PackageManager.PERMISSION_GRANTED
        pendingPermissionResult?.success(micGranted)
        pendingPermissionResult = null
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != requestCodeScreenCapture) return

        if (resultCode == Activity.RESULT_OK && data != null) {
            val serviceIntent = Intent(this, ScreenRecordService::class.java).apply {
                action = ScreenRecordService.ACTION_START
                putExtra(ScreenRecordService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenRecordService.EXTRA_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            pendingResult?.success(true)
        } else {
            pendingResult?.success(false)
        }
        pendingResult = null
    }
}
