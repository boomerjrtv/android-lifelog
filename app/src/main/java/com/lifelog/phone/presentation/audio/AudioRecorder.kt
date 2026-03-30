package com.lifelog.phone.presentation.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class AudioRecorder {
    private var audioRecord: AudioRecord? = null

    suspend fun recordAudio(durationMs: Long): ByteArray = suspendCancellableCoroutine { continuation ->
        try {
            val sampleRate = 16000
            val bufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ) * 2

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e("AudioRecorder", "Failed to initialize AudioRecord")
                continuation.resume(byteArrayOf())
                return@suspendCancellableCoroutine
            }

            val audioBuffer = mutableListOf<Byte>()
            audioRecord?.startRecording()

            val thread = Thread {
                val buffer = ByteArray(bufferSize)
                val startTime = System.currentTimeMillis()

                while (System.currentTimeMillis() - startTime < durationMs && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (bytesRead > 0) {
                        audioBuffer.addAll(buffer.take(bytesRead))
                    }
                }

                audioRecord?.stop()
                audioRecord?.release()
                audioRecord = null
                val pcm = audioBuffer.toByteArray()
                if (pcm.isEmpty()) {
                    continuation.resume(byteArrayOf())
                } else {
                    continuation.resume(pcm16ToWav(pcm, sampleRate))
                }
            }

            thread.start()

            continuation.invokeOnCancellation {
                audioRecord?.stop()
                audioRecord?.release()
                audioRecord = null
            }
        } catch (e: Exception) {
            Log.e("AudioRecorder", "Recording error: ${e.message}")
            continuation.resume(byteArrayOf())
        }
    }

    private fun pcm16ToWav(pcmData: ByteArray, sampleRate: Int): ByteArray {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataLen = pcmData.size
        val totalLen = 36 + dataLen

        val out = ByteArrayOutputStream(dataLen + 44)
        fun writeStr(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun writeInt(v: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        fun writeShort(v: Int) = out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array())

        writeStr("RIFF")
        writeInt(totalLen)
        writeStr("WAVE")
        writeStr("fmt ")
        writeInt(16)
        writeShort(1)
        writeShort(channels)
        writeInt(sampleRate)
        writeInt(byteRate)
        writeShort(blockAlign)
        writeShort(bitsPerSample)
        writeStr("data")
        writeInt(dataLen)
        out.write(pcmData)
        return out.toByteArray()
    }
}
