package br.com.chatnoir.ggwave_kotlin

import android.Manifest
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class GGWaveCodec private constructor(
    private val params: GGWaveParameters,
    private val protocolId: Int,
    private val volume: Int
) {
    private var continueListeningFlow: Boolean = true

    /**
     * PCM 버퍼 콜백.
     * AudioRecord.read() 직후, GGWave 디코딩 전에 호출됩니다.
     * 파라미터: (buffer: ByteArray, readBytes: Int)
     *
     * 용도: 실제 마이크 PCM 레벨(maxAmp/RMS)을 외부에서 읽기 위한 훅.
     * 주의: IO 스레드에서 호출되므로 UI 접근 시 메인 스레드로 전환 필요.
     *
     * 사용 예:
     *   codec.onPcmBuffer = { buf, len ->
     *       val shorts = ByteBuffer.wrap(buf, 0, len)
     *           .order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
     *       var maxAmp = 0
     *       repeat(shorts.limit()) { maxAmp = maxOf(maxAmp, Math.abs(shorts.get().toInt())) }
     *       val normalized = maxAmp / 32768f  // 0.0 ~ 1.0
     *   }
     */
    var onPcmBuffer: ((buffer: ByteArray, readBytes: Int) -> Unit)? = null

    class Builder {
        private var sampleRate: Float = 48000f
        private var sampleFormatInp: Byte = GGWaveSampleFormat.I16
        private var sampleFormatOut: Byte = GGWaveSampleFormat.I16
        private var samplesPerFrame: Int? = null
        private var protocolId: Int = GGWaveProtocolId.AUDIBLE_FAST
        private var volume: Int = 5

        fun sampleRate(value: Float) = apply { sampleRate = value }
        fun sampleFormatInp(value: Byte) = apply { sampleFormatInp = value }
        fun sampleFormatOut(value: Byte) = apply { sampleFormatOut = value }
        fun samplesPerFrame(value: Int) = apply { samplesPerFrame = value }
        fun protocolId(value: Int) = apply { protocolId = value }
        fun volume(value: Int) = apply { volume = value }

        fun build(): GGWaveCodec = GGWaveCodec(
            params = GGWaveWrapper.getDefaultParameters().copy(
                sampleRate = sampleRate,
                sampleRateInp = sampleRate,
                sampleRateOut = sampleRate,
                sampleFormatInp = sampleFormatInp,
                sampleFormatOut = sampleFormatOut,
                samplesPerFrame = samplesPerFrame ?: GGWaveWrapper.getDefaultParameters().samplesPerFrame,
            ),
            protocolId = protocolId,
            volume = volume
        )
    }

    suspend fun encode(
        message: String,
        result: suspend (ByteArray, Long) -> Unit,
    ) {
        val instance = GGWaveWrapper.init(params)
        var durationMs = -1L
        var waveform = ByteArray(0)
        try {
            val payload = message.toByteArray()
            val dummyWaveform = ByteArray(1)
            val waveformSize = GGWaveWrapper.encode(
                instance = instance,
                payload = payload,
                payloadSize = payload.size,
                protocolId = protocolId,
                volume = volume,
                waveform = dummyWaveform,
                query = GGWaveQuery.WAVEFORM_SIZE
            )

            if (waveformSize <= 0) {
                throw GGWaveCodecException("transmitGGWave error on encode [$waveformSize]")
            }

            waveform = ByteArray(waveformSize)
            GGWaveWrapper.encode(
                instance = instance,
                payload = payload,
                payloadSize = payload.size,
                protocolId = protocolId,
                volume = volume,
                waveform = waveform,
                query = GGWaveQuery.NONE
            )

            durationMs = (waveform.size * 1000L) / (2 * params.sampleRate.toInt())
        } catch (ex: Exception) {
            GGWaveCodecException("transmitGGWave error", ex)
        } finally {
            GGWaveWrapper.free(instance)
        }
        result(waveform, durationMs)
    }

    suspend fun encodeAndPlay(
        message: String,
        playFinished: () -> Unit = {},
        periodicNotification: (track: AudioTrack?) -> Unit = { _ -> }
    ) {
        var audioTrack: AudioTrack? = null
        val mutex = Mutex()
        try {
            mutex.lock()
            encode(message) { waveform, durationMs ->
                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(params.sampleRate.toInt())
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(waveform.size)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                audioTrack.setNotificationMarkerPosition(waveform.size / 2)

                audioTrack.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                    override fun onMarkerReached(track: AudioTrack?) {
                        Log.d("GGWave", "onMarkerReached")
                        if (mutex.isLocked) mutex.unlock()
                        playFinished()
                    }

                    override fun onPeriodicNotification(track: AudioTrack?) {
                        periodicNotification(track)
                    }
                })
                audioTrack.play()
                audioTrack.write(waveform, 0, waveform.size)

                mutex.withLock {
                    Log.d("GGWave", "onMarkerReached called, finish")
                }
            }
        } catch (ex: Exception) {
            GGWaveCodecException("transmitGGWave error", ex)
        } finally {
            if (mutex.isLocked) mutex.unlock()
            audioTrack?.release()
        }
    }

    suspend fun decode(
        waveform: ByteArray,
        waveFormSize: Int,
        result: suspend (String) -> Unit,
    ) {
        val instance = GGWaveWrapper.init(params)
        var decodedString = ""
        try {
            val decoded = ByteArray(256)
            val decodedLen = GGWaveWrapper.decode(instance, waveform, waveFormSize, decoded)
            if (decodedLen > 0) {
                decodedString = String(decoded, 0, decodedLen)
            }
        } catch (ex: Exception) {
            GGWaveCodecException("decode error", ex)
        } finally {
            GGWaveWrapper.free(instance)
        }
        result(decodedString)
    }

    fun stopListeningFlow() {
        continueListeningFlow = false
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startListeningFlow() = flow {

        continueListeningFlow = true
        var audioRecord: AudioRecord? = null
        val instance = GGWaveWrapper.init(params)
        try {
            val bufferSize = AudioRecord.getMinBufferSize(
                params.sampleRate.toInt(),
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                params.sampleRate.toInt(),
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            val waveform = ByteArray(params.samplesPerFrame * 2) // 16-bit PCM

            audioRecord.startRecording()

            while (continueListeningFlow) {
                val decoded = ByteArray(256)
                val read = audioRecord.read(waveform, 0, waveform.size)
                if (read > 0) {
                    // PCM 버퍼 콜백 — GGWave 디코딩 전에 호출 (실제 마이크 레벨 측정용)
                    onPcmBuffer?.invoke(waveform, read)
                    val decodedLen = GGWaveWrapper.decode(instance, waveform, read, decoded)
                    if (decodedLen > 0) {
                        val decodedString = String(decoded, 0, decodedLen)
                        Log.d("GGWave", "Decoded: $decodedString")
                        emit(decodedString)
                    }
                }
            }

            audioRecord.stop()

        } catch (ex: Exception) {
            GGWaveCodecException("transmitGGWave error", ex)
        } finally {
            continueListeningFlow = true
            audioRecord?.release()
            GGWaveWrapper.free(instance)
        }
    }.flowOn(Dispatchers.IO)

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    suspend fun listenAndDecode(listeningTime: Long = 5000): String {

        var audioRecord: AudioRecord? = null
        var result = ""

        try {
            val bufferSize = AudioRecord.getMinBufferSize(
                params.sampleRate.toInt(),
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                params.sampleRate.toInt(),
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            val totalSamples = ((params.sampleRate * listeningTime / 1000).toInt())
            val waveform = ByteArray(totalSamples * 2) // 16-bit PCM

            audioRecord.startRecording()

            var offset = 0
            while (offset < waveform.size) {
                val toRead = minOf(bufferSize, waveform.size - offset)
                val read = audioRecord.read(waveform, offset, toRead)
                if (read > 0) {
                    offset += read
                } else {
                    break
                }
            }

            audioRecord.stop()

            decode(waveform = waveform, waveFormSize = offset) {
                result = it
            }
        } catch (ex: Exception) {
            GGWaveCodecException("listenAndDecode error", ex)
        } finally {
            audioRecord?.release()
        }

        return result.ifEmpty { "No GGWave message detected" }
    }
}
