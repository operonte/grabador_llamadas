package com.cristianbravo.grabador_llamadas

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Codifica pantalla + audio a MP4 con MediaCodec/MediaMuxer en vez de
 * MediaRecorder. Se necesita este control manual porque mezclamos dos
 * fuentes de audio que MediaRecorder no admite combinar:
 *
 * - El micrófono físico (`AudioSource.MIC`): capta tu voz.
 * - Captura de audio interno (`AudioPlaybackCaptureConfiguration`): capta lo
 *   que reproduce Meet/WhatsApp/etc., que es la única vía pública sin root
 *   para escuchar al interlocutor, ya que Android excluye de esta API el
 *   audio marcado `USAGE_VOICE_COMMUNICATION` pero Meet reproduce la voz
 *   remota como `USAGE_MEDIA` normal.
 *
 * Nota: mientras la llamada mantenga su propia sesión de micrófono activa
 * (privacy-sensitive), Android silencia el `AudioSource.MIC` de esta app por
 * completo (ver README) — la mezcla igual sigue funcionando porque sumar
 * silencio no daña la otra fuente.
 */
class CallRecordingPipeline(
    private val context: Context,
    private val projection: MediaProjection,
    private val width: Int,
    private val height: Int,
    private val densityDpi: Int,
    private val outputFd: ParcelFileDescriptor,
) {
    companion object {
        private const val TAG = "CallRecordingPipeline"
        private const val VIDEO_MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val AUDIO_MIME = MediaFormat.MIMETYPE_AUDIO_AAC
        private const val VIDEO_BITRATE = 5_000_000
        private const val VIDEO_FRAME_RATE = 30
        private const val VIDEO_I_FRAME_INTERVAL = 1
        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val AUDIO_BITRATE = 128_000
        private const val TIMEOUT_US = 10_000L
        private const val CHUNK_SHORTS = 2048
        private const val MAX_EOS_DRAIN_ATTEMPTS = 200
    }

    private lateinit var videoEncoder: MediaCodec
    private lateinit var audioEncoder: MediaCodec
    private lateinit var videoInputSurface: Surface
    private lateinit var muxer: MediaMuxer

    private var virtualDisplay: VirtualDisplay? = null
    private var micRecord: AudioRecord? = null
    private var playbackRecord: AudioRecord? = null

    private val muxerLock = Object()
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private val muxerStarted = AtomicBoolean(false)

    private val running = AtomicBoolean(false)
    @Volatile private var paused = false

    /** El primer presentationTimeUs que entrega el codificador de video viene
     *  del reloj de actividad del dispositivo (System.nanoTime()), un número
     *  enorme sin relación con el audio, que nosotros sí arrancamos en 0. Sin
     *  restar este offset, el archivo queda con una duración disparatada y el
     *  video se ve "congelado" porque casi todo el rango de tiempo del track
     *  de video no tiene frames reales. */
    private var videoPtsOffsetUs: Long = -1L

    private var videoThread: Thread? = null
    private var audioThread: Thread? = null
    private var previousAudioMode: Int = AudioManager.MODE_NORMAL

    fun start() {
        muxer = MediaMuxer(outputFd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        setupVideoEncoder()
        setupAudioEncoder()
        forceInCommunicationMode()
        setupAudioCapture()

        running.set(true)
        // Un fallo inesperado en estos hilos no debe tumbar toda la app (comportamiento
        // por defecto de Android ante una excepción no capturada en cualquier hilo):
        // preferimos que la grabación se corte en silencio a que la app se cierre.
        videoThread = Thread({
            try { runVideoLoop() } catch (e: Throwable) {
                Log.e(TAG, "Hilo de video terminó por una excepción", e)
                running.set(false)
            }
        }, "CallRecording-Video").apply { start() }
        audioThread = Thread({
            try { runAudioLoop() } catch (e: Throwable) {
                Log.e(TAG, "Hilo de audio terminó por una excepción", e)
                running.set(false)
            }
        }, "CallRecording-Audio").apply { start() }
    }

    fun pause() {
        paused = true
        virtualDisplay?.surface = null
    }

    fun resume() {
        virtualDisplay?.surface = videoInputSurface
        paused = false
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        videoThread?.join(2000)
        audioThread?.join(2000)

        virtualDisplay?.release()
        virtualDisplay = null

        try { micRecord?.stop() } catch (e: Exception) { /* ignorar */ }
        micRecord?.release()
        try { playbackRecord?.stop() } catch (e: Exception) { /* ignorar */ }
        playbackRecord?.release()

        try { videoEncoder.stop() } catch (e: Exception) { /* ignorar */ }
        videoEncoder.release()
        try { audioEncoder.stop() } catch (e: Exception) { /* ignorar */ }
        audioEncoder.release()

        restoreAudioMode()

        synchronized(muxerLock) {
            if (muxerStarted.get()) {
                try { muxer.stop() } catch (e: Exception) { /* ignorar */ }
            }
        }
        muxer.release()
    }

    private fun setupVideoEncoder() {
        val format = MediaFormat.createVideoFormat(VIDEO_MIME, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, VIDEO_I_FRAME_INTERVAL)
        }
        videoEncoder = MediaCodec.createEncoderByType(VIDEO_MIME)
        videoEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        videoInputSurface = videoEncoder.createInputSurface()
        videoEncoder.start()

        virtualDisplay = projection.createVirtualDisplay(
            "GrabadorLlamadasDisplay",
            width, height, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            videoInputSurface, null, null
        )
    }

    private fun setupAudioEncoder() {
        val format = MediaFormat.createAudioFormat(AUDIO_MIME, SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
            // Sin esto, el códec reserva el tamaño de buffer de entrada que le
            // parezca (a veces menor a un chunk nuestro de CHUNK_SHORTS), y
            // escribir de más con putShort() revienta con BufferOverflowException
            // en el hilo de audio, lo que tumba toda la app.
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CHUNK_SHORTS * 2)
        }
        audioEncoder = MediaCodec.createEncoderByType(AUDIO_MIME)
        audioEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        audioEncoder.start()
    }

    /** Truco documentado por apps de grabación de llamadas (ej. Cube ACR) para
     *  Android 9-14: forzar el modo de audio del sistema a "en comunicación" antes
     *  de abrir el micrófono. Es una configuración GLOBAL del dispositivo (no solo
     *  de esta app) y no está garantizado que sirva — varía por fabricante — pero
     *  ya se agotaron las vías oficiales (ver README). Se restaura al detener. */
    private fun forceInCommunicationMode() {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            previousAudioMode = audioManager.mode
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo forzar el modo de audio", e)
        }
    }

    private fun restoreAudioMode() {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.mode = previousAudioMode
        } catch (e: Exception) {
            // ignorar
        }
    }

    /** El micrófono es obligatorio (comportamiento previo); la captura de
     *  audio interno es un extra que se suma si el dispositivo la admite.
     *  Se usa VOICE_RECOGNITION en vez de MIC: es la fuente que Cube ACR
     *  documenta como la que a veces logra evitar el silenciamiento por
     *  captura concurrente en Android 10-14 combinada con el modo forzado
     *  arriba. No hay garantía de que funcione en todos los equipos. */
    private fun setupAudioCapture() {
        val minBufSize = maxOf(
            AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_ENCODING),
            CHUNK_SHORTS * 2
        )
        val bufferSizeBytes = minBufSize * 2

        micRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_ENCODING, bufferSizeBytes
            ).apply {
                if (state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException()
                startRecording()
            }
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo inicializar el micrófono", e)
            null
        }

        playbackRecord = try {
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .build()
            val audioFormat = AudioFormat.Builder()
                .setEncoding(AUDIO_ENCODING)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNEL_CONFIG)
                .build()
            AudioRecord.Builder()
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSizeBytes)
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()
                .apply {
                    if (state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException()
                    startRecording()
                }
        } catch (e: Exception) {
            // Algunos dispositivos/ROMs restringidos no admiten captura de
            // audio interno; seguimos solo con el micrófono en vez de que
            // falle toda la grabación.
            Log.w(TAG, "Captura de audio interno no disponible, sigo solo con el micrófono", e)
            null
        }
    }

    private fun runVideoLoop() {
        val bufferInfo = MediaCodec.BufferInfo()
        while (running.get()) {
            drainVideoEncoder(bufferInfo, endOfStream = false)
            Thread.sleep(10)
        }
        try { videoEncoder.signalEndOfInputStream() } catch (e: Exception) { /* ignorar */ }
        drainVideoEncoder(bufferInfo, endOfStream = true)
    }

    private fun drainVideoEncoder(bufferInfo: MediaCodec.BufferInfo, endOfStream: Boolean) {
        var attempts = 0
        while (true) {
            val outputIndex = videoEncoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            when {
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    synchronized(muxerLock) {
                        videoTrackIndex = muxer.addTrack(videoEncoder.outputFormat)
                        maybeStartMuxer()
                    }
                }
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream || attempts++ > MAX_EOS_DRAIN_ATTEMPTS) return
                }
                outputIndex >= 0 -> {
                    val encodedData = videoEncoder.getOutputBuffer(outputIndex)
                    val isConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (encodedData != null && bufferInfo.size > 0 && !isConfig && muxerStarted.get()) {
                        if (videoPtsOffsetUs < 0) videoPtsOffsetUs = bufferInfo.presentationTimeUs
                        bufferInfo.presentationTimeUs =
                            (bufferInfo.presentationTimeUs - videoPtsOffsetUs).coerceAtLeast(0)
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        synchronized(muxerLock) { muxer.writeSampleData(videoTrackIndex, encodedData, bufferInfo) }
                    }
                    videoEncoder.releaseOutputBuffer(outputIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    private fun runAudioLoop() {
        val micBuf = ShortArray(CHUNK_SHORTS)
        val pbBuf = ShortArray(CHUNK_SHORTS)
        val mixed = ShortArray(CHUNK_SHORTS)
        val bufferInfo = MediaCodec.BufferInfo()
        var samplesWritten = 0L
        val mic = micRecord
        val pb = playbackRecord

        while (running.get()) {
            val micRead = mic?.read(micBuf, 0, CHUNK_SHORTS, AudioRecord.READ_BLOCKING) ?: -1
            val pbRead = pb?.read(pbBuf, 0, CHUNK_SHORTS, AudioRecord.READ_BLOCKING) ?: -1
            val frames = maxOf(if (micRead > 0) micRead else 0, if (pbRead > 0) pbRead else 0)
            if (frames == 0) continue

            for (i in 0 until frames) {
                val a = if (micRead > i) micBuf[i].toInt() else 0
                val b = if (pbRead > i) pbBuf[i].toInt() else 0
                mixed[i] = (a + b).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }

            if (!paused) {
                feedAudioEncoder(mixed, frames, samplesWritten)
                samplesWritten += frames
            }
            drainAudioEncoder(bufferInfo, endOfStream = false)
        }

        feedAudioEncoderEos(samplesWritten)
        drainAudioEncoder(bufferInfo, endOfStream = true)
    }

    /** Escribe en trozos según el espacio real del buffer de entrada: el tamaño
     *  que el códec reserva de verdad varía por dispositivo, y escribir más de
     *  lo que entra revienta con BufferOverflowException (ver KEY_MAX_INPUT_SIZE
     *  arriba — esto es la red de seguridad además de esa configuración). */
    private fun feedAudioEncoder(pcm: ShortArray, frameCount: Int, sampleOffset: Long) {
        var offset = 0
        var sampleIndex = sampleOffset
        while (offset < frameCount) {
            val inputIndex = audioEncoder.dequeueInputBuffer(TIMEOUT_US)
            if (inputIndex < 0) continue
            val inputBuffer = audioEncoder.getInputBuffer(inputIndex) ?: continue
            inputBuffer.clear()
            val toWrite = minOf(inputBuffer.remaining() / 2, frameCount - offset)
            for (i in 0 until toWrite) inputBuffer.putShort(pcm[offset + i])
            val presentationTimeUs = sampleIndex * 1_000_000L / SAMPLE_RATE
            audioEncoder.queueInputBuffer(inputIndex, 0, toWrite * 2, presentationTimeUs, 0)
            offset += toWrite
            sampleIndex += toWrite
        }
    }

    private fun feedAudioEncoderEos(sampleOffset: Long) {
        val inputIndex = audioEncoder.dequeueInputBuffer(TIMEOUT_US)
        if (inputIndex < 0) return
        val presentationTimeUs = sampleOffset * 1_000_000L / SAMPLE_RATE
        audioEncoder.queueInputBuffer(inputIndex, 0, 0, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
    }

    private fun drainAudioEncoder(bufferInfo: MediaCodec.BufferInfo, endOfStream: Boolean) {
        var attempts = 0
        while (true) {
            val outputIndex = audioEncoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            when {
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    synchronized(muxerLock) {
                        audioTrackIndex = muxer.addTrack(audioEncoder.outputFormat)
                        maybeStartMuxer()
                    }
                }
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream || attempts++ > MAX_EOS_DRAIN_ATTEMPTS) return
                }
                outputIndex >= 0 -> {
                    val encodedData = audioEncoder.getOutputBuffer(outputIndex)
                    if (encodedData != null && bufferInfo.size > 0 && muxerStarted.get()) {
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        synchronized(muxerLock) { muxer.writeSampleData(audioTrackIndex, encodedData, bufferInfo) }
                    }
                    audioEncoder.releaseOutputBuffer(outputIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    /** Debe llamarse ya dentro de `synchronized(muxerLock)`. */
    private fun maybeStartMuxer() {
        if (!muxerStarted.get() && videoTrackIndex >= 0 && audioTrackIndex >= 0) {
            muxer.start()
            muxerStarted.set(true)
        }
    }
}
