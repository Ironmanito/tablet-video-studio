package com.tabletstudio.videoeditor.model

import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadataRetriever
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class MediaItemMeta(
    val id: String,
    val name: String,
    val fileName: String,
    val url: String,
    val type: String, // "video" | "audio" | "image"
    val durationMs: Long,
    val durationSec: Double,
    val width: Int?,
    val height: Int?,
    val sizeBytes: Long,
    val hasVideo: Boolean,
    val hasAudio: Boolean,
    val createdAt: Long
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("id", id)
        obj.put("name", name)
        obj.put("fileName", fileName)
        obj.put("url", url)
        obj.put("type", type)
        obj.put("durationMs", durationMs)
        obj.put("duration", durationSec)
        if (width != null) obj.put("width", width) else obj.put("width", JSONObject.NULL)
        if (height != null) obj.put("height", height) else obj.put("height", JSONObject.NULL)
        obj.put("sizeBytes", sizeBytes)
        obj.put("size", sizeBytes)
        obj.put("createdAt", createdAt)
        return obj
    }

    companion object {
        fun fromJson(obj: JSONObject): MediaItemMeta {
            val durMs = obj.optLong("durationMs", 0L)
            val durSec = obj.optDouble("duration", durMs / 1000.0)
            return MediaItemMeta(
                id = obj.optString("id", ""),
                name = obj.optString("name", "Archivo"),
                fileName = obj.optString("fileName", ""),
                url = obj.optString("url", ""),
                type = obj.optString("type", "video"),
                durationMs = durMs,
                durationSec = if (durSec.isNaN()) 0.0 else durSec,
                width = if (obj.isNull("width")) null else obj.optInt("width", 1080),
                height = if (obj.isNull("height")) null else obj.optInt("height", 1920),
                sizeBytes = obj.optLong("sizeBytes", obj.optLong("size", 0L)),
                hasVideo = obj.optString("type", "video") != "audio",
                hasAudio = obj.optString("type", "video") != "image",
                createdAt = obj.optLong("createdAt", System.currentTimeMillis())
            )
        }
    }
}

class RenderJob(
    val id: String,
    val outputFile: File,
    @Volatile var status: String = "PENDING", // PENDING | COMPLETED | FAILED
    @Volatile var progress: Int = 2,
    @Volatile var speed: String = "preparando...",
    @Volatile var fps: Int = 30,
    @Volatile var width: Int = 1080,
    @Volatile var height: Int = 1920,
    @Volatile var durationMs: Long = 5000L,
    @Volatile var savedToGalleryPath: String? = null,
    @Volatile var error: String? = null,
    @Volatile var cancelled: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) {
    val downloadUrl: String
        get() = "/api/render/download/${outputFile.name}"

    fun toVideoJson(): JSONObject {
        val v = JSONObject()
        v.put("id", id)
        v.put("status", status)
        v.put("progress", progress)
        v.put("url", downloadUrl)
        v.put("speed", speed)
        v.put("fps", fps)
        v.put("width", width)
        v.put("height", height)
        v.put("durationMs", durationMs)
        if (savedToGalleryPath != null) {
            v.put("savedToGalleryPath", savedToGalleryPath)
        } else {
            v.put("savedToGalleryPath", JSONObject.NULL)
        }
        if (error != null) {
            v.put("error", error)
        } else {
            v.put("error", JSONObject.NULL)
        }
        return v
    }
}

data class TransformValues(
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val translateX: Float = 0f,
    val translateY: Float = 0f
)

data class TargetDimensions(
    val origW: Int,
    val origH: Int,
    val outW: Int,
    val outH: Int,
    val scaleX: Float,
    val scaleY: Float
)

data class VisualBox(
    val rawW: Int,
    val rawH: Int,
    val finalW: Int,
    val finalH: Int,
    val finalX: Float,
    val finalY: Float,
    val hasCustomCrop: Boolean,
    val cropX: Int,
    val cropY: Int
)

data class TransitionSpec(
    val kind: String,
    val durationSec: Float,
    val direction: String
)

