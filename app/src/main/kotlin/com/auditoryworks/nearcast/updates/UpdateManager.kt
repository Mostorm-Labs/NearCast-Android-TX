package com.auditoryworks.nearcast.updates

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import com.auditoryworks.nearcast.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

data class AppUpdateInfo(
    val version: String,
    val downloadUrl: String,
    val changelog: String?
)

object UpdateManager {
    private const val TAG = "UpdateManager"
    private const val PRODUCT_SLUG = "nearcast-tx-android"
    private const val API_BASE_URL = "https://mosapi.auditoryworks.co/v1"
    private const val UPDATE_QUERY =
        // Mosapi sorts the version field lexicographically, so asking for the
        // first version can incorrectly return 1.0.9 before 1.0.10. Fetch a
        // batch and select the highest semantic version locally instead.
        "sort=createdAt:desc&limit=50&populate=otaFiles.file,descriptions"
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun checkUpdate(): AppUpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val url = "$API_BASE_URL/updates/product/$PRODUCT_SLUG?$UPDATE_QUERY"
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                val data = json.optJSONArray("data") ?: return@withContext null
                if (data.length() == 0) return@withContext null

                val currentVersion = BuildConfig.VERSION_NAME.removePrefix("v")
                var bestUpdate: AppUpdateInfo? = null

                for (index in 0 until data.length()) {
                    // Strapi returns update fields under `attributes`; older
                    // Mosapi deployments returned them at the top level.
                    val updateEnvelope = data.getJSONObject(index)
                    val update = updateEnvelope.optJSONObject("attributes") ?: updateEnvelope
                    val remoteVersion = update.optString("version")
                        .removePrefix("v")
                        .takeIf { it.isNotBlank() }
                        ?: continue

                    if (!isNewerVersion(remoteVersion, currentVersion)) continue

                    val downloadUrl = extractDownloadUrl(update.optJSONArray("otaFiles"))
                    if (downloadUrl.isNullOrBlank()) continue

                    val candidate = AppUpdateInfo(
                        version = remoteVersion,
                        downloadUrl = downloadUrl,
                        changelog = extractChangelog(update)
                    )
                    if (bestUpdate == null ||
                        compareVersions(candidate.version, bestUpdate.version) > 0
                    ) {
                        bestUpdate = candidate
                    }
                }

