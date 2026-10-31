package com.tabletstudio.videoeditor.server

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import com.tabletstudio.videoeditor.engine.AndroidMediaCodecRenderer
import com.tabletstudio.videoeditor.model.MediaItemMeta
import com.tabletstudio.videoeditor.model.MediaProbeUtils
import com.tabletstudio.videoeditor.model.RenderJob
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class LocalStudioServer(private val context: Context) {

    val uploadsDir = File(context.filesDir, "uploads").apply { mkdirs() }
    val cacheDir = File(context.filesDir, "cache").apply { mkdirs() }
    val outputsDir = File(context.filesDir, "outputs").apply { mkdirs() }
    private val metaFile = File(uploadsDir, ".meta.json")

    private val renderJobs = ConcurrentHashMap<String, RenderJob>()
    private val mediaMetaMap = ConcurrentHashMap<String, MediaItemMeta>()
    private val executor = Executors.newCachedThreadPool()
    private val renderer = AndroidMediaCodecRenderer(context, uploadsDir, cacheDir)

    @Volatile
    private var running = false
    private var serverSocket: ServerSocket? = null

    var activePort: Int = 18450
        private set

    val baseUrl: String
        get() = "http://127.0.0.1:$activePort"

    init {
        loadMetaMap()
        seedDemoMediaIfEmpty()
    }

    fun startServer(): Int {
        if (running && serverSocket != null && !serverSocket!!.isClosed) {
            return activePort
        }

        for (candidatePort in 18450..18465) {
            try {
                val ss = ServerSocket(candidatePort, 64, InetAddress.getByName("127.0.0.1"))
                ss.reuseAddress = true
                serverSocket = ss
                activePort = candidatePort
                running = true
                break
            } catch (_: Exception) {
                // Try next port
            }
        }

        executor.execute {
            while (running) {
                try {
                    val client = serverSocket?.accept() ?: break
                    executor.execute { handleClientSocket(client) }
                } catch (_: Exception) {
                    if (!running) break
                }
            }
        }

        return activePort
    }

    fun stopServer() {
        running = false
        for (job in renderJobs.values) {
            job.cancelled = true
        }
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
    }

    fun getLatestExportedFile(): File? {
        val files = outputsDir.listFiles { f -> f.isFile && f.name.endsWith(".mp4", true) }
        return files?.maxByOrNull { it.lastModified() }
    }

    fun resolveExportedFile(urlOrName: String): File? {
        val clean = urlOrName.substringBefore("?").substringAfterLast("/")
        val decoded = try {
            URLDecoder.decode(clean, "UTF-8")
        } catch (_: Exception) {
            clean
        }
        val candidate = File(outputsDir, File(decoded).name)
        if (candidate.exists()) return candidate
        return getLatestExportedFile()
    }

    fun importAndroidUri(uri: Uri): MediaItemMeta? {
        return try {
            val rawName = queryDisplayName(uri) ?: "video_${System.currentTimeMillis()}.mp4"
            val safeBase = sanitizeFileName(rawName)
            val uniqueId = UUID.randomUUID().toString().replace("-", "").take(10)
            val savedName = "${uniqueId}_$safeBase"
            val destFile = File(uploadsDir, savedName)

            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return null

            if (!destFile.exists() || destFile.length() == 0L) return null

            val meta = MediaProbeUtils.probeMediaFile(destFile, uniqueId, safeBase)
            mediaMetaMap[savedName] = meta
            saveMetaMap()
            meta
        } catch (_: Exception) {
            null
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) {
                    cursor.getString(idx)
                } else null
            } ?: uri.lastPathSegment
        } catch (_: Exception) {
            uri.lastPathSegment
        }
    }

    private fun seedDemoMediaIfEmpty() {
        try {
            val seedMarker = File(uploadsDir, ".seeded")
            if (seedMarker.exists()) return

            val existing = uploadsDir.listFiles { f -> f.isFile && !f.name.startsWith(".") }
            if (!existing.isNullOrEmpty()) {
                seedMarker.createNewFile()
                return
            }

            val demoFiles = context.assets.list("demo_uploads") ?: return
            for (demoName in demoFiles) {
                val dest = File(uploadsDir, demoName)
                context.assets.open("demo_uploads/$demoName").use { input ->
                    FileOutputStream(dest).use { output ->
                        input.copyTo(output)
                    }
                }
                val id = demoName.substringBeforeLast(".")
                val meta = MediaProbeUtils.probeMediaFile(dest, id, demoName)
                mediaMetaMap[demoName] = meta
            }
            saveMetaMap()
            seedMarker.createNewFile()
        } catch (_: Exception) {
        }
    }

    private fun loadMetaMap() {
        try {
            if (!metaFile.exists()) return
            val json = JSONObject(metaFile.readText(Charsets.UTF_8))
            val keys = json.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val obj = json.optJSONObject(k) ?: continue
                mediaMetaMap[k] = MediaItemMeta.fromJson(obj)
            }
        } catch (_: Exception) {
        }
    }

    @Synchronized
    private fun saveMetaMap() {
        try {
            val root = JSONObject()
            for ((k, v) in mediaMetaMap) {
                root.put(k, v.toJson())
            }
            metaFile.writeText(root.toString(2), Charsets.UTF_8)
        } catch (_: Exception) {
        }
    }

    private fun handleClientSocket(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 60_000
            val input = BufferedInputStream(s.getInputStream())
            val output = s.getOutputStream()

            val requestLine = readAsciiLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0].uppercase(Locale.ROOT)
            val fullUri = parts[1]
            val pathPart = fullUri.substringBefore("?")
            val queryPart = if (fullUri.contains("?")) fullUri.substringAfter("?") else ""
            val queryParams = parseQueryParams(queryPart)

            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readAsciiLine(input) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    val k = line.substring(0, colon).trim().lowercase(Locale.ROOT)
                    val v = line.substring(colon + 1).trim()
                    headers[k] = v
                }
            }

            if (method == "OPTIONS") {
                sendEmptyResponse(output, "204 No Content")
                return
            }

            try {
                routeHttpRequest(method, pathPart, queryParams, headers, input, output)
            } catch (e: Throwable) {
                try {
                    val err = JSONObject().put("error", e.message ?: "Internal Server Error")
                    sendJsonResponse(output, "500 Internal Server Error", err)
                } catch (_: Throwable) {}
            }
        }
    }

    private fun routeHttpRequest(
        method: String,
        path: String,
        query: Map<String, String>,
        headers: Map<String, String>,
        input: InputStream,
        output: OutputStream
    ) {
        // 1. GET /api/health
        if (method == "GET" && path == "/api/health") {
            val res = JSONObject().apply {
                put("ok", true)
                put("engine", "Android MediaCodec Hardware ARM64")
                put("ffmpeg", "MediaCodec-HW-ARM64")
                put("arch", "arm64-v8a")
                put("platform", "android-native")
            }
            sendJsonResponse(output, "200 OK", res)
            return
        }

        // 2. GET /api/voices
        if (method == "GET" && path == "/api/voices") {
            val arr = JSONArray().apply {
                put(
                    JSONObject()
                        .put("voice_id", "es-ar-standard")
                        .put("name", "Voz Español Tablet (Nativa)")
                        .put("language", "es")
                        .put("gender", "Neutral")
                )
            }
            sendJsonArrayResponse(output, "200 OK", arr)
            return
        }

        // 3. GET /api/media/list
        if (method == "GET" && path == "/api/media/list") {
            val entries = uploadsDir.listFiles { f -> f.isFile && !f.name.startsWith(".") } ?: emptyArray()
            val list = mutableListOf<MediaItemMeta>()
            var dirty = false

            for (file in entries) {
                var meta = mediaMetaMap[file.name]
                if (meta == null) {
                    val cleanDisplay = file.name.replace(Regex("^[a-z0-9]+_"), "")
                    val id = file.name.substringBefore("_").ifEmpty { file.name }
                    meta = MediaProbeUtils.probeMediaFile(file, id, cleanDisplay)
                    mediaMetaMap[file.name] = meta
                    dirty = true
                }
                list.add(meta)
            }

            if (dirty) saveMetaMap()
            list.sortByDescending { it.createdAt }

            val filesJson = JSONArray()
            for (m in list) {
                filesJson.put(m.toJson())
            }
            val payload = JSONObject().apply {
                put("ok", true)
                put("files", filesJson)
                put("uploads", filesJson)
            }
            sendJsonResponse(output, "200 OK", payload)
            return
        }

        // 4. POST /api/media/upload
        if (method == "POST" && path == "/api/media/upload") {
            val contentLength = headers["content-length"]?.toLongOrNull() ?: 0L
            val contentType = headers["content-type"] ?: ""

            var originalName = query["name"] ?: headers["x-file-name"] ?: "upload_${System.currentTimeMillis()}.mp4"
            val decodedOrig = try {
                URLDecoder.decode(originalName, "UTF-8")
            } catch (_: Exception) {
                originalName
            }
            val safeBase = sanitizeFileName(decodedOrig)
            val uniqueId = UUID.randomUUID().toString().replace("-", "").take(10)
            val savedName = "${uniqueId}_$safeBase"
            val savedFile = File(uploadsDir, savedName)

            var uploadOk = false

            if (contentType.contains("application/json", true)) {
                val rawBytes = readFixedBytes(input, contentLength.toInt())
                val bodyObj = JSONObject(String(rawBytes, Charsets.UTF_8))
                val jsonName = bodyObj.optString("fileName", bodyObj.optString("name", originalName))
                val b64 = bodyObj.optString("base64Data", bodyObj.optString("data", ""))
                val cleanB64 = if (b64.contains(",")) b64.substringAfter(",") else b64
                val fileBytes = Base64.decode(cleanB64, Base64.DEFAULT)
                if (fileBytes.isNotEmpty()) {
                    FileOutputStream(savedFile).use { it.write(fileBytes) }
                    uploadOk = true
                }
            } else if (contentType.contains("multipart/form-data", true)) {
                val boundaryMatch = Regex("""boundary=(?:"([^"]+)"|([^;]+))""", RegexOption.IGNORE_CASE).find(contentType)
                val boundary = boundaryMatch?.let { it.groupValues[1].ifEmpty { it.groupValues[2] }.trim() }
                if (!boundary.isNullOrEmpty()) {
                    uploadOk = streamMultipartToFile(input, contentLength, boundary, savedFile)
                } else {
                    val rawBytes = readFixedBytes(input, contentLength.toInt())
                    val extracted = extractMultipartFile(rawBytes, contentType)
                    if (extracted != null && extracted.second.isNotEmpty()) {
                        FileOutputStream(savedFile).use { it.write(extracted.second) }
                        uploadOk = true
                    }
                }
            } else {
                // Streaming directo a disco usando bloques de 64KB (cero acumulación en memoria RAM)
                uploadOk = streamBodyToFile(input, contentLength, savedFile)
            }

            if (!uploadOk || !savedFile.exists() || savedFile.length() == 0L) {
                if (savedFile.exists()) savedFile.delete()
                sendJsonResponse(
                    output,
                    "400 Bad Request",
                    JSONObject().put("error", "Error recibiendo archivo o archivo vacío")
                )
                return
            }

            val itemMeta = MediaProbeUtils.probeMediaFile(savedFile, uniqueId, safeBase)
            mediaMetaMap[savedName] = itemMeta
            saveMetaMap()

            sendJsonResponse(output, "200 OK", itemMeta.toJson())
            return
        }

        // 5. GET / HEAD / DELETE /api/media/files/:filename
        if (path.startsWith("/api/media/files/")) {
            val rawFile = path.removePrefix("/api/media/files/")
            val safeName = File(URLDecoder.decode(rawFile, "UTF-8")).name
            val targetFile = File(uploadsDir, safeName)

            if (method == "DELETE") {
                if (targetFile.exists()) targetFile.delete()
                mediaMetaMap.remove(safeName)
                saveMetaMap()
                sendJsonResponse(output, "200 OK", JSONObject().put("ok", true))
                return
            }

            if (method == "GET" || method == "HEAD") {
                serveFileWithRange(
                    output = output,
                    file = targetFile,
                    rangeHeader = headers["range"],
                    headOnly = method == "HEAD",
                    downloadName = null,
                    inline = true
                )
                return
            }
        }

        // 6. POST /api/render
        if (method == "POST" && path == "/api/render") {
            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            val rawBytes = readFixedBytes(input, contentLength)
            val bodyStr = if (rawBytes.isNotEmpty()) String(rawBytes, Charsets.UTF_8) else "{}"
            val body = JSONObject(bodyStr)
            val design = body.optJSONObject("design") ?: body.optJSONObject("payload") ?: body
            val options = body.optJSONObject("options") ?: JSONObject()

            val jobId = "render_${System.currentTimeMillis().toString(36)}_${UUID.randomUUID().toString().take(4)}"
            val outFile = File(outputsDir, "$jobId.mp4")

            val job = RenderJob(
                id = jobId,
                outputFile = outFile,
                status = "PENDING",
                progress = 2,
                fps = options.optInt("fps", design.optInt("fps", 30))
            )
            renderJobs[jobId] = job

            executor.execute {
                try {
                    renderer.renderTimelineJob(job, design, options)
                } catch (t: Throwable) {
                    job.status = "FAILED"
                    job.error = t.message ?: "Error de memoria o hardware durante el renderizado"
                }
            }

            val resp = JSONObject().put("video", job.toVideoJson())
            sendJsonResponse(output, "200 OK", resp)
            return
        }

        // 7. POST /api/render/cancel or DELETE /api/render
        if ((method == "POST" && path == "/api/render/cancel") ||
            (method == "DELETE" && path == "/api/render")
        ) {
            var jobId = query["id"]
            if (jobId.isNullOrBlank() && method == "POST") {
                val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                val rawBytes = readFixedBytes(input, contentLength)
                if (rawBytes.isNotEmpty()) {
                    val body = JSONObject(String(rawBytes, Charsets.UTF_8))
                    jobId = body.optString("id", "")
                }
            }
            if (!jobId.isNullOrBlank()) {
                renderJobs[jobId]?.let { job ->
                    job.cancelled = true
                    job.status = "FAILED"
                    job.error = "Renderizado cancelado por el usuario."
                }
            }
            sendJsonResponse(output, "200 OK", JSONObject().put("ok", true))
            return
        }

        // 8. GET /api/render?id=<jobId>
        if (method == "GET" && path == "/api/render") {
            val jobId = query["id"] ?: ""
            val job = renderJobs[jobId]
            if (job == null) {
                val notFound = JSONObject()
                    .put("error", "Job de renderizado no encontrado")
                    .put(
                        "video",
                        JSONObject()
                            .put("id", jobId)
                            .put("status", "FAILED")
                            .put("progress", 0)
                            .put("error", "Job de renderizado no encontrado")
                    )
                sendJsonResponse(output, "404 Not Found", notFound)
                return
            }

            val resp = JSONObject().put("video", job.toVideoJson())
            sendJsonResponse(output, "200 OK", resp)
            return
        }

        // 9. GET /api/render/download/:file
        if ((method == "GET" || method == "HEAD") && path.startsWith("/api/render/download/")) {
            val rawFile = path.removePrefix("/api/render/download/")
            val safeFile = File(URLDecoder.decode(rawFile, "UTF-8")).name
            val targetFile = File(outputsDir, safeFile)
            val isInline = query["inline"] == "1"

            serveFileWithRange(
                output = output,
                file = targetFile,
                rangeHeader = headers["range"],
                headOnly = method == "HEAD",
                downloadName = safeFile,
                inline = isInline
            )
            return
        }

        // 10. Static SPA Assets from assets/webapp/
        serveWebappAsset(output, path)
    }

    private fun serveWebappAsset(output: OutputStream, rawPath: String) {
        val cleanRel = rawPath.trimStart('/').ifEmpty { "index.html" }
        val assetCandidates = listOf("webapp/$cleanRel", "webapp/index.html")

        for (assetPath in assetCandidates) {
            try {
                val bytes = context.assets.open(assetPath).use { it.readBytes() }
                val mime = getMimeType(assetPath)
                val headers = buildString {
                    append("HTTP/1.1 200 OK\r\n")
                    append("Content-Type: $mime\r\n")
                    append("Content-Length: ${bytes.size}\r\n")
                    append("Access-Control-Allow-Origin: *\r\n")
                    append("Cache-Control: no-cache\r\n")
                    append("Connection: close\r\n\r\n")
                }
                output.write(headers.toByteArray(Charsets.UTF_8))
                output.write(bytes)
                output.flush()
                return
            } catch (_: Exception) {
                // Try fallback
            }
        }

        sendJsonResponse(
            output,
            "404 Not Found",
            JSONObject().put("error", "Asset not found: $rawPath")
        )
    }

    private fun serveFileWithRange(
        output: OutputStream,
        file: File,
        rangeHeader: String?,
        headOnly: Boolean,
        downloadName: String?,
        inline: Boolean
    ) {
        if (!file.exists() || !file.isFile) {
            sendJsonResponse(output, "404 Not Found", JSONObject().put("error", "File not found"))
            return
        }

        val totalSize = file.length()
        val mime = getMimeType(file.name)
        val dispHeader = if (!downloadName.isNullOrBlank()) {
            val mode = if (inline) "inline" else "attachment"
            "Content-Disposition: $mode; filename=\"${File(downloadName).name}\"\r\n"
        } else ""

        if (!rangeHeader.isNullOrBlank() && rangeHeader.startsWith("bytes=")) {
            val spec = rangeHeader.removePrefix("bytes=").trim()
            val dash = spec.indexOf('-')
            if (dash >= 0) {
                val startStr = spec.substring(0, dash).trim()
                val endStr = spec.substring(dash + 1).trim()

                var start = startStr.toLongOrNull() ?: 0L
                var end = endStr.toLongOrNull() ?: (totalSize - 1L)
                if (startStr.isEmpty() && endStr.isNotEmpty()) {
                    val suffix = endStr.toLongOrNull() ?: 0L
                    start = max(0L, totalSize - suffix)
                    end = totalSize - 1L
                }
                end = min(end, totalSize - 1L)

                if (start in 0 until totalSize && end >= start) {
                    val chunkLen = end - start + 1L
                    val respHeaders = buildString {
                        append("HTTP/1.1 206 Partial Content\r\n")
                        append("Content-Type: $mime\r\n")
                        append("Accept-Ranges: bytes\r\n")
                        append("Content-Range: bytes $start-$end/$totalSize\r\n")
                        append("Content-Length: $chunkLen\r\n")
                        append("Access-Control-Allow-Origin: *\r\n")
                        append(dispHeader)
                        append("Connection: close\r\n\r\n")
                    }
                    output.write(respHeaders.toByteArray(Charsets.UTF_8))
                    if (!headOnly) {
                        RandomAccessFile(file, "r").use { raf ->
                            raf.seek(start)
                            val buf = ByteArray(32768)
                            var remaining = chunkLen
                            while (remaining > 0L) {
                                val toRead = min(buf.size.toLong(), remaining).toInt()
                                val read = raf.read(buf, 0, toRead)
                                if (read <= 0) break
                                output.write(buf, 0, read)
                                remaining -= read
                            }
                        }
                    }
                    output.flush()
                    return
                }
            }
        }

        val respHeaders = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: $mime\r\n")
            append("Accept-Ranges: bytes\r\n")
            append("Content-Length: $totalSize\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append(dispHeader)
            append("Connection: close\r\n\r\n")
        }
        output.write(respHeaders.toByteArray(Charsets.UTF_8))
        if (!headOnly) {
            file.inputStream().use { fis ->
                fis.copyTo(output, 32768)
            }
        }
        output.flush()
    }

    private fun sendEmptyResponse(output: OutputStream, status: String) {
        val headers = buildString {
            append("HTTP/1.1 $status\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Methods: GET, POST, PUT, DELETE, OPTIONS\r\n")
            append("Access-Control-Allow-Headers: Content-Type, Range, X-File-Name\r\n")
            append("Content-Length: 0\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(headers.toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun sendJsonResponse(output: OutputStream, status: String, json: JSONObject) {
        val body = json.toString().toByteArray(Charsets.UTF_8)
        val headers = buildString {
            append("HTTP/1.1 $status\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Cache-Control: no-cache\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(headers.toByteArray(Charsets.UTF_8))
        output.write(body)
        output.flush()
    }

    private fun sendJsonArrayResponse(output: OutputStream, status: String, json: JSONArray) {
        val body = json.toString().toByteArray(Charsets.UTF_8)
        val headers = buildString {
            append("HTTP/1.1 $status\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Cache-Control: no-cache\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(headers.toByteArray(Charsets.UTF_8))
        output.write(body)
        output.flush()
    }

    private fun readAsciiLine(input: InputStream): String? {
        val baos = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) {
                return if (baos.size() == 0) null else baos.toString("UTF-8")
            }
            if (b == '\n'.code) break
            if (b != '\r'.code) {
                baos.write(b)
            }
        }
        return baos.toString("UTF-8")
    }

    private fun readFixedBytes(input: InputStream, count: Int): ByteArray {
        if (count <= 0) return ByteArray(0)
        val buffer = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(buffer, offset, count - offset)
            if (read <= 0) break
            offset += read
        }
        return if (offset == count) buffer else buffer.copyOf(offset)
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        val map = mutableMapOf<String, String>()
        for (pair in query.split("&")) {
            if (pair.isBlank()) continue
            val eq = pair.indexOf('=')
            if (eq > 0) {
                val k = URLDecoder.decode(pair.substring(0, eq), "UTF-8")
                val v = URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
                map[k] = v
            } else {
                map[URLDecoder.decode(pair, "UTF-8")] = ""
            }
        }
        return map
    }

    private fun sanitizeFileName(rawName: String): String {
        val base = File(rawName).name
        val cleaned = base.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return cleaned.ifEmpty { "media_${System.currentTimeMillis()}.mp4" }
    }

    private fun extractMultipartFile(buffer: ByteArray, contentType: String): Pair<String, ByteArray>? {
        val boundaryMatch = Regex("""boundary=(?:"([^"]+)"|([^;]+))""", RegexOption.IGNORE_CASE).find(contentType)
            ?: return null
        val boundary = boundaryMatch.groupValues[1].ifEmpty { boundaryMatch.groupValues[2] }.trim()
        val boundaryBytes = "--$boundary".toByteArray(Charsets.UTF_8)
        val headerEndBytes = "\r\n\r\n".toByteArray(Charsets.UTF_8)

        val firstPos = indexOfBytes(buffer, boundaryBytes, 0)
        if (firstPos < 0) return null

        val partStart = firstPos + boundaryBytes.size + 2
        val nextBoundary = indexOfBytes(buffer, boundaryBytes, partStart)
        if (nextBoundary < 0) return null

        val headerEnd = indexOfBytes(buffer, headerEndBytes, partStart)
        if (headerEnd < 0 || headerEnd >= nextBoundary) return null

        val headerStr = String(buffer, partStart, headerEnd - partStart, Charsets.UTF_8)
        val fnMatch = Regex("""filename="([^"]+)"""", RegexOption.IGNORE_CASE).find(headerStr)
        val fileName = fnMatch?.groupValues?.get(1) ?: "upload.mp4"

        val dataStart = headerEnd + 4
        val dataEnd = max(dataStart, nextBoundary - 2)
        return fileName to buffer.copyOfRange(dataStart, dataEnd)
    }

    private fun indexOfBytes(source: ByteArray, target: ByteArray, fromIndex: Int): Int {
        if (target.isEmpty() || source.size < target.size) return -1
        for (i in max(0, fromIndex)..(source.size - target.size)) {
            var match = true
            for (j in target.indices) {
                if (source[i + j] != target[j]) {
                    match = false
                    break
                }
            }
            if (match) return i
        }
        return -1
    }

    private fun streamBodyToFile(input: InputStream, totalBytes: Long, targetFile: File): Boolean {
        return try {
            FileOutputStream(targetFile).use { fos ->
                val buffer = ByteArray(65536)
                var remaining = if (totalBytes > 0) totalBytes else Long.MAX_VALUE
                while (remaining > 0) {
                    val toRead = min(remaining, buffer.size.toLong()).toInt()
                    val read = input.read(buffer, 0, toRead)
                    if (read <= 0) break
                    fos.write(buffer, 0, read)
                    if (totalBytes > 0) remaining -= read
                }
                fos.flush()
            }
            targetFile.length() > 0
        } catch (_: Exception) {
            false
        }
    }

    private fun streamMultipartToFile(
        input: InputStream,
        totalBytes: Long,
        boundary: String,
        targetFile: File
    ): Boolean {
        return try {
            val rawBytes = readFixedBytes(input, totalBytes.toInt())
            val extracted = extractMultipartFile(rawBytes, "multipart/form-data; boundary=$boundary")
            if (extracted != null && extracted.second.isNotEmpty()) {
                FileOutputStream(targetFile).use { it.write(extracted.second) }
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun getMimeType(path: String): String {
        val lower = path.lowercase(Locale.ROOT)
        return when {
            lower.endsWith(".html") -> "text/html; charset=utf-8"
            lower.endsWith(".js") || lower.endsWith(".mjs") -> "application/javascript; charset=utf-8"
            lower.endsWith(".css") -> "text/css; charset=utf-8"
            lower.endsWith(".json") -> "application/json; charset=utf-8"
            lower.endsWith(".svg") -> "image/svg+xml"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".gif") -> "image/gif"
            lower.endsWith(".woff2") -> "font/woff2"
            lower.endsWith(".woff") -> "font/woff"
            lower.endsWith(".ttf") -> "font/ttf"
            lower.endsWith(".mp4") -> "video/mp4"
            lower.endsWith(".webm") -> "video/webm"
            lower.endsWith(".mov") -> "video/quicktime"
            lower.endsWith(".mp3") -> "audio/mpeg"
            lower.endsWith(".wav") -> "audio/wav"
            lower.endsWith(".ogg") -> "audio/ogg"
            lower.endsWith(".m4a") -> "audio/mp4"
            else -> "application/octet-stream"
        }
    }
}