data class TimelineVisualClip(
    val id: String,
    val type: String, // "video" | "image"
    val file: File,
    val displayFromMs: Long,
    val displayToMs: Long,
    val trimFromMs: Long,
    val trimToMs: Long,
    val playbackRate: Float,
    val volume: Float, // 0f..1.5f
    val opacity: Float, // 0f..1f
    val box: VisualBox,
    val borderRadiusPx: Float,
    val borderWidthPx: Float,
    val borderColor: Int,
    val flipX: Boolean,
    val flipY: Boolean,
    val brightness: Float, // 100 = normal
    val contrast: Float = 100f, // 100 = normal
    val saturate: Float = 100f, // 100 = normal
    val sepia: Float = 0f, // 0..100
    val grayscale: Float = 0f, // 0..100
    val blur: Float,
    val blurFill: Boolean = false,
    val cameraMotion: String = "none",
    val inTransition: TransitionSpec?,
    val outTransition: TransitionSpec?,
    val hasAudio: Boolean,
    val isMain: Boolean,
    val orderIndex: Int
)

data class TimelineTextClip(
    val id: String,
    val text: String,
    val displayFromMs: Long,
    val displayToMs: Long,
    val boxX: Float,
    val boxY: Float,
    val boxW: Float,
    val boxH: Float,
    val fontSizePx: Float,
    val textColor: Int,
    val backgroundColor: Int?,
    val borderWidthPx: Float,
    val borderColor: Int,
    val textAlign: String, // "left" | "center" | "right"
    val isBold: Boolean,
    val opacity: Float,
    val orderIndex: Int
)

data class TimelineAudioClip(
    val id: String,
    val file: File,
    val displayFromMs: Long,
    val displayToMs: Long,
    val trimFromMs: Long,
    val trimToMs: Long,
    val playbackRate: Float,
    val volume: Float, // 0f..1.5f
    val fadeIn: Boolean = false,
    val fadeOut: Boolean = false
)

object MediaProbeUtils {

    fun toEvenInt(value: Double, minVal: Int = 2): Int {
        if (!value.isFinite() || value <= minVal) return minVal
        return max(minVal, (value / 2.0).roundToInt() * 2)
    }

