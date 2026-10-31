package com.tabletstudio.videoeditor.engine

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Base64
import com.tabletstudio.videoeditor.model.MediaProbeUtils
import com.tabletstudio.videoeditor.model.RenderJob
import com.tabletstudio.videoeditor.model.TargetDimensions
import com.tabletstudio.videoeditor.model.TimelineAudioClip
import com.tabletstudio.videoeditor.model.TimelineTextClip
import com.tabletstudio.videoeditor.model.TimelineVisualClip
import com.tabletstudio.videoeditor.model.TransitionSpec
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class AndroidMediaCodecRenderer(
    private val context: Context,
    private val uploadsDir: File,
    private val cacheDir: File
) {

    private class CachedVideoSource(val file: File) {
        val retriever = MediaMetadataRetriever()
        var lastTimestampUs: Long = -1_000_000L
        var lastBitmap: Bitmap? = null
        var isValid = false

        init {
            try {
                retriever.setDataSource(file.absolutePath)
                isValid = true
            } catch (_: Exception) {
                isValid = false
            }
        }

        fun getFrameAt(sourceTimeUs: Long, targetW: Int, targetH: Int, fastMode: Boolean): Bitmap? {
            if (!isValid) return null
            // Reuse cached frame if within 28ms (~35fps threshold)
            if (lastBitmap != null && abs(sourceTimeUs - lastTimestampUs) < 28_000L) {
                return lastBitmap
            }
            return try {
                val option = if (fastMode) {
                    MediaMetadataRetriever.OPTION_CLOSEST
                } else {
                    MediaMetadataRetriever.OPTION_CLOSEST
                }
                val bmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 && targetW > 0 && targetH > 0) {
                    retriever.getScaledFrameAtTime(
                        max(0L, sourceTimeUs),
                        option,
                        targetW.coerceIn(64, 1920),
                        targetH.coerceIn(64, 1920)
                    )
                } else {
                    retriever.getFrameAtTime(max(0L, sourceTimeUs), option)
                }
                if (bmp != null) {
                    lastBitmap = bmp
                    lastTimestampUs = sourceTimeUs
                }
                bmp ?: lastBitmap
            } catch (_: Exception) {
                lastBitmap
            }
        }

        fun release() {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
            lastBitmap = null
        }
    }

    fun renderTimelineJob(job: RenderJob, design: JSONObject, options: JSONObject) {
        val startWallMs = System.currentTimeMillis()
        val videoRetCache = mutableMapOf<String, CachedVideoSource>()
        val imageBmpCache = mutableMapOf<String, Bitmap>()

        var videoEncoder: MediaCodec? = null
        var audioEncoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false

        try {
            val fps = (options.optInt("fps", design.optInt("fps", 30))).coerceIn(15, 60)
            val presetProfile = options.optString("presetProfile", "quality")
            val fastMode = presetProfile == "fast"
            val dims = MediaProbeUtils.resolveTargetDimensions(design.optJSONObject("size"), options)

            job.fps = fps
            job.width = dims.outW
            job.height = dims.outH
            job.progress = 4
            job.speed = "analizando pistas..."

            val parsed = parseTimelineDesign(design, options, dims)
            val totalDurationMs = parsed.totalDurationMs
            job.durationMs = totalDurationMs

            if (job.cancelled) {
                job.status = "FAILED"
                job.error = "Renderizado cancelado por el usuario."
                return
            }

            // 1. Pre-mix audio tracks in memory (44.1 kHz Stereo PCM)
            job.progress = 8
            job.speed = "mezclando audio..."
            val masterPcm = AudioTrackMixer.mixAudioClipsToStereoPcm(
                clips = parsed.audioClips,
                totalDurationMs = totalDurationMs,
                isCancelled = { job.cancelled }
            )

            if (job.cancelled) {
                job.status = "FAILED"
                job.error = "Renderizado cancelado por el usuario."
                return
            }

            // 2. Configure Hardware H.264 Video Encoder + Input Surface
            val bitrate = when {
                fastMode -> 3_500_000
                dims.outW * dims.outH >= 1920 * 1080 -> 7_000_000
                else -> 5_000_000
            }

            val videoFormat = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                dims.outW,
                dims.outH
            ).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            videoEncoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = videoEncoder.createInputSurface()
            videoEncoder.start()

            // 3. Configure Hardware AAC Audio Encoder
            val audioFormat = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                AudioTrackMixer.SAMPLE_RATE,
                AudioTrackMixer.CHANNELS
            ).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(
                    MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC
                )
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }

            audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            audioEncoder.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            audioEncoder.start()

            if (job.outputFile.exists()) {
                job.outputFile.delete()
            }
            val activeMuxer = MediaMuxer(
                job.outputFile.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )
            muxer = activeMuxer

            val totalFrames = max(1, ((totalDurationMs * fps) / 1000L).toInt())
            val videoBufferInfo = MediaCodec.BufferInfo()
            var muxerVideoTrack = -1
            var muxerAudioTrack = -1
            var encodedVideoFrames = 0

            // First, obtain audio output format from AAC encoder so we can start MediaMuxer cleanly
            val audioBufferInfo = MediaCodec.BufferInfo()
            val encodedAudioPackets = mutableListOf<Pair<ByteArray, MediaCodec.BufferInfo>>()
            encodeAllPcmToAacPackets(
                masterPcm = masterPcm,
                audioEncoder = audioEncoder,
                bufferInfo = audioBufferInfo,
                outPackets = encodedAudioPackets,
                isCancelled = { job.cancelled }
            )
            val finalAudioFormat = audioEncoder.outputFormat
            muxerAudioTrack = activeMuxer.addTrack(finalAudioFormat)

            // 4. Render every video frame on Canvas -> Surface -> H.264 Hardware Encoder
            for (frameIdx in 0 until totalFrames) {
                if (job.cancelled) {
                    job.status = "FAILED"
                    job.error = "Renderizado cancelado por el usuario."
                    return
                }

                val currentTimeMs = (frameIdx * 1000L) / fps
                val canvas = inputSurface.lockCanvas(null)
                try {
                    drawCompositeFrame(
                        canvas = canvas,
                        timeMs = currentTimeMs,
                        dims = dims,
                        visualClips = parsed.visualClips,
                        textClips = parsed.textClips,
                        videoRetCache = videoRetCache,
                        imageBmpCache = imageBmpCache,
                        fastMode = fastMode
                    )
                } finally {
                    inputSurface.unlockCanvasAndPost(canvas)
                }

                // Drain available encoded video buffers
                drainVideoEncoder(
                    encoder = videoEncoder,
                    bufferInfo = videoBufferInfo,
                    endOfStream = false,
                    fps = fps,
                    onFormatChanged = { fmt ->
                        if (!muxerStarted) {
                            muxerVideoTrack = activeMuxer.addTrack(fmt)
                            activeMuxer.start()
                            muxerStarted = true
                        }
                    },
                    onSampleReady = { buf, info ->
                        if (muxerStarted && muxerVideoTrack >= 0) {
                            info.presentationTimeUs = (encodedVideoFrames.toLong() * 1_000_000L) / fps
                            encodedVideoFrames++
                            activeMuxer.writeSampleData(muxerVideoTrack, buf, info)
                        }
                    }
                )

                val pct = 12 + ((frameIdx + 1) * 82) / totalFrames
                if (pct > job.progress) {
                    job.progress = pct.coerceIn(12, 94)
                }
                val elapsedSec = max(0.2f, (System.currentTimeMillis() - startWallMs) / 1000f)
                val currentVideoSec = (frameIdx + 1).toFloat() / fps.toFloat()
                val speedRatio = currentVideoSec / elapsedSec
                job.speed = String.format(Locale.US, "%.2fx HW", speedRatio)
            }

            // Signal End Of Stream to Video Encoder and drain remaining frames
            videoEncoder.signalEndOfInputStream()
            drainVideoEncoder(
                encoder = videoEncoder,
                bufferInfo = videoBufferInfo,
                endOfStream = true,
                fps = fps,
                onFormatChanged = { fmt ->
                    if (!muxerStarted) {
                        muxerVideoTrack = activeMuxer.addTrack(fmt)
                        activeMuxer.start()
                        muxerStarted = true
                    }
                },
                onSampleReady = { buf, info ->
                    if (muxerStarted && muxerVideoTrack >= 0) {
                        info.presentationTimeUs = (encodedVideoFrames.toLong() * 1_000_000L) / fps
                        encodedVideoFrames++
                        activeMuxer.writeSampleData(muxerVideoTrack, buf, info)
                    }
                }
            )

            // 5. Write all pre-encoded AAC audio packets into the MP4 Muxer
            if (muxerStarted && muxerAudioTrack >= 0) {
                job.progress = 96
                job.speed = "empaquetando MP4..."
                val tempByteBuf = java.nio.ByteBuffer.allocateDirect(65536)
                for ((bytes, info) in encodedAudioPackets) {
                    if (bytes.isEmpty()) continue
                    tempByteBuf.clear()
                    tempByteBuf.put(bytes)
                    tempByteBuf.flip()
                    val copyInfo = MediaCodec.BufferInfo().apply {
                        set(0, bytes.size, info.presentationTimeUs, info.flags)
                    }
                    activeMuxer.writeSampleData(muxerAudioTrack, tempByteBuf, copyInfo)
                }
            }

            activeMuxer.stop()
            activeMuxer.release()
            muxer = null

            // 6. Export MP4 to Tablet Gallery / Movies & Downloads
            val galleryPath = exportMp4ToAndroidGallery(job.outputFile, job.id)
            job.savedToGalleryPath = galleryPath
            job.progress = 100
            job.status = "COMPLETED"
        } catch (e: Exception) {
            job.status = "FAILED"
            job.error = e.message ?: "Error en el motor MediaCodec: ${e.javaClass.simpleName}"
        } finally {
            for (v in videoRetCache.values) {
                v.release()
            }
            videoRetCache.clear()
            imageBmpCache.clear()
            try {
                videoEncoder?.stop()
                videoEncoder?.release()
            } catch (_: Exception) {
            }
            try {
                audioEncoder?.stop()
                audioEncoder?.release()
            } catch (_: Exception) {
            }
            try {
                if (muxer != null) {
                    if (muxerStarted) muxer.stop()
                    muxer.release()
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun encodeAllPcmToAacPackets(
        masterPcm: ShortArray,
        audioEncoder: MediaCodec,
        bufferInfo: MediaCodec.BufferInfo,
        outPackets: MutableList<Pair<ByteArray, MediaCodec.BufferInfo>>,
        isCancelled: () -> Boolean
    ) {
        var shortCursor = 0
        var inputDone = false
        var outputDone = false
        val shortsPerSec = AudioTrackMixer.SAMPLE_RATE * AudioTrackMixer.CHANNELS

        while (!outputDone && !isCancelled()) {
            if (!inputDone) {
                val inIdx = audioEncoder.dequeueInputBuffer(10_000L)
                if (inIdx >= 0) {
                    val inBuf = audioEncoder.getInputBuffer(inIdx)
                    if (inBuf != null) {
                        inBuf.clear()
                        val maxShorts = min(masterPcm.size - shortCursor, inBuf.remaining() / 2)
                        if (maxShorts <= 0) {
                            val ptsUs = (shortCursor.toLong() * 1_000_000L) / shortsPerSec
                            audioEncoder.queueInputBuffer(
                                inIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            val ptsUs = (shortCursor.toLong() * 1_000_000L) / shortsPerSec
                            val byteBuf = inBuf.order(ByteOrder.LITTLE_ENDIAN)
                            for (i in 0 until maxShorts) {
                                byteBuf.putShort(masterPcm[shortCursor + i])
                            }
                            shortCursor += maxShorts
                            audioEncoder.queueInputBuffer(inIdx, 0, maxShorts * 2, ptsUs, 0)
                        }
                    }
                }
            }

            val outIdx = audioEncoder.dequeueOutputBuffer(bufferInfo, 10_000L)
            if (outIdx >= 0) {
                val outBuf = audioEncoder.getOutputBuffer(outIdx)
                if (outBuf != null && bufferInfo.size > 0 &&
                    (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                ) {
                    outBuf.position(bufferInfo.offset)
                    outBuf.limit(bufferInfo.offset + bufferInfo.size)
                    val chunk = ByteArray(bufferInfo.size)
                    outBuf.get(chunk)
                    val infoCopy = MediaCodec.BufferInfo().apply {
                        set(0, bufferInfo.size, max(0L, bufferInfo.presentationTimeUs), bufferInfo.flags)
                    }
                    outPackets.add(chunk to infoCopy)
                }
                audioEncoder.releaseOutputBuffer(outIdx, false)
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    outputDone = true
                }
            } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone) {
                // Keep polling briefly until EOS
            }
        }
    }

    private fun drainVideoEncoder(
        encoder: MediaCodec,
        bufferInfo: MediaCodec.BufferInfo,
        endOfStream: Boolean,
        fps: Int,
        onFormatChanged: (MediaFormat) -> Unit,
        onSampleReady: (java.nio.ByteBuffer, MediaCodec.BufferInfo) -> Unit
    ) {
        val timeoutUs = if (endOfStream) 10_000L else 0L
        while (true) {
            val outIdx = encoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
            when {
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) break
                }
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    onFormatChanged(encoder.outputFormat)
                }
                outIdx >= 0 -> {
                    val encodedData = encoder.getOutputBuffer(outIdx)
                    if (encodedData != null) {
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            bufferInfo.size = 0
                        }
                        if (bufferInfo.size > 0) {
                            encodedData.position(bufferInfo.offset)
                            encodedData.limit(bufferInfo.offset + bufferInfo.size)
                            onSampleReady(encodedData, bufferInfo)
                        }
                    }
                    encoder.releaseOutputBuffer(outIdx, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break
                    }
                }
            }
        }
    }

    private fun drawCompositeFrame(
        canvas: Canvas,
        timeMs: Long,
        dims: TargetDimensions,
        visualClips: List<TimelineVisualClip>,
        textClips: List<TimelineTextClip>,
        videoRetCache: MutableMap<String, CachedVideoSource>,
        imageBmpCache: MutableMap<String, Bitmap>,
        fastMode: Boolean
    ) {
        canvas.drawColor(Color.BLACK)
        val timeSec = timeMs / 1000f
        val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
        }

        // 1. Draw active video and image layers
        for (clip in visualClips) {
            if (timeMs < clip.displayFromMs || timeMs > clip.displayToMs) continue

            val bmp: Bitmap? = if (clip.type == "image") {
                imageBmpCache.getOrPut(clip.file.absolutePath) {
                    decodeSampledBitmap(clip.file, clip.box.finalW, clip.box.finalH)
                        ?: Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
                }
            } else {
                val srcOffsetMs = ((timeMs - clip.displayFromMs) * clip.playbackRate).toLong() + clip.trimFromMs
                val videoSource = videoRetCache.getOrPut(clip.file.absolutePath) {
                    CachedVideoSource(clip.file)
                }
                videoSource.getFrameAt(
                    sourceTimeUs = srcOffsetMs * 1000L,
                    targetW = clip.box.finalW,
                    targetH = clip.box.finalH,
                    fastMode = fastMode
                )
            }

            if (bmp == null || bmp.width <= 1 || bmp.height <= 1) continue

            // Compute fade / slide transitions
            val clipDurSec = max(0.05f, (clip.displayToMs - clip.displayFromMs) / 1000f)
            val relSec = max(0f, (timeMs - clip.displayFromMs) / 1000f)
            var alphaFactor = clip.opacity

            var offsetX = clip.box.finalX
            var offsetY = clip.box.finalY

            clip.inTransition?.let { tr ->
                val d = min(tr.durationSec, clipDurSec * 0.5f)
                if (relSec < d && d > 0.01f) {
                    val progress = (relSec / d).coerceIn(0f, 1f)
                    alphaFactor *= progress
                    if (tr.kind == "slide" || tr.kind == "wipe") {
                        val inv = 1f - progress
                        when (tr.direction) {
                            "from-right" -> offsetX += (dims.outW - offsetX) * inv
                            "from-left" -> offsetX -= (offsetX + clip.box.finalW) * inv
                            "from-bottom" -> offsetY += (dims.outH - offsetY) * inv
                            "from-top" -> offsetY -= (offsetY + clip.box.finalH) * inv
                        }
                    }
                }
            }

            clip.outTransition?.let { tr ->
                val d = min(tr.durationSec, clipDurSec * 0.5f)
                val outStart = clipDurSec - d
                if (relSec > outStart && d > 0.01f) {
                    val progress = ((clipDurSec - relSec) / d).coerceIn(0f, 1f)
                    alphaFactor *= progress
                }
            }

            bitmapPaint.alpha = (alphaFactor.coerceIn(0f, 1f) * 255f).roundToInt()

            // Optional brightness adjustment
            if (abs(clip.brightness - 100f) > 1f) {
                val shift = ((clip.brightness - 100f) / 100f).coerceIn(-0.8f, 0.8f) * 255f
                val cm = ColorMatrix(
                    floatArrayOf(
                        1f, 0f, 0f, 0f, shift,
                        0f, 1f, 0f, 0f, shift,
                        0f, 0f, 1f, 0f, shift,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
                bitmapPaint.colorFilter = ColorMatrixColorFilter(cm)
            } else {
                bitmapPaint.colorFilter = null
            }

            val dstRect = RectF(
                offsetX,
                offsetY,
                offsetX + clip.box.finalW,
                offsetY + clip.box.finalH
            )

            canvas.save()
            if (clip.borderRadiusPx > 0.5f) {
                val clipPath = Path().apply {
                    addRoundRect(dstRect, clip.borderRadiusPx, clip.borderRadiusPx, Path.Direction.CW)
                }
                canvas.clipPath(clipPath)
            }

            if (clip.flipX || clip.flipY) {
                canvas.scale(
                    if (clip.flipX) -1f else 1f,
                    if (clip.flipY) -1f else 1f,
                    dstRect.centerX(),
                    dstRect.centerY()
                )
            }

            val srcRect = Rect(0, 0, bmp.width, bmp.height)
            canvas.drawBitmap(bmp, srcRect, dstRect, bitmapPaint)
            canvas.restore()

            if (clip.borderWidthPx > 0.5f && Color.alpha(clip.borderColor) > 0) {
                borderPaint.strokeWidth = clip.borderWidthPx
                borderPaint.color = clip.borderColor
                borderPaint.alpha = (alphaFactor.coerceIn(0f, 1f) * Color.alpha(clip.borderColor)).roundToInt()
                if (clip.borderRadiusPx > 0.5f) {
                    canvas.drawRoundRect(
                        dstRect,
                        clip.borderRadiusPx,
                        clip.borderRadiusPx,
                        borderPaint
                    )
                } else {
                    canvas.drawRect(dstRect, borderPaint)
                }
            }
        }

        // 2. Draw active text and caption overlays
        for (txt in textClips) {
            if (timeMs < txt.displayFromMs || timeMs > txt.displayToMs) continue
            drawTextClip(canvas, txt)
        }
    }

    private fun drawTextClip(canvas: Canvas, txt: TimelineTextClip) {
        if (txt.text.isBlank()) return

        val tf = if (txt.isBold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tf
            textSize = txt.fontSizePx
            color = txt.textColor
            if (txt.borderWidthPx <= 0.5f) {
                setShadowLayer(4f, 2f, 2f, Color.argb(150, 0, 0, 0))
            }
        }

        val alignment = when (txt.textAlign.lowercase(Locale.ROOT)) {
            "left" -> Layout.Alignment.ALIGN_NORMAL
            "right" -> Layout.Alignment.ALIGN_OPPOSITE
            else -> Layout.Alignment.ALIGN_CENTER
        }

        val layoutWidth = max(64, txt.boxW.roundToInt())
        val staticLayout = StaticLayout.Builder
            .obtain(txt.text, 0, txt.text.length, textPaint, layoutWidth)
            .setAlignment(alignment)
            .setLineSpacing(0f, 1.12f)
            .setIncludePad(false)
            .build()

        val textHeight = staticLayout.height.toFloat()
        val drawX = txt.boxX
        val drawY = txt.boxY + if (txt.boxH > textHeight) (txt.boxH - textHeight) / 2f else 0f

        canvas.save()

        // Optional pill background box
        txt.backgroundColor?.let { bgCol ->
            if (Color.alpha(bgCol) > 0) {
                var maxLineW = 0f
                for (i in 0 until staticLayout.lineCount) {
                    maxLineW = max(maxLineW, staticLayout.getLineWidth(i))
                }
                val padX = max(10f, txt.fontSizePx * 0.25f)
                val padY = max(6f, txt.fontSizePx * 0.16f)
                val boxLeft = when (alignment) {
                    Layout.Alignment.ALIGN_NORMAL -> drawX - padX
                    Layout.Alignment.ALIGN_OPPOSITE -> drawX + layoutWidth - maxLineW - padX
                    else -> drawX + (layoutWidth - maxLineW) / 2f - padX
                }
                val bgRect = RectF(
                    boxLeft,
                    drawY - padY,
                    boxLeft + maxLineW + padX * 2f,
                    drawY + textHeight + padY
                )
                val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    color = bgCol
                }
                val radius = max(8f, txt.fontSizePx * 0.22f)
                canvas.drawRoundRect(bgRect, radius, radius, bgPaint)
            }
        }

        canvas.translate(drawX, drawY)

        // Optional stroke outline
        if (txt.borderWidthPx > 0.5f && Color.alpha(txt.borderColor) > 0) {
            val strokePaint = TextPaint(textPaint).apply {
                style = Paint.Style.STROKE
                strokeWidth = txt.borderWidthPx * 2f
                strokeJoin = Paint.Join.ROUND
                color = txt.borderColor
                clearShadowLayer()
            }
            val strokeLayout = StaticLayout.Builder
                .obtain(txt.text, 0, txt.text.length, strokePaint, layoutWidth)
                .setAlignment(alignment)
                .setLineSpacing(0f, 1.12f)
                .setIncludePad(false)
                .build()
            strokeLayout.draw(canvas)
        }

        staticLayout.draw(canvas)
        canvas.restore()
    }

    private data class ParsedTimeline(
        val visualClips: List<TimelineVisualClip>,
        val textClips: List<TimelineTextClip>,
        val audioClips: List<TimelineAudioClip>,
        val totalDurationMs: Long
    )

    private fun parseTimelineDesign(
        design: JSONObject,
        options: JSONObject,
        dims: TargetDimensions
    ): ParsedTimeline {
        val trackItemsMap = design.optJSONObject("trackItemsMap") ?: JSONObject()
        val trackItemDetailsMap = design.optJSONObject("trackItemDetailsMap") ?: JSONObject()
        val transitionsMap = design.optJSONObject("transitionsMap") ?: JSONObject()

        val orderedIds = mutableListOf<String>()
        val seenIds = mutableSetOf<String>()

        design.optJSONArray("trackItemIds")?.let { arr ->
            for (i in 0 until arr.length()) {
                val id = arr.optString(i)
                if (id.isNotBlank() && seenIds.add(id)) {
                    orderedIds.add(id)
                }
            }
        }

        design.optJSONArray("tracks")?.let { tracksArr ->
            for (i in tracksArr.length() - 1 downTo 0) {
                val track = tracksArr.optJSONObject(i) ?: continue
                val items = track.optJSONArray("items") ?: continue
                for (j in 0 until items.length()) {
                    val id = items.optString(j)
                    if (id.isNotBlank() && seenIds.add(id)) {
                        orderedIds.add(id)
                    }
                }
            }
        }

        val mapKeys = trackItemsMap.keys()
        while (mapKeys.hasNext()) {
            val id = mapKeys.next()
            if (id.isNotBlank() && seenIds.add(id)) {
                orderedIds.add(id)
            }
        }

        val visualClips = mutableListOf<TimelineVisualClip>()
        val textClips = mutableListOf<TimelineTextClip>()
        val audioClips = mutableListOf<TimelineAudioClip>()
        var maxToMs = 0L

        for ((orderIdx, id) in orderedIds.withIndex()) {
            val baseItem = trackItemsMap.optJSONObject(id)
            val detailItem = trackItemDetailsMap.optJSONObject(id)
            if (baseItem == null && detailItem == null) continue

            val type = (baseItem?.optString("type")?.takeIf { it.isNotBlank() }
                ?: detailItem?.optString("type") ?: "").lowercase(Locale.ROOT)

            val details = JSONObject()
            baseItem?.optJSONObject("details")?.let { copyJsonInto(it, details) }
            detailItem?.optJSONObject("details")?.let { copyJsonInto(it, details) }

            if (details.optString("visibility").equals("hidden", true)) continue

            val displayObj = baseItem?.optJSONObject("display")
                ?: detailItem?.optJSONObject("display")
            val displayFromMs = max(0L, displayObj?.optDouble("from", 0.0)?.roundToInt()?.toLong() ?: 0L)
            val displayToMs = max(
                displayFromMs + 50L,
                displayObj?.optDouble("to", (displayFromMs + 5000L).toDouble())?.roundToInt()?.toLong()
                    ?: (displayFromMs + 5000L)
            )
            if (displayToMs > maxToMs) maxToMs = displayToMs

            val trimObj = baseItem?.optJSONObject("trim") ?: detailItem?.optJSONObject("trim")
            val playbackRate = (baseItem?.optDouble("playbackRate", Double.NaN)
                ?.takeIf { !it.isNaN() && it > 0 }
                ?: detailItem?.optDouble("playbackRate", 1.0)
                ?: 1.0).toFloat().coerceIn(0.25f, 4.0f)

            val trimFromMs = max(0L, trimObj?.optDouble("from", 0.0)?.roundToInt()?.toLong() ?: 0L)
            val defaultTrimToMs = trimFromMs + ((displayToMs - displayFromMs) * playbackRate).toLong()
            val rawTrimToMs = trimObj?.optDouble("to", 0.0)?.roundToInt()?.toLong() ?: 0L
            val trimToMs = if (rawTrimToMs > trimFromMs) rawTrimToMs else defaultTrimToMs

            val opacityPct = MediaProbeUtils.parsePixelOrNumber(details.opt("opacity"), 100f) ?: 100f
            val opacity = (opacityPct / 100f).coerceIn(0f, 1f)

            val rawVol = MediaProbeUtils.parsePixelOrNumber(details.opt("volume"), 100f) ?: 100f
            val normVol = if (rawVol > 1.5f) (rawVol / 100f) else rawVol

            when (type) {
                "video", "image" -> {
                    val src = details.optString("src", "")
                    val localFile = resolveMediaSourceToFile(src) ?: continue
                    val probe = MediaProbeUtils.probeMediaFile(localFile, id, localFile.name)
                    val box = MediaProbeUtils.computeVisualBox(details, probe.width, probe.height, dims)
                    val (inTrans, outTrans) = extractTransitions(id, baseItem, detailItem, details, transitionsMap, options)

                    val borderRadius = (MediaProbeUtils.parsePixelOrNumber(details.opt("borderRadius"), 0f) ?: 0f) * dims.scaleX
                    val borderWidth = (MediaProbeUtils.parsePixelOrNumber(details.opt("borderWidth"), 0f) ?: 0f) * dims.scaleX
                    val borderColor = MediaProbeUtils.parseCssColor(
                        details.optString("borderColor", "#22d3ee"),
                        opacity,
                        Color.TRANSPARENT
                    )

                    val isMain = baseItem?.optBoolean("isMain", false) == true
                    val vClip = TimelineVisualClip(
                        id = id,
                        type = type,
                        file = localFile,
                        displayFromMs = displayFromMs,
                        displayToMs = displayToMs,
                        trimFromMs = trimFromMs,
                        trimToMs = trimToMs,
                        playbackRate = playbackRate,
                        volume = normVol.coerceIn(0f, 1.5f),
                        opacity = opacity,
                        box = box,
                        borderRadiusPx = borderRadius,
                        borderWidthPx = borderWidth,
                        borderColor = borderColor,
                        flipX = details.optBoolean("flipX", false),
                        flipY = details.optBoolean("flipY", false),
                        brightness = MediaProbeUtils.parsePixelOrNumber(details.opt("brightness"), 100f) ?: 100f,
                        blur = MediaProbeUtils.parsePixelOrNumber(details.opt("blur"), 0f) ?: 0f,
                        inTransition = inTrans,
                        outTransition = outTrans,
                        hasAudio = probe.hasAudio,
                        isMain = isMain,
                        orderIndex = orderIdx
                    )
                    visualClips.add(vClip)

                    if (type == "video" && probe.hasAudio && normVol > 0.001f) {
                        audioClips.add(
                            TimelineAudioClip(
                                id = "${id}_audio",
                                file = localFile,
                                displayFromMs = displayFromMs,
                                displayToMs = displayToMs,
                                trimFromMs = trimFromMs,
                                trimToMs = trimToMs,
                                playbackRate = playbackRate,
                                volume = normVol.coerceIn(0f, 1.5f)
                            )
                        )
                    }
                }
                "audio" -> {
                    val src = details.optString("src", "")
                    val localFile = resolveMediaSourceToFile(src) ?: continue
                    if (normVol > 0.001f) {
                        audioClips.add(
                            TimelineAudioClip(
                                id = id,
                                file = localFile,
                                displayFromMs = displayFromMs,
                                displayToMs = displayToMs,
                                trimFromMs = trimFromMs,
                                trimToMs = trimToMs,
                                playbackRate = playbackRate,
                                volume = normVol.coerceIn(0f, 1.5f)
                            )
                        )
                    }
                }
                "text", "caption" -> {
                    var rawContent = details.optString("text", "")
                    if (rawContent.isBlank()) {
                        val wordsArr = details.optJSONArray("words")
                        if (wordsArr != null) {
                            val sb = StringBuilder()
                            for (wIdx in 0 until wordsArr.length()) {
                                val wObj = wordsArr.optJSONObject(wIdx)
                                val word = wObj?.optString("word", "") ?: ""
                                if (word.isNotBlank()) {
                                    if (sb.isNotEmpty()) sb.append(" ")
                                    sb.append(word)
                                }
                            }
                            rawContent = sb.toString()
                        }
                    }
                    val cleaned = MediaProbeUtils.cleanTextContent(
                        rawContent,
                        details.optString("textTransform", "none")
                    )
                    if (cleaned.isNotBlank()) {
                        val tf = MediaProbeUtils.parseTransform(details.optString("transform", ""))
                        val rawFontSize = MediaProbeUtils.parsePixelOrNumber(details.opt("fontSize"), 64f) ?: 64f
                        val effectiveFontSize = max(14f, rawFontSize * tf.scaleY * dims.scaleY)

                        val unscaledW = MediaProbeUtils.parsePixelOrNumber(details.opt("width"), dims.origW * 0.8f) ?: (dims.origW * 0.8f)
                        val unscaledH = MediaProbeUtils.parsePixelOrNumber(details.opt("height"), rawFontSize * 1.4f) ?: (rawFontSize * 1.4f)
                        val visBoxW = unscaledW * tf.scaleX * dims.scaleX
                        val visBoxH = unscaledH * tf.scaleY * dims.scaleY

                        val leftVal = MediaProbeUtils.parsePixelOrNumber(details.opt("left"), null)
                        val topVal = MediaProbeUtils.parsePixelOrNumber(details.opt("top"), null)

                        val boxX: Float
                        val boxY: Float
                        if (leftVal == null && topVal == null) {
                            boxX = (dims.outW - visBoxW) / 2f
                            boxY = (dims.outH - visBoxH) / 2f
                        } else {
                            val l = (leftVal ?: 0f) + tf.translateX
                            val t = (topVal ?: 0f) + tf.translateY
                            val visWOrig = unscaledW * tf.scaleX
                            val visHOrig = unscaledH * tf.scaleY
                            boxX = (l + (unscaledW - visWOrig) / 2f) * dims.scaleX
                            boxY = (t + (unscaledH - visHOrig) / 2f) * dims.scaleY
                        }

                        val fontWeightStr = details.optString("fontWeight", "").lowercase(Locale.ROOT)
                        val isBold = fontWeightStr.contains("bold") || (fontWeightStr.toIntOrNull() ?: 400) >= 600

                        val textColor = MediaProbeUtils.parseCssColor(
                            details.optString("color", "#ffffff"),
                            opacity,
                            Color.WHITE
                        )

                        val bgStr = details.optString("backgroundColor", "").trim()
                        val bgColor = if (bgStr.isNotEmpty() && !bgStr.equals("transparent", true) && !bgStr.equals("none", true)) {
                            MediaProbeUtils.parseCssColor(bgStr, opacity * 0.85f, Color.TRANSPARENT)
                        } else null

                        val borderW = (MediaProbeUtils.parsePixelOrNumber(
                            details.opt("borderWidth") ?: details.opt("WebkitTextStrokeWidth"),
                            0f
                        ) ?: 0f) * dims.scaleY

                        val borderCol = MediaProbeUtils.parseCssColor(
                            details.optString("borderColor", details.optString("WebkitTextStrokeColor", "#000000")),
                            opacity,
                            Color.BLACK
                        )

                        textClips.add(
                            TimelineTextClip(
                                id = id,
                                text = cleaned,
                                displayFromMs = displayFromMs,
                                displayToMs = displayToMs,
                                boxX = boxX,
                                boxY = boxY,
                                boxW = visBoxW,
                                boxH = visBoxH,
                                fontSizePx = effectiveFontSize,
                                textColor = textColor,
                                backgroundColor = bgColor,
                                borderWidthPx = borderW,
                                borderColor = borderCol,
                                textAlign = details.optString("textAlign", "center"),
                                isBold = isBold,
                                opacity = opacity,
                                orderIndex = orderIdx
                            )
                        )
                    }
                }
            }
        }

        visualClips.sortWith { a, b ->
            val aMain = if (a.isMain) 0 else 1
            val bMain = if (b.isMain) 0 else 1
            if (aMain != bMain) {
                aMain - bMain
            } else if (a.displayFromMs != b.displayFromMs) {
                a.displayFromMs.compareTo(b.displayFromMs)
            } else {
                a.orderIndex.compareTo(b.orderIndex)
            }
        }

        textClips.sortBy { it.orderIndex }

        val fallbackDurationMs = design.optLong("duration", 5000L).takeIf { it > 0L } ?: 5000L
        val finalDurationMs = if (maxToMs > 0L) maxToMs else fallbackDurationMs

        return ParsedTimeline(
            visualClips = visualClips,
            textClips = textClips,
            audioClips = audioClips,
            totalDurationMs = max(500L, finalDurationMs)
        )
    }

    private fun extractTransitions(
        itemId: String,
        baseItem: JSONObject?,
        detailItem: JSONObject?,
        details: JSONObject,
        transitionsMap: JSONObject,
        options: JSONObject
    ): Pair<TransitionSpec?, TransitionSpec?> {
        var inTrans: TransitionSpec? = null
        var outTrans: TransitionSpec? = null

        val tKeys = transitionsMap.keys()
        while (tKeys.hasNext()) {
            val k = tKeys.next()
            val t = transitionsMap.optJSONObject(k) ?: continue
            val kind = t.optString("kind", "none")
            if (kind.isBlank() || kind == "none") continue
            val dur = t.optDouble("duration", 0.5).toFloat().coerceIn(0.15f, 2.0f)
            if (t.optString("toId") == itemId) {
                inTrans = TransitionSpec(kind, dur, t.optString("direction", "from-right"))
            }
            if (t.optString("fromId") == itemId) {
                outTrans = TransitionSpec(kind, dur, t.optString("direction", "from-left"))
            }
        }

        if (options.optBoolean("autoFade", false)) {
            if (inTrans == null) inTrans = TransitionSpec("fade", 0.35f, "from-right")
            if (outTrans == null) outTrans = TransitionSpec("fade", 0.35f, "from-left")
        }

        return inTrans to outTrans
    }

    private fun resolveMediaSourceToFile(rawSrc: String?): File? {
        if (rawSrc.isNullOrBlank()) return null
        val trimmed = rawSrc.trim()

        // 1. Local /api/media/files/...
        if (trimmed.contains("/api/media/files/")) {
            val after = trimmed.substringAfter("/api/media/files/").substringBefore("?")
            val safeName = File(URLDecoder.decode(after, "UTF-8")).name
            val candidate = File(uploadsDir, safeName)
            if (candidate.exists()) return candidate
        }

        // 2. Direct path
        if (trimmed.startsWith("/")) {
            val direct = File(trimmed)
            if (direct.exists()) return direct
        }

        // 3. Data URI
        if (trimmed.startsWith("data:")) {
            return try {
                val commaIdx = trimmed.indexOf(',')
                if (commaIdx < 0) return null
                val header = trimmed.substring(0, commaIdx)
                val dataPart = trimmed.substring(commaIdx + 1)
                val bytes = if (header.contains(";base64", true)) {
                    Base64.decode(dataPart, Base64.DEFAULT)
                } else {
                    URLDecoder.decode(dataPart, "UTF-8").toByteArray(Charsets.UTF_8)
                }
                val ext = when {
                    header.contains("png") -> ".png"
                    header.contains("webp") -> ".webp"
                    header.contains("jpeg") || header.contains("jpg") -> ".jpg"
                    header.contains("mp3") || header.contains("mpeg") -> ".mp3"
                    header.contains("wav") -> ".wav"
                    else -> ".mp4"
                }
                val hash = sha1Hex(bytes)
                val outFile = File(cacheDir, "data_$hash$ext")
                if (!outFile.exists()) {
                    FileOutputStream(outFile).use { it.write(bytes) }
                }
                outFile
            } catch (_: Exception) {
                null
            }
        }

        // 4. Remote HTTP/HTTPS URL
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return try {
                val hash = sha1Hex(trimmed.toByteArray(Charsets.UTF_8))
                val cleanUrlPath = trimmed.substringBefore("?").substringBefore("#")
                val dotExt = cleanUrlPath.substringAfterLast('.', "")
                val ext = if (dotExt.length in 2..4) ".$dotExt" else ".mp4"
                val cached = File(cacheDir, "remote_$hash$ext")
                if (cached.exists() && cached.length() > 0L) return cached

                val conn = (URL(trimmed).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                }
                conn.inputStream.use { input ->
                    FileOutputStream(cached).use { output ->
                        input.copyTo(output)
                    }
                }
                cached
            } catch (_: Exception) {
                null
            }
        }

        return null
    }

    private fun decodeSampledBitmap(file: File, reqW: Int, reqH: Int): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            var sampleSize = 1
            val targetW = max(128, reqW)
            val targetH = max(128, reqH)
            if (opts.outHeight > targetH || opts.outWidth > targetW) {
                val halfH = opts.outHeight / 2
                val halfW = opts.outWidth / 2
                while ((halfH / sampleSize) >= targetH && (halfW / sampleSize) >= targetW) {
                    sampleSize *= 2
                }
            }
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
        } catch (_: Exception) {
            null
        }
    }

    private fun exportMp4ToAndroidGallery(sourceFile: File, jobId: String): String? {
        if (!sourceFile.exists()) return null
        val displayFileName = "TabletVideoStudio_${jobId}.mp4"

        // 1. Save via Android MediaStore into Movies/TabletVideoStudio (Works on Android 10-15 without storage permissions)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, displayFileName)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(
                        MediaStore.Video.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_MOVIES + "/TabletVideoStudio"
                    )
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { out ->
                        FileInputStream(sourceFile).use { input ->
                            input.copyTo(out)
                        }
                    }
                    values.clear()
                    values.put(MediaStore.Video.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }

                // Also save a copy in MediaStore.Downloads so it shows in the Downloads app immediately
                val dlValues = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, displayFileName)
                    put(MediaStore.Downloads.MIME_TYPE, "video/mp4")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val dlUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, dlValues)
                if (dlUri != null) {
                    resolver.openOutputStream(dlUri)?.use { out ->
                        FileInputStream(sourceFile).use { input ->
                            input.copyTo(out)
                        }
                    }
                    dlValues.clear()
                    dlValues.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(dlUri, dlValues, null, null)
                }
                return "/sdcard/Movies/TabletVideoStudio/$displayFileName"
            }
        } catch (_: Exception) {
            // Fallback to direct public directory below
        }

        // 2. Fallback direct copy for older Android versions
        try {
            val dlDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dlDir.exists()) dlDir.mkdirs()
            val destFile = File(dlDir, displayFileName)
            sourceFile.copyTo(destFile, overwrite = true)
            MediaScannerConnection.scanFile(
                context,
                arrayOf(destFile.absolutePath),
                arrayOf("video/mp4"),
                null
            )
            return destFile.absolutePath
        } catch (_: Exception) {
            return sourceFile.absolutePath
        }
    }

    private fun copyJsonInto(src: JSONObject, dst: JSONObject) {
        val keys = src.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            dst.put(k, src.opt(k))
        }
    }

    private fun sha1Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }.take(14)
    }
}
