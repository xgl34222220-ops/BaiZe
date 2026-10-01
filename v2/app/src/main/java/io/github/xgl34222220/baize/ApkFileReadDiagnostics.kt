package io.github.xgl34222220.baize

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.MediaStore
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import io.github.xgl34222220.baize.root.IProfileRootService
import io.github.xgl34222220.baize.root.RootServiceClients
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One selected/indexed file only. No directory contents, logs, identifiers or APK bytes collected. */
internal object ApkFileReadDiagnostics {
    fun stat(path: String): JSONObject = try { stat(Os.lstat(path)) }
        catch (error: Exception) { failure(error) }

    private fun stat(value: StructStat): JSONObject = JSONObject().put("ok", true)
        .put("kind", when { OsConstants.S_ISREG(value.st_mode) -> "regular"; OsConstants.S_ISDIR(value.st_mode) -> "directory"
            OsConstants.S_ISLNK(value.st_mode) -> "symlink"; else -> "other" })
        .put("device", value.st_dev).put("inode", value.st_ino).put("bytes", value.st_size)
        .put("modified", value.st_mtime).put("changed", value.st_ctime)

    private fun failure(error: Exception) = JSONObject().put("ok", false).put("error", error.javaClass.simpleName)
        .put("message", error.message.orEmpty().replace('\n', ' ').take(240))
        .put("errno", generateSequence<Throwable>(error) { it.cause }.take(6).filterIsInstance<ErrnoException>()
            .firstOrNull()?.errno ?: JSONObject.NULL)

    @Suppress("DEPRECATION")
    fun collect(context: Context, uriString: String, path: String, remote: IProfileRootService?,
        scanIdentity: ApkFileIdentity? = null, cancellation: android.os.CancellationSignal? = null,
        indexedFile: Boolean = false): String {
        cancellation?.throwIfCanceled()
        val report = JSONObject().put("diagnosticVersion", 1).put("appVersionCode", BuildConfig.VERSION_CODE)
            .put("androidApi", Build.VERSION.SDK_INT).put("deviceModel", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("appUid", Process.myUid()).put("allFilesAccess", ApkMediaStoreIndex.hasAllFilesAccess())
            .put("path", path).put("uri", uriString).put("appPathStat", stat(path))
            .put("scanIdentity", scanIdentity?.json() ?: JSONObject.NULL)
            .put("currentAppIdentity", ApkDeletionGuard.forContext(context).capture(path)?.json() ?: JSONObject.NULL)
            .put("primaryStorage", android.os.Environment.getExternalStorageDirectory().absolutePath)
            .put("canonicalPrimaryStorage", runCatching { android.os.Environment.getExternalStorageDirectory().canonicalPath }.getOrDefault("unavailable"))
        report.put("appParentStat", File(path).parent?.let(::stat) ?: JSONObject.NULL)
        report.put("canonicalPath", runCatching { File(path).canonicalPath }.getOrDefault("unavailable"))
        if (ApkDeletionGuard.validUri(uriString)) {
            val uri = Uri.parse(uriString)
            try {
                val fields = arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED)
                val row = context.contentResolver.query(uri, fields, null, null, null, cancellation)?.use { cursor ->
                    if (!cursor.moveToFirst()) JSONObject().put("exists", false) else JSONObject().put("exists", true)
                        .put("path", cursor.getString(cursor.getColumnIndexOrThrow(fields[0])))
                        .put("bytes", cursor.getLong(cursor.getColumnIndexOrThrow(fields[1])))
                        .put("modified", cursor.getLong(cursor.getColumnIndexOrThrow(fields[2])))
                }
                report.put("index", row ?: JSONObject().put("available", false))
            } catch (error: Exception) { report.put("index", failure(error)) }
            cancellation?.throwIfCanceled()
            try { context.contentResolver.openFileDescriptor(uri, "r", cancellation)?.use {
                report.put("mediaStoreFd", stat(Os.fstat(it.fileDescriptor)))
            } ?: report.put("mediaStoreFd", JSONObject().put("ok", false).put("error", "null_descriptor"))
            } catch (error: Exception) { report.put("mediaStoreFd", failure(error)) }
        }
        cancellation?.throwIfCanceled()
        if (remote == null) report.put("root", JSONObject().put("connected", false))
        else try {
            val ping = remote.ping()
            val version = JSONObject(ping)
            val supported = if (indexedFile) version.optInt("uid", -1) == 0 && version.optBoolean("root") &&
                version.optInt("indexedFileEvidenceVersion") == 1 else ApkRootFileEvidence.supported(ping)
            val root = JSONObject().put("connected", true).put("uid", version.optInt("uid", -1))
                .put("rootVersionCode", version.optLong("rootVersionCode", -1L))
                .put("moduleVersionCode", version.opt("moduleVersionCode") ?: JSONObject.NULL)
                .put("fileEvidenceSupported", supported)
            if (supported) root.put("file", JSONObject(RootServiceClients.profileExchange(
                remote, context.cacheDir, if (indexedFile) "getIndexedFileEvidence" else "getApkFileEvidence", JSONArray().put(path))))
            else root.put("note", "当前清理服务不支持文件身份诊断，请重新连接服务后重扫")
            report.put("root", root)
        } catch (error: Exception) { report.put("root", failure(error).put("connected", false)) }
        return report.toString(2)
    }
}
