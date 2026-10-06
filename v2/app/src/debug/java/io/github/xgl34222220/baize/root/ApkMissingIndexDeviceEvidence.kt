package io.github.xgl34222220.baize.root

import android.content.Context
import android.os.Build
import android.os.Looper
import android.os.Process
import org.json.JSONObject

/** Read-only real Root evidence for exactly the CI run's synthetic permission-probe APK. */
object ApkMissingIndexDeviceEvidence {
    @JvmStatic fun main(args: Array<String>) {
        check(Process.myUid() == 0)
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        val path = args.single()
        check(path.matches(Regex("/storage/emulated/0/Download/baize-apk-permission-probe-[0-9]+/fixture.apk")))
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
        val type = Class.forName("android.app.ActivityThread")
        val thread = type.getDeclaredMethod("systemMain").invoke(null)
        val system = type.getDeclaredMethod("getSystemContext").invoke(thread) as Context
        val context = system.createPackageContext("io.github.xgl34222220.baize", Context.CONTEXT_IGNORE_SECURITY)
        val appUid = context.applicationInfo.uid
        val evidence = JSONObject(ApkFileEvidenceRepository(ownAppUid = { appUid }).read(path, appUid))
            .put("uid", Process.myUid()).put("root", true)
        println(evidence.toString())
        kotlin.system.exitProcess(0)
    }
}
