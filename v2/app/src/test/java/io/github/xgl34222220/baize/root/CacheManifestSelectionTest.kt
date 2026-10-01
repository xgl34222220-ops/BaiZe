package io.github.xgl34222220.baize.root

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class CacheManifestSelectionTest {
    private fun sha(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    @Test fun selectionKeepsOriginalContentDigestRawNameAndExpiry() {
        val dir = kotlin.io.path.createTempDirectory("baize-cache-selection-").toFile()
        try {
            val a = "/data/user/0/io.baize.first/cache"
            val b = "/data/user/0/io.baize.second/cache"
            File(dir, "whitelist.conf").writeText(""); File(dir, "native-cache-packages.conf").writeText("")
            File(dir, "cache_scan.targets").writeText("$a\n$b\n")
            File(dir, "cache_scan.items.tsv").writeText("package\tcategory\tfiles\tbytes\tdirectories\tpath\nio.baize.first\tcache\t1\t3\t0\t$a\nio.baize.second\tcache\t1\t3\t0\t$b\n")
            fun record(pkg: String, path: String, digest: String) = listOf(pkg,"cache","1","2","3","4","5","6","7",path,digest).joinToString("\u0000",postfix="\u0000")
            val selected = record("io.baize.first", "$a/quote ' newline\nkept ", "a".repeat(64))
            File(dir, "cache_scan.manifest0").writeText(selected + record("io.baize.second", "$b/unselected", "b".repeat(64)))
            val epoch = System.currentTimeMillis()/1000 - 60
            File(dir, "cache_scan.env").writeText(buildString {
                append("snapshot_id=original\nepoch=$epoch\nmanifest_format=nul-v3-sha256\n")
                for ((key,name) in listOf("targets" to "cache_scan.targets", "items" to "cache_scan.items.tsv", "manifest" to "cache_scan.manifest0", "whitelist" to "whitelist.conf", "package_whitelist" to "native-cache-packages.conf"))
                    append("${key}_sha=${sha(File(dir,name))}\n")
            })
            val result = JSONObject(CacheSelectionRepository(dir).prepare("original", JSONObject().put(a,true).toString()))
            assertTrue(result.toString(),result.optBoolean("success"))
            assertEquals(selected,File(dir,"cache_scan.manifest0").readText())
            assertTrue(File(dir,"cache_scan.env").readLines().contains("epoch=$epoch"))
            val oldState=File(dir,"cache_scan.env").readText().replace("nul-v3-sha256","nul-v2")
            File(dir,"cache_scan.env").writeText(oldState)
            val rejected=JSONObject(CacheSelectionRepository(dir).prepare(result.getString("snapshotId"),"{\"__all_safe__\":true}"))
            assertEquals("unsupported_manifest",rejected.getString("error"))
            assertEquals(selected,File(dir,"cache_scan.manifest0").readText())
        } finally { dir.deleteRecursively() }
    }
}
