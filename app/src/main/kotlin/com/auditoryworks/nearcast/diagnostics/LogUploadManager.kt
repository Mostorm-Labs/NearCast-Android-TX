package com.auditoryworks.nearcast.diagnostics

import android.content.Context
import android.os.Build
import com.auditoryworks.nearcast.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class LogUploadResult(
    val uploadId: String,
    val feedbackId: String?,
    val fileCount: Int,
    val totalBytes: Long
)

data class LogArchiveInfo(
    val fileCount: Int,
    val totalBytes: Long,
    val truncatedCount: Int
)

object LogUploadManager {
    private const val API_BASE_URL = "https://mosapi.auditoryworks.co/v1"
    private const val MAX_BYTES = 50L * 1024L * 1024L
    private const val LOGCAT_LINE_LIMIT = "20000"
    private const val DEFAULT_DESCRIPTION = "NearHub Cast logs"
    private const val DEFAULT_EMAIL = "example@mail.com"
    private const val TRACE_FILE_NAME = "002-webrtc-flow.txt"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build()

    suspend fun upload(
        context: Context,
        email: String,
        description: String
    ): LogUploadResult = withContext(Dispatchers.IO) {
        val safeDescription = description.ifBlank { DEFAULT_DESCRIPTION }
        val archive = createArchive(context)
        try {
            val uploadId = uploadArchive(archive.file)
            val feedbackId = submitFeedback(
                attachmentId = uploadId,
                email = email,
                description = safeDescription
            )
            LogUploadResult(
                uploadId = uploadId,
                feedbackId = feedbackId,
                fileCount = archive.info.fileCount,
                totalBytes = archive.info.totalBytes
            )
        } finally {
            archive.file.delete()
        }
    }

    private fun createArchive(context: Context): CreatedArchive {
        val stamp = timestampForFileName()
        val archiveFile = File(context.cacheDir, "nearhub-cast-logs-$stamp.zip")
        val logcat = readLogcat(context.packageName)
        val workflowTrace = SessionTraceRecorder.snapshot()

        ZipOutputStream(archiveFile.outputStream().buffered()).use { zip ->
            val manifest = buildManifest(context, logcat, workflowTrace)
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("001-app-logcat.txt"))
            zip.write(logcat.text.toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry(TRACE_FILE_NAME))
            zip.write(workflowTrace.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }

        val totalBytes = archiveFile.length()
        return CreatedArchive(
            file = archiveFile,
            info = LogArchiveInfo(
                fileCount = 3,
                totalBytes = totalBytes,
                truncatedCount = if (logcat.truncated) 1 else 0
            )
        )
    }

    private fun readLogcat(packageName: String): LogcatCapture {
        val filters = listOf(
            packageName,
            "com.auditoryworks.nearcast",
            "WebRtcManager",
            "SystemAudioCapture",
            "ScreenCaptureService",
            "NearHubSignalingClient",
            "LocalDirectSignalingClient",
            "NsdDiscoveryManager",
            "org.webrtc.Logging",
            "AndroidRuntime",
            "System.err"
        )
        val builder = StringBuilder()
        var capturedBytes = 0L
        var truncated = false
        var error: String? = null

        try {
            val process = ProcessBuilder("logcat", "-d", "-v", "time", "-t", LOGCAT_LINE_LIMIT)
                .redirectErrorStream(true)
                .start()
            BufferedReader(InputStreamReader(process.inputStream)).useLines { lines ->
                for (line in lines) {
                    if (!filters.any { line.contains(it, ignoreCase = true) }) continue
                    val bytesNeeded = line.toByteArray(Charsets.UTF_8).size + 1
                    if (capturedBytes + bytesNeeded > MAX_BYTES) {
                        truncated = true
                        break
                    }
                    builder.append(line).append('\n')
                    capturedBytes += bytesNeeded
                }
            }
            val finished = process.waitFor(5, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                error = "logcat timed out"
            } else if (process.exitValue() != 0) {
                error = "logcat exited with code ${process.exitValue()}"
            }
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }

        if (builder.isEmpty()) {
            builder.append("No matching NearHub Cast logcat entries were captured.\n")
        }
        error?.let {
            builder.append("\nlogcat capture warning: ").append(it).append('\n')
        }

        return LogcatCapture(
            text = builder.toString(),
            truncated = truncated,
            error = error
        )
    }

    private fun buildManifest(
        context: Context,
        logcat: LogcatCapture,
        workflowTrace: String
    ): JSONObject {
        return JSONObject().apply {
            put("format", "nearhub-cast-diagnostic-logs-v1")
            put("createdUtc", timestampUtc())
            put("packageName", context.packageName)
            put("applicationVersion", BuildConfig.VERSION_NAME)
            put("versionCode", BuildConfig.VERSION_CODE)
            put("maxBytes", MAX_BYTES)
            put("logcatError", logcat.error ?: JSONObject.NULL)
            put("files", JSONArray().apply {
                put(JSONObject().apply {
                    put("name", "001-app-logcat.txt")
                    put("bytes", logcat.text.toByteArray(Charsets.UTF_8).size)
                    put("truncated", logcat.truncated)
                })
                put(JSONObject().apply {
                    put("name", TRACE_FILE_NAME)
                    put("bytes", workflowTrace.toByteArray(Charsets.UTF_8).size)
                    put("truncated", false)
                })
            })
            put("device", JSONObject().apply {
                put("manufacturer", Build.MANUFACTURER)
                put("model", Build.MODEL)
                put("brand", Build.BRAND)
                put("device", Build.DEVICE)
                put("sdkInt", Build.VERSION.SDK_INT)
                put("release", Build.VERSION.RELEASE)
            })
        }
    }

