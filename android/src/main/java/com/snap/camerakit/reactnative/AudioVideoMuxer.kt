package com.snap.camerakit.reactnative

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer

object AudioVideoMuxer {

    private const val BIT_RATE = 128_000
    private const val SAMPLES_PER_FRAME = 1024
    private const val BYTES_PER_SAMPLE = 2

    fun mux(videoFile: File, pcmFile: File, sampleRate: Int, outputFile: File): Boolean {
        return try {
            val extractor = MediaExtractor()
            extractor.setDataSource(videoFile.absolutePath)

            var videoTrackIdx = -1
            var videoFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                if (fmt.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    videoTrackIdx = i
                    videoFormat = fmt
                    break
                }
            }
            if (videoTrackIdx < 0 || videoFormat == null) { extractor.release(); return false }
            extractor.selectTrack(videoTrackIdx)

            // Set up AAC encoder
            val encFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1)
            encFormat.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            encFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            encFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, SAMPLES_PER_FRAME * BYTES_PER_SAMPLE * 4)
            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxVideoTrack = muxer.addTrack(videoFormat)
            var muxAudioTrack = -1
            var muxerStarted = false

            val pcmInput = FileInputStream(pcmFile)
            val pcmChunk = ByteArray(SAMPLES_PER_FRAME * BYTES_PER_SAMPLE)
            val bufInfo = MediaCodec.BufferInfo()
            val videoBuf = ByteBuffer.allocate(2 * 1024 * 1024)
            val videoInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var pcmTimeUs = 0L

            fun drainEncoder() {
                while (true) {
                    val idx = encoder.dequeueOutputBuffer(bufInfo, 10_000)
                    when {
                        idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            muxAudioTrack = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxerStarted = true
                            // Write all video samples now that muxer has started
                            while (true) {
                                videoInfo.offset = 0
                                videoInfo.size = extractor.readSampleData(videoBuf, 0)
                                if (videoInfo.size < 0) break
                                videoInfo.presentationTimeUs = extractor.sampleTime
                                videoInfo.flags = extractor.sampleFlags
                                muxer.writeSampleData(muxVideoTrack, videoBuf, videoInfo)
                                extractor.advance()
                            }
                        }
                        idx >= 0 -> {
                            val outBuf = encoder.getOutputBuffer(idx)!!
                            if (muxerStarted && muxAudioTrack >= 0 && bufInfo.size > 0) {
                                muxer.writeSampleData(muxAudioTrack, outBuf, bufInfo)
                            }
                            encoder.releaseOutputBuffer(idx, false)
                            if (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                        }
                        else -> return
                    }
                }
            }

            while (!inputDone) {
                val inIdx = encoder.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val inBuf = encoder.getInputBuffer(inIdx)!!
                    val read = pcmInput.read(pcmChunk)
                    if (read <= 0) {
                        encoder.queueInputBuffer(inIdx, 0, 0, pcmTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        inBuf.clear()
                        inBuf.put(pcmChunk, 0, read)
                        encoder.queueInputBuffer(inIdx, 0, read, pcmTimeUs, 0)
                        pcmTimeUs += read.toLong() * 1_000_000L / (sampleRate * BYTES_PER_SAMPLE)
                    }
                }
                drainEncoder()
            }
            drainEncoder()

            encoder.stop()
            encoder.release()
            pcmInput.close()
            muxer.stop()
            muxer.release()
            extractor.release()
            true
        } catch (_: Exception) {
            false
        }
    }
}