    fun classifyMediaType(fileName: String, hasVideo: Boolean, hasAudio: Boolean): String {
        val lower = fileName.lowercase(Locale.ROOT)
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
            lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".bmp") ||
            lower.endsWith(".svg")
        ) {
            return "image"
        }
        if (lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".ogg") ||
            lower.endsWith(".m4a") || lower.endsWith(".aac") || lower.endsWith(".flac") ||
            lower.endsWith(".opus") || lower.contains("voz-en-off")
        ) {
            return "audio"
        }
        if (lower.endsWith(".mp4") || lower.endsWith(".webm") || lower.endsWith(".mov") ||
            lower.endsWith(".mkv")
        ) {
            return "video"
        }
        if (hasVideo) return "video"
        if (hasAudio) return "audio"
        return "video"
    }

    fun probeMediaFile(file: File, id: String, displayName: String): MediaItemMeta {
        val extLower = file.name.lowercase(Locale.ROOT)
        val isImageExt = extLower.endsWith(".png") || extLower.endsWith(".jpg") ||
            extLower.endsWith(".jpeg") || extLower.endsWith(".webp") ||
            extLower.endsWith(".gif") || extLower.endsWith(".bmp")

        if (isImageExt) {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            val w = if (opts.outWidth > 0) opts.outWidth else 1080
            val h = if (opts.outHeight > 0) opts.outHeight else 1920
            return MediaItemMeta(
                id = id,
                name = displayName,
                fileName = file.name,
                url = "/api/media/files/${file.name}",
                type = "image",
                durationMs = 5000L,
                durationSec = 5.0,
                width = w,
                height = h,
                sizeBytes = file.length(),
                hasVideo = true,
                hasAudio = false,
                createdAt = file.lastModified()
            )
        }

        var durationMs = 0L
        var width: Int? = null
        var height: Int? = null
        var hasVideo = false
        var hasAudio = false

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            durationMs = durStr?.toLongOrNull() ?: 0L

            val hasVideoStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
            hasVideo = !hasVideoStr.isNullOrEmpty() && hasVideoStr != "no"

            val hasAudioStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
            hasAudio = !hasAudioStr.isNullOrEmpty() && hasAudioStr != "no"

            if (hasVideo) {
                val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                val rot = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                if (w != null && h != null) {
                    if (rot == 90 || rot == 270) {
                        width = h
                        height = w
                    } else {
                        width = w
                        height = h
                    }
                }
            }
        } catch (_: Exception) {
            // Ignore corrupt metadata
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }

        val mediaType = classifyMediaType(file.name, hasVideo, hasAudio)
        val finalDurationMs = if (durationMs > 0L) durationMs else 5000L
        return MediaItemMeta(
            id = id,
            name = displayName,
            fileName = file.name,
            url = "/api/media/files/${file.name}",
            type = mediaType,
            durationMs = finalDurationMs,
            durationSec = finalDurationMs / 1000.0,
            width = width ?: if (mediaType == "audio") null else 1080,
            height = height ?: if (mediaType == "audio") null else 1920,
            sizeBytes = file.length(),
            hasVideo = mediaType != "audio",
            hasAudio = hasAudio || mediaType == "audio",
            createdAt = file.lastModified()
        )
    }

    fun parsePixelOrNumber(raw: Any?, fallback: Float? = null): Float? {
        if (raw == null || raw == JSONObject.NULL) return fallback
        if (raw is Number) {
            val f = raw.toFloat()
            return if (f.isFinite()) f else fallback
        }
        val str = raw.toString().trim()
        if (str.isEmpty() || str.equals("auto", true) || str.equals("none", true)) {
            return fallback
        }
        val clean = str.replace(Regex("px$", RegexOption.IGNORE_CASE), "").trim()
        return clean.toFloatOrNull() ?: fallback
    }

    fun parseTransform(transformStr: String?): TransformValues {
        if (transformStr.isNullOrBlank() || transformStr.equals("none", true)) {
            return TransformValues()
        }
        var sx = 1f
        var sy = 1f
        var tx = 0f
        var ty = 0f

        val scaleRegex = Regex("""scale\(\s*([^,)]+)(?:\s*,\s*([^)]+))?\s*\)""", RegexOption.IGNORE_CASE)
        scaleRegex.find(transformStr)?.let { m ->
            val v1 = m.groupValues[1].trim().toFloatOrNull()
            val v2 = m.groupValues.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() }?.toFloatOrNull() ?: v1
            if (v1 != null && v1 > 0f) sx = v1
            if (v2 != null && v2 > 0f) sy = v2
        }

        val scale3dRegex = Regex("""scale3d\(\s*([^,]+)\s*,\s*([^,]+)\s*,\s*([^)]+)\s*\)""", RegexOption.IGNORE_CASE)
        scale3dRegex.find(transformStr)?.let { m ->
            val v1 = m.groupValues[1].trim().toFloatOrNull()
            val v2 = m.groupValues[2].trim().toFloatOrNull()
            if (v1 != null && v1 > 0f) sx = v1
            if (v2 != null && v2 > 0f) sy = v2
        }

        val transRegex = Regex("""translate(?:3d)?\(\s*([^,)]+)(?:\s*,\s*([^,)]+))?""", RegexOption.IGNORE_CASE)
        transRegex.find(transformStr)?.let { m ->
            val v1 = parsePixelOrNumber(m.groupValues[1], 0f) ?: 0f
            val v2 = parsePixelOrNumber(m.groupValues.getOrNull(2), 0f) ?: 0f
            tx = v1
            ty = v2
        }

        return TransformValues(sx, sy, tx, ty)
    }

    fun parseCssColor(colorStr: String?, opacityMultiplier: Float = 1f, defaultColor: Int = Color.WHITE): Int {
        val clampedOpacity = opacityMultiplier.coerceIn(0f, 1f)
        if (colorStr.isNullOrBlank()) {
            return applyAlphaMultiplier(defaultColor, clampedOpacity)
        }
        val c = colorStr.trim()
        if (c.equals("transparent", true) || c.equals("none", true)) {
            return Color.TRANSPARENT
        }

        try {
            if (c.startsWith("#")) {
                var hex = c.substring(1)
                if (hex.length == 3) {
                    hex = "${hex[0]}${hex[0]}${hex[1]}${hex[1]}${hex[2]}${hex[2]}"
                }
                if (hex.length == 6) {
                    val rgb = hex.toInt(16)
                    val a = (255 * clampedOpacity).roundToInt().coerceIn(0, 255)
                    return (a shl 24) or (rgb and 0x00FFFFFF)
                }
                if (hex.length == 8) {
                    val r = hex.substring(0, 2).toInt(16)
                    val g = hex.substring(2, 4).toInt(16)
                    val b = hex.substring(4, 6).toInt(16)
                    val aOrig = hex.substring(6, 8).toInt(16) / 255f
                    val a = (255 * aOrig * clampedOpacity).roundToInt().coerceIn(0, 255)
                    return Color.argb(a, r, g, b)
                }
            }

            val rgbRegex = Regex("""rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)(?:\s*,\s*([0-9.]+))?\s*\)""", RegexOption.IGNORE_CASE)
            rgbRegex.find(c)?.let { m ->
                val r = m.groupValues[1].toIntOrNull()?.coerceIn(0, 255) ?: 255
                val g = m.groupValues[2].toIntOrNull()?.coerceIn(0, 255) ?: 255
                val b = m.groupValues[3].toIntOrNull()?.coerceIn(0, 255) ?: 255
                val aOrig = m.groupValues.getOrNull(4)?.takeIf { it.isNotEmpty() }?.toFloatOrNull() ?: 1f
                val a = (255 * aOrig.coerceIn(0f, 1f) * clampedOpacity).roundToInt().coerceIn(0, 255)
                return Color.argb(a, r, g, b)
            }

            val parsed = Color.parseColor(c)
            return applyAlphaMultiplier(parsed, clampedOpacity)
        } catch (_: Exception) {
            return applyAlphaMultiplier(defaultColor, clampedOpacity)
        }
    }

    private fun applyAlphaMultiplier(color: Int, multiplier: Float): Int {
        val a = (Color.alpha(color) * multiplier.coerceIn(0f, 1f)).roundToInt().coerceIn(0, 255)
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }

    fun cleanTextContent(rawText: String?, textTransform: String?): String {
        if (rawText.isNullOrBlank()) return ""
        var text = rawText
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</div>\\s*<div[^>]*>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</p>\\s*<p[^>]*>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ", ignoreCase = true)
            .replace("&amp;", "&", ignoreCase = true)
            .replace("&lt;", "<", ignoreCase = true)
            .replace("&gt;", ">", ignoreCase = true)
            .replace("&quot;", "\"", ignoreCase = true)
            .replace("&#39;", "'", ignoreCase = true)
            .trim()

        when (textTransform?.lowercase(Locale.ROOT)) {
            "uppercase" -> text = text.uppercase(Locale.getDefault())
            "lowercase" -> text = text.lowercase(Locale.getDefault())
            "capitalize" -> {
                text = text.split(" ").joinToString(" ") { word ->
                    word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
                }
            }
        }
        return text
    }

    fun resolveTargetDimensions(designSize: JSONObject?, options: JSONObject?): TargetDimensions {
        val origW = designSize?.optInt("width", 1080)?.takeIf { it > 0 } ?: 1080
        val origH = designSize?.optInt("height", 1920)?.takeIf { it > 0 } ?: 1920

        var baseW = origW
        var baseH = origH

        val ar = options?.optString("aspectRatio", "current") ?: "current"
        when (ar) {
            "16:9" -> {
                baseW = 1920
                baseH = 1080
            }
            "9:16" -> {
                baseW = 1080
                baseH = 1920
            }
            "1:1" -> {
                baseW = 1080
                baseH = 1080
            }
            else -> {
                val optSize = options?.optJSONObject("size")
                if (optSize != null) {
                    val sw = optSize.optInt("width", 0)
                    val sh = optSize.optInt("height", 0)
                    if (sw > 0 && sh > 0) {
                        baseW = sw
                        baseH = sh
                    }
                }
            }
        }

        val presetProfile = options?.optString("presetProfile", "quality") ?: "quality"
        var scaleFactor = 1.0
        if (presetProfile == "fast") {
            val shortSide = min(baseW, baseH)
            if (shortSide > 720) {
                scaleFactor = 720.0 / shortSide.toDouble()
            }
        }

        val outW = toEvenInt(baseW * scaleFactor, 16)
        val outH = toEvenInt(baseH * scaleFactor, 16)

        return TargetDimensions(
            origW = origW,
            origH = origH,
            outW = outW,
            outH = outH,
            scaleX = outW.toFloat() / origW.toFloat(),
            scaleY = outH.toFloat() / origH.toFloat()
        )
    }

    fun computeVisualBox(
        details: JSONObject,
        intrinsicW: Int?,
        intrinsicH: Int?,
        dims: TargetDimensions
    ): VisualBox {
        val tf = parseTransform(details.optString("transform", ""))
        val rawW = parsePixelOrNumber(details.opt("width"), (intrinsicW ?: dims.origW).toFloat()) ?: dims.origW.toFloat()
        val rawH = parsePixelOrNumber(details.opt("height"), (intrinsicH ?: dims.origH).toFloat()) ?: dims.origH.toFloat()

        val cropObj = details.optJSONObject("crop")
        val cropW = cropObj?.optDouble("width", 0.0)?.toFloat() ?: 0f
        val cropH = cropObj?.optDouble("height", 0.0)?.toFloat() ?: 0f
        val hasCustomCrop = cropObj != null && cropW > 0f && cropH > 0f

        val boxUnscaledW = if (hasCustomCrop) cropW else rawW
        val boxUnscaledH = if (hasCustomCrop) cropH else rawH

        val leftVal = parsePixelOrNumber(details.opt("left"), null)
        val topVal = parsePixelOrNumber(details.opt("top"), null)

        val finalW: Int
        val finalH: Int
        val finalX: Float
        val finalY: Float

        val hasTransform = details.optString("transform", "").isNotBlank() &&
            !details.optString("transform", "").equals("none", true)

        if (leftVal == null && topVal == null) {
            val fitScale = min(dims.origW.toFloat() / boxUnscaledW, dims.origH.toFloat() / boxUnscaledH)
            val visW = boxUnscaledW * if (hasTransform) tf.scaleX else fitScale
            val visH = boxUnscaledH * if (hasTransform) tf.scaleY else fitScale
            val cx = (dims.origW - visW) / 2f
            val cy = (dims.origH - visH) / 2f

            finalW = toEvenInt((visW * dims.scaleX).toDouble(), 2)
            finalH = toEvenInt((visH * dims.scaleY).toDouble(), 2)
            finalX = cx * dims.scaleX
            finalY = cy * dims.scaleY
        } else {
            val l = (leftVal ?: 0f) + tf.translateX
            val t = (topVal ?: 0f) + tf.translateY
            val visW = boxUnscaledW * tf.scaleX
            val visH = boxUnscaledH * tf.scaleY

            val originStr = details.optString("transformOrigin", "center center").lowercase(Locale.ROOT)
            val isTopLeftOrigin = originStr.startsWith("0") ||
                originStr.startsWith("top left") ||
                originStr.startsWith("left top")

            val visX = if (isTopLeftOrigin) l else l + (boxUnscaledW - visW) / 2f
            val visY = if (isTopLeftOrigin) t else t + (boxUnscaledH - visH) / 2f

            finalW = toEvenInt((visW * dims.scaleX).toDouble(), 2)
            finalH = toEvenInt((visH * dims.scaleY).toDouble(), 2)
            finalX = visX * dims.scaleX
            finalY = visY * dims.scaleY
        }

        val cropX = if (hasCustomCrop) {
            max(0, ((cropObj?.optDouble("x", 0.0)?.toFloat() ?: 0f) * tf.scaleX * dims.scaleX).roundToInt())
        } else 0
        val cropY = if (hasCustomCrop) {
            max(0, ((cropObj?.optDouble("y", 0.0)?.toFloat() ?: 0f) * tf.scaleY * dims.scaleY).roundToInt())
        } else 0

        return VisualBox(
            rawW = toEvenInt((rawW * tf.scaleX * dims.scaleX).toDouble(), 2),
            rawH = toEvenInt((rawH * tf.scaleY * dims.scaleY).toDouble(), 2),
            finalW = finalW,
            finalH = finalH,
            finalX = finalX,
            finalY = finalY,
            hasCustomCrop = hasCustomCrop,
            cropX = cropX,
            cropY = cropY
        )
    }
}
