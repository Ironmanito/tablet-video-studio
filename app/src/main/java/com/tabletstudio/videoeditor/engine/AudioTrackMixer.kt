package com.tabletstudio.videoeditor.engine

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.tabletstudio.videoeditor.model.TimelineAudioClip
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object AudioTrackMixer {

    const val SAMPLE_RATE = 44100
    const val CHANNELS = 2

    /**
     * Decodes and mixes all active audio clips into a master 44.1 kHz Stereo 16-bit PCM array.
     * Supports trimming (trimFromMs..trimToMs), timeline placement (displayFromMs..displayToMs),
     * playbackRate speed adjustment, and per-clip volume mixing.
     */
    fun mixAudioClipsToStereoPcm(
        clips: List<TimelineAudioClip>,
        totalDurationMs: Long,
        isCancelled: () -> Boolean
    ): ShortArray {
        val clampedDurationMs = totalDurationMs.coerceIn(500L, 180_000L)
        val totalFrames = ((clampedDurationMs * SAMPLE_RATE) / 1000L).toInt()
        val masterPcm = ShortArray(totalFrames * CHANNELS)

        if (clips.isEmpty()) {
            return masterPcm
        }

        for (clip in clips) {
            if (isCancelled()) break
            if (!clip.file.exists() || clip.volume <= 0.001f) continue

            try {
                decodeAndMixSingleClip(clip, masterPcm, totalFrames, isCancelled)
            } catch (_: Exception) {
                // Skip unreadable audio stream without failing entire video render
            }
        }

        return masterPcm
    }

    private fun decodeAndMixSingleClip(
        clip: TimelineAudioClip,
        masterPcm: ShortArray,
        masterFrames: Int,
        isCancelled: () -> Boolean
    ) {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(clip.file.absolutePath)
            var audioTrackIndex = -1
            var trackFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    trackFormat = fmt
                    break
                }
            }

            if (audioTrackIndex < 0 || trackFormat == null) return
            extractor.selectTrack(audioTrackIndex)

            val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: return
            var srcSampleRate = if (trackFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else SAMPLE_RATE
            var srcChannels = if (trackFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else CHANNELS

            val trimStartUs = max(0L, clip.trimFromMs * 1000L)
            val clipTimelineDurationMs = max(50L, clip.displayToMs - clip.displayFromMs)
            val rate = clip.playbackRate.coerceIn(0.25f, 4.0f)
            val sourceDurationNeededUs = (clipTimelineDurationMs * 1000L * rate).toLong()
            val trimEndUs = if (clip.trimToMs > clip.trimFromMs) {
                clip.trimToMs * 1000L
            } else {
                trimStartUs + sourceDurationNeededUs
            }

            if (trimStartUs > 0L) {
                extractor.seekTo(trimStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }

            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(trackFormat, null, null, 0)
            decoder.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            val dstStartFrame = ((clip.displayFromMs * SAMPLE_RATE) / 1000L).toInt().coerceIn(0, masterFrames)
            val dstMaxFrame = ((clip.displayToMs * SAMPLE_RATE) / 1000L).toInt().coerceIn(0, masterFrames)
            if (dstMaxFrame <= dstStartFrame) return

            var dstFrameCursor = dstStartFrame.toDouble()
            val vol = clip.volume.coerceIn(0f, 2f)
            val fadeFrames = (SAMPLE_RATE * 0.8f).toInt().coerceAtLeast(1)

            while (!outputDone && !isCancelled()) {
                if (!inputDone) {
                    val inIdx = decoder.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val inBuf = decoder.getInputBuffer(inIdx)
                        if (inBuf != null) {
                            val sampleSize = extractor.readSampleData(inBuf, 0)
                            val sampleTimeUs = extractor.sampleTime
                            if (sampleSize < 0 || (sampleTimeUs > trimEndUs && sampleTimeUs > 0)) {
                                decoder.queueInputBuffer(
                                    inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(inIdx, 0, sampleSize, sampleTimeUs, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIdx = decoder.dequeueOutputBuffer(bufferInfo, 10_000L)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFmt = decoder.outputFormat
                        if (newFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            srcSampleRate = newFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (newFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            srcChannels = max(1, newFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                        }
                    }
                    outIdx >= 0 -> {
                        val outBuf = decoder.getOutputBuffer(outIdx)
                        if (outBuf != null && bufferInfo.size > 0 && bufferInfo.presentationTimeUs >= trimStartUs - 50_000L) {
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            val shortBuf = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            val numShorts = shortBuf.remaining()
                            val srcFrames = numShorts / max(1, srcChannels)

                            // Step in destination frames per source frame
                            val dstFramesPerSrcFrame = (SAMPLE_RATE.toDouble() / max(8000, srcSampleRate).toDouble()) / rate

                            for (f in 0 until srcFrames) {
                                val baseIdx = f * srcChannels
                                val leftRaw = shortBuf.get(baseIdx).toInt()
                                val rightRaw = if (srcChannels > 1 && baseIdx + 1 < numShorts) {
                                    shortBuf.get(baseIdx + 1).toInt()
                                } else {
                                    leftRaw
                                }

                                val nextCursor = dstFrameCursor + dstFramesPerSrcFrame
                                val startDst = dstFrameCursor.toInt()
                                val endDst = max(startDst + 1, nextCursor.toInt())

                                for (dFrame in startDst until min(endDst, dstMaxFrame)) {
                                    val mIdx = dFrame * 2
                                    if (mIdx + 1 < masterPcm.size) {
                                        var env = 1f
                                        if (clip.fadeIn && dFrame - dstStartFrame < fadeFrames) {
                                            env *= ((dFrame - dstStartFrame).toFloat() / fadeFrames).coerceIn(0f, 1f)
                                        }
                                        if (clip.fadeOut && dstMaxFrame - dFrame < fadeFrames) {
                                            env *= ((dstMaxFrame - dFrame).toFloat() / fadeFrames).coerceIn(0f, 1f)
                                        }
                                        val effVol = vol * env
                                        val mixedL = (masterPcm[mIdx].toInt() + (leftRaw * effVol).roundToInt())
                                            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                                        val mixedR = (masterPcm[mIdx + 1].toInt() + (rightRaw * effVol).roundToInt())
                                            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                                        masterPcm[mIdx] = mixedL.toShort()
                                        masterPcm[mIdx + 1] = mixedR.toShort()
                                    }
                                }
                                dstFrameCursor = nextCursor
                                if (dstFrameCursor >= dstMaxFrame) break
                            }
                        }
                        decoder.releaseOutputBuffer(outIdx, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0 ||
                            dstFrameCursor >= dstMaxFrame
                        ) {
                            outputDone = true
                        }
                    }
                }
            }
        } finally {
            try {
                decoder?.stop()
                decoder?.release()
            } catch (_: Exception) {
            }
            try {
                extractor.release()
            } catch (_: Exception) {
            }
        }
    }
}
