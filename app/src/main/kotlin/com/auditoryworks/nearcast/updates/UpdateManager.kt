package com.auditoryworks.nearcast.updates

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import com.auditoryworks.nearcast.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
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
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun checkUpdate(): AppUpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val url = "$API_BASE_URL/updates/product/$PRODUCT_SLUG?sort=version:desc&limit=1&populate=otaFiles.file"
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                val data = json.optJSONArray("data") ?: return@withContext null
                if (data.length() == 0) return@withContext null
                
                val latest = data.getJSONObject(0)
                val remoteVersion = latest.getString("version").removePrefix("v")
                val currentVersion = BuildConfig.VERSION_NAME.removePrefix("v")
                
                if (isNewerVersion(remoteVersion, currentVersion)) {
                    val otaFiles = latest.optJSONArray("otaFiles")
                    if (otaFiles != null && otaFiles.length() > 0) {
                        val fileObj = otaFiles.getJSONObject(0).optJSONObject("file")
                        val downloadUrl = fileObj?.optString("url")
                        if (!downloadUrl.isNullOrBlank()) {
                            // Mosapi might return relative URLs
                            val fullUrl = if (downloadUrl.startsWith("http")) downloadUrl else "https://mosapi.auditoryworks.co$downloadUrl"
                            return@withContext AppUpdateInfo(
                                version = remoteVersion,
                                downloadUrl = fullUrl,
                                changelog = latest.optString("description", "")
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Check update failed", e)
        }
        null
    }

    private fun isNewerVersion(remote: String, current: String): Boolean {
        val remoteParts = remote.split("-")[0].split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = current.split("-")[0].split(".").mapNotNull { it.toIntOrNull() }
        
        for (i in 0 until maxOf(remoteParts.size, currentParts.size)) {
            val r = remoteParts.getOrNull(i) ?: 0
            val c = currentParts.getOrNull(i) ?: 0
            if (r > c) return true
            if (r < c) return false
        }
        
        // If versions are same, check suffixes (beta/alpha) - simplified logic
        return remote.contains("beta") && !current.contains("beta") && current.contains("alpha") 
                || (remote != current && remote.length > current.length && remote.startsWith(current)) 
                // Fallback to string comparison if simple numeric fails
                || remote > current
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
