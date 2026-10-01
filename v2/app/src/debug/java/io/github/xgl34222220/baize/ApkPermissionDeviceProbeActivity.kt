package io.github.xgl34222220.baize

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import android.system.ErrnoException
import android.system.Os
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Read-only diagnostics for one shell-created APK on the disposable API36 emulator. */
class ApkPermissionDeviceProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); inspect(intent) }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); inspect(intent) }

    private fun inspect(request: Intent) {
        if (!(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))) { finish(); return }
        val phase = request.getStringExtra("phase").orEmpty()
        val path = request.getStringExtra("path").orEmpty()
        if (phase !in setOf("denied", "granted", "revoked", "restored", "restarted", "stale", "raw_refreshed", "public_refreshed") ||
            !path.matches(Regex("/storage/emulated/0/Download/baize-apk-permission-probe-[0-9]+/fixture.apk"))) { finish(); return }
        lifecycleScope.launch(Dispatchers.IO) {
            val out = File(filesDir, "apk-permission-probe").apply { mkdirs() }
            val result = JSONObject().put("phase", phase).put("uid", Process.myUid()).put("pid", Process.myPid())
                .put("api", Build.VERSION.SDK_INT).put("versionCode", BuildConfig.VERSION_CODE)
                .put("allFiles", ApkMediaStoreIndex.hasAllFilesAccess()).put("path", path)
            fun stat(target: String): JSONObject = try {
                val s = Os.lstat(target)
                JSONObject().put("ok", true).put("device", s.st_dev).put("inode", s.st_ino)
                    .put("mode", s.st_mode).put("size", s.st_size).put("modified", s.st_mtime)
            } catch (error: Exception) { JSONObject().put("ok", false).put("error", error.javaClass.simpleName)
                .put("errno", (error as? ErrnoException)?.errno ?: -1) }
            try {
                result.put("primary", Environment.getExternalStorageDirectory().absolutePath)
                    .put("canonicalPrimary", Environment.getExternalStorageDirectory().canonicalPath)
                    .put("canonicalFile", File(path).canonicalPath).put("file", stat(path))
                result.put("parents", JSONArray(listOf("/storage/emulated/0", "/storage/emulated/0/Download", File(path).parent!!)
                    .map { stat(it).put("path", it) }))
                val identity = ApkDeletionGuard.forContext(applicationContext).capture(path)
                result.put("identityCaptured", identity != null).put("identity", identity?.json() ?: JSONObject.NULL)
                val collection = MediaStore.Files.getContentUri("external")
                @Suppress("DEPRECATION")
                val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATA,
                    MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED)
                try {
                    contentResolver.query(collection, projection, "_data = ?", arrayOf(path), null)?.use { cursor ->
                        result.put("indexRows", cursor.count)
                        if (cursor.moveToFirst()) {
                            val uri = android.content.ContentUris.withAppendedId(collection, cursor.getLong(0))
                            val bytes = cursor.getLong(2); val modified = cursor.getLong(3)
                            result.put("uri", uri.toString()).put("indexBytes", bytes).put("indexModified", modified)
                            try { contentResolver.openFileDescriptor(uri, "r")?.use {
                                val s = Os.fstat(it.fileDescriptor)
                                result.put("providerFdReadable", true).put("providerFdSize", s.st_size)
                            } ?: result.put("providerFdReadable", false)
                            } catch (error: Exception) { result.put("providerFdReadable", false).put("providerFdError", error.javaClass.simpleName) }
                            val preview = ApkArchiveMetadata.inspect(applicationContext, uri.toString(), path, bytes, modified)
                            result.put("previewStatus", preview.parseStatus.name).put("previewFailure", preview.failureReason?.name ?: "")
                        }
                    } ?: result.put("indexRows", JSONObject.NULL)
                } catch (error: Exception) { result.put("indexError", error.javaClass.simpleName) }
                result.put("completed", true)
            } catch (error: Exception) { result.put("completed", false).put("error", error.javaClass.simpleName).put("message", error.message.orEmpty()) }
            File(out, "$phase.json").writeText(result.toString(2))
        }
    }
}