    private fun uploadArchive(archiveFile: File): String {
        val fileBody = archiveFile.asRequestBody("application/octet-stream".toMediaType())
        val fileInfo = JSONObject()
            .put("name", archiveFile.name)
            .put("folder", "diagnostic")
            .toString()
            .toRequestBody(null)

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("files", "blob", fileBody)
            .addFormDataPart("fileInfo", null, fileInfo)
            .build()

        val request = Request.Builder()
            .url("$API_BASE_URL/upload")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw mosapiHttpError("upload", response.code, responseBody)
            }
            val parsed = parseJsonOrNull(responseBody)
            return findFirstId(parsed)
                ?: throw IllegalStateException("Mosapi upload response did not contain a file id: $responseBody")
        }
    }

    private fun submitFeedback(
        attachmentId: String,
        email: String,
        description: String
    ): String? {
        val payload = JSONObject().apply {
            put("data", JSONObject().apply {
                put("appVersion", "NearHub Cast ${BuildConfig.VERSION_NAME}")
                put("client", JSONObject().apply {
                    put("name", "Unknown")
                    put("email", email.ifBlank { DEFAULT_EMAIL })
                    put("phone", "Unknown")
                    put("location", "Unknown")
                })
                put("equipment", buildEquipment())
                put("content", JSONObject().apply {
                    put("title", "NearHub Cast logs: ${description.normalizedTitle()}")
                    put("message", description)
                    put("attachments", JSONArray().apply {
                        put(JSONObject().put("id", attachmentId))
                    })
                })
                put("application", JSONObject().apply {
                    put("connect", JSONArray().apply {
                        put(JSONObject().put("id", 2))
                    })
                })
                put("timestamp", System.currentTimeMillis().toString())
            })
        }

        val request = Request.Builder()
            .url("$API_BASE_URL/feedbacks")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw mosapiHttpError("feedback", response.code, responseBody)
            }
            return findFirstId(parseJsonOrNull(responseBody))
        }
    }

    private fun mosapiHttpError(operation: String, code: Int, responseBody: String): IllegalStateException {
        val serverMessage = findFirstMessage(parseJsonOrNull(responseBody))
            ?: responseBody.replace(Regex("\\s+"), " ").trim().takeIf { it.isNotBlank() }
            ?: "empty response"
        val compactMessage = if (serverMessage.length > 300) {
            serverMessage.substring(0, 300) + "..."
        } else {
            serverMessage
        }
        return IllegalStateException("Mosapi $operation returned HTTP $code: $compactMessage")
    }

    private fun buildEquipment(): JSONObject {
        return JSONObject().apply {
            put("category", "Android")
            put("manufacture", Build.MANUFACTURER)
            put("model", JSONObject().put("name", Build.MODEL))
            put("hardware", JSONObject().apply {
                put("processor", Build.HARDWARE)
                put("memory", "Unknown")
                put("storage", "Unknown")
            })
            put("software", JSONObject().apply {
                put("systemVersion", "Android ${Build.VERSION.RELEASE}")
                put("systemType", "android")
                put("language", Locale.getDefault().language)
                put("region", Locale.getDefault().country)
            })
            put("graphics", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "GPU")
                    put("model", "Unknown")
                    put("vendor", "")
                })
            })
            put("displays", JSONArray().apply {
                put(JSONObject().apply {
                    put("name", "Android display")
                    put("resolution", "Unknown")
                    put("isMain", true)
                    put("connectionType", "Internal")
                })
            })
        }
    }

    private fun parseJsonOrNull(value: String): Any? {
        if (value.isBlank()) return null
        return try {
            JSONObject(value)
        } catch (_: Exception) {
            try {
                JSONArray(value)
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun findFirstId(value: Any?): String? {
        return when (value) {
            is JSONObject -> {
                val direct = value.optString("id").takeIf { it.isNotBlank() }
                if (direct != null) return direct
                val keys = value.keys()
                while (keys.hasNext()) {
                    findFirstId(value.opt(keys.next()))?.let { return it }
                }
                null
            }
            is JSONArray -> {
                for (index in 0 until value.length()) {
                    findFirstId(value.opt(index))?.let { return it }
                }
                null
            }
            else -> null
        }
    }

    private fun findFirstMessage(value: Any?): String? {
        return when (value) {
            is JSONObject -> {
                val direct = value.optString("message").takeIf { it.isNotBlank() }
                if (direct != null) return direct
                val keys = value.keys()
                while (keys.hasNext()) {
                    findFirstMessage(value.opt(keys.next()))?.let { return it }
                }
                null
            }
            is JSONArray -> {
                for (index in 0 until value.length()) {
                    findFirstMessage(value.opt(index))?.let { return it }
                }
                null
            }
            else -> null
        }
    }

    private fun String.normalizedTitle(): String {
        val normalized = replace(Regex("\\s+"), " ").trim().ifBlank { DEFAULT_DESCRIPTION }
        return if (normalized.length > 50) normalized.substring(0, 50) else normalized
    }

    private fun timestampUtc(): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date())
    }

    private fun timestampForFileName(): String {
        val format = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date())
    }

    private data class LogcatCapture(
        val text: String,
        val truncated: Boolean,
        val error: String?
    )

    private data class CreatedArchive(
        val file: File,
        val info: LogArchiveInfo
    )
}