                return@withContext bestUpdate
            }
        } catch (e: Exception) {
            Log.e(TAG, "Check update failed", e)
        }
        null
    }

    private fun isNewerVersion(remote: String, current: String): Boolean {
        return compareVersions(remote, current) > 0
    }

    /** Compare semantic versions, including alpha/beta suffixes. */
    private fun compareVersions(left: String, right: String): Int {
        val leftParts = parseVersion(left)
        val rightParts = parseVersion(right)

        for (index in 0 until maxOf(leftParts.first.size, rightParts.first.size)) {
            val l = leftParts.first.getOrNull(index) ?: 0
            val r = rightParts.first.getOrNull(index) ?: 0
            if (l != r) return l.compareTo(r)
        }

        val leftPre = leftParts.second
        val rightPre = rightParts.second
        if (leftPre.isEmpty() && rightPre.isEmpty()) return 0
        if (leftPre.isEmpty()) return 1
        if (rightPre.isEmpty()) return -1

        for (index in 0 until maxOf(leftPre.size, rightPre.size)) {
            val l = leftPre.getOrNull(index) ?: return -1
            val r = rightPre.getOrNull(index) ?: return 1
            val result = comparePreReleaseToken(l, r)
            if (result != 0) return result
        }
        return 0
    }

    private fun comparePreReleaseToken(left: String, right: String): Int {
        val leftMatch = Regex("^([A-Za-z]+)(\\d*)$").matchEntire(left)
        val rightMatch = Regex("^([A-Za-z]+)(\\d*)$").matchEntire(right)
        if (leftMatch != null && rightMatch != null) {
            val nameResult = leftMatch.groupValues[1].lowercase(Locale.US)
                .compareTo(rightMatch.groupValues[1].lowercase(Locale.US))
            if (nameResult != 0) return nameResult

            val leftNumber = leftMatch.groupValues[2].toIntOrNull()
            val rightNumber = rightMatch.groupValues[2].toIntOrNull()
            if (leftNumber != null && rightNumber != null) {
                return leftNumber.compareTo(rightNumber)
            }
            if (leftNumber != null) return 1
            if (rightNumber != null) return -1
            return 0
        }

        val leftNumber = left.toIntOrNull()
        val rightNumber = right.toIntOrNull()
        return when {
            leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
            leftNumber != null -> -1
            rightNumber != null -> 1
            else -> left.lowercase(Locale.US).compareTo(right.lowercase(Locale.US))
        }
    }

    private fun parseVersion(value: String): Pair<List<Int>, List<String>> {
        val normalized = value.trim().removePrefix("v")
        val sections = normalized.split('-', limit = 2)
        val numbers = sections[0].split('.').map { token ->
            token.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        }
        val preRelease = sections.getOrNull(1)
            ?.split('.', '-')
            ?.filter { it.isNotBlank() }
            ?: emptyList()
        return numbers to preRelease
    }

    private fun extractDownloadUrl(otaFiles: JSONArray?): String? {
        if (otaFiles == null || otaFiles.length() == 0) return null

        for (index in 0 until otaFiles.length()) {
            val fileObj = otaFiles.getJSONObject(index).optJSONObject("file") ?: continue

            val directUrl = fileObj.optString("url").takeIf { it.isNotBlank() }
            if (directUrl != null) {
                return resolveAbsoluteUrl(directUrl)
            }

            val nestedUrl = fileObj.optJSONObject("data")
                ?.optJSONObject("attributes")
                ?.optString("url")
                ?.takeIf { it.isNotBlank() }
            if (nestedUrl != null) {
                return resolveAbsoluteUrl(nestedUrl)
            }

            val attrsUrl = fileObj.optJSONObject("attributes")
                ?.optString("url")
                ?.takeIf { it.isNotBlank() }
            if (attrsUrl != null) {
                return resolveAbsoluteUrl(attrsUrl)
            }
        }

        return null
    }

    private fun extractChangelog(latest: JSONObject): String? {
        val descriptions = latest.optJSONArray("descriptions")
        if (descriptions != null) {
            val enUs = findDescriptionContent(descriptions, "en-us")
            if (!enUs.isNullOrBlank()) return enUs

            val any = findDescriptionContent(descriptions, null)
            if (!any.isNullOrBlank()) return any
        }

        val description = latest.optString("description").takeIf { it.isNotBlank() }
        if (description != null) return description

        return null
    }

    private fun findDescriptionContent(descriptions: JSONArray, locale: String?): String? {
        for (index in 0 until descriptions.length()) {
            val item = descriptions.optJSONObject(index) ?: continue
            if (locale != null && !locale.equals(item.optString("locale"), ignoreCase = true)) {
                continue
            }
            val content = item.optString("content").takeIf { it.isNotBlank() }
            if (content != null) return content
        }
        return null
    }

    private fun resolveAbsoluteUrl(url: String): String {
        return if (url.startsWith("http://") || url.startsWith("https://")) {
            url
        } else {
            "https://mosapi.auditoryworks.co$url"
        }
    }

    suspend fun downloadAndInstall(context: Context, updateInfo: AppUpdateInfo, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(updateInfo.downloadUrl).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw Exception("Download failed: ${response.code}")
                
                val body = response.body ?: throw Exception("Empty body")
                val totalBytes = body.contentLength()
                val apkFile = File(context.externalCacheDir ?: context.cacheDir, "update-${updateInfo.version}.apk")
                
                body.byteStream().use { input ->
                    apkFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var totalRead = 0L
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                            if (totalBytes > 0) {
                                onProgress(totalRead.toFloat() / totalBytes)
                            }
                        }
                    }
                }
                
                installApk(context, apkFile)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download and install failed", e)
            throw e
        }
    }

    private fun installApk(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
