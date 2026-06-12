package com.snap.camerakit.reactnative

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaProjection
import android.media.MediaRecorder
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

@RequiresApi(Build.VERSION_CODES.Q)
class MixedAudioRecorder(
    private val mediaProjection: MediaProjection,
    val pcmFile: File,
    val sampleRate: Int = 44100
) {
    companion object {
        const val CHANNEL_COUNT = 1
        const val BYTES_PER_SAMPLE = 2
    }

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start() {
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBuffer * 4, 8192)

        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val appRecord = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(captureConfig)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .build()

        val micRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        running.set(true)
        appRecord.startRecording()
        micRecord.startRecording()

        thread = Thread {
            val appBuf = ShortArray(bufferSize / BYTES_PER_SAMPLE)
            val micBuf = ShortArray(bufferSize / BYTES_PER_SAMPLE)
            val outBytes = ByteBuffer.allocate(bufferSize).order(ByteOrder.LITTLE_ENDIAN)
            FileOutputStream(pcmFile).use { fos ->
                while (running.get()) {
                    val appN = appRecord.read(appBuf, 0, appBuf.size).coerceAtLeast(0)
                    val micN = micRecord.read(micBuf, 0, micBuf.size).coerceAtLeast(0)
                    val n = maxOf(appN, micN)
                    if (n <= 0) continue

                    outBytes.clear()
                    for (i in 0 until n) {
                        val a = if (i < appN) appBuf[i].toInt() else 0
                        val m = if (i < micN) micBuf[i].toInt() else 0
                        outBytes.putShort(
                            (a + m).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                        )
                    }
                    fos.write(outBytes.array(), 0, n * BYTES_PER_SAMPLE)
                }
                fos.flush()
            }
            appRecord.stop()
            appRecord.release()
            micRecord.stop()
            micRecord.release()
        }.also { it.start() }
    }

    fun stop() {
        running.set(false)
        thread?.join(3000)
        thread = null
    }
}
