package io.github.xgl34222220.baize

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.nio.file.Files
import java.io.File

class IndependentRuleBundlesTest {
    private val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private fun pack(version: Long, missing: Boolean = false): ByteArray {
        val files = JSONObject()
        IndependentRuleBundles.NAMES.filter { !missing || it != "deep.rules" }.forEach { files.put(it, Base64.getEncoder().encodeToString("# test $version".toByteArray())) }
        val payload = JSONObject().put("schema", 1).put("version", version).put("minAppBuild", 1).put("files", files).toString().toByteArray()
        val signature = Signature.getInstance("SHA256withRSA").run { initSign(pair.private); update(payload); sign() }
        return JSONObject().put("payload", Base64.getEncoder().encodeToString(payload)).put("signature", Base64.getEncoder().encodeToString(signature)).toString().toByteArray()
    }
    @Test fun verifiedAtomicVersionsRollbackAndTamperFallback() {
        val root = Files.createTempDirectory("baize-rules-test").toFile()
        try {
            val store = IndependentRuleBundles(root, listOf(pair.public), 2)
            store.install(store.verify(pack(1))); store.install(store.verify(pack(2)))
            assertEquals(2L, store.active()!!.version)
            assertTrue(runCatching { store.install(store.verify(pack(1))) }.isFailure)
            store.rollback(); assertEquals(1L, store.active()!!.version)
            File(root, "version-1.json").writeText("tampered")
            assertNull(store.active())
            store.useBundled(); assertNull(store.active())
        } finally { root.deleteRecursively() }
    }
    @Test fun untrustedMissingOrIncompatiblePacksFailClosed() {
        val root = Files.createTempDirectory("baize-rules-test").toFile()
        try {
            assertTrue(runCatching { IndependentRuleBundles(root, emptyList(), 2).verify(pack(1)) }.isFailure)
            assertTrue(runCatching { IndependentRuleBundles(root, listOf(pair.public), 0).verify(pack(1)) }.isFailure)
            assertTrue(runCatching { IndependentRuleBundles(root, listOf(pair.public), 2).verify(pack(1, true)) }.isFailure)
            assertFalse(File(root, "active").exists())
        } finally { root.deleteRecursively() }
    }
    @Test fun customPreviewIsReadOnlyBoundedToExactDirectoryAndAge() {
        val old = StorageFileRecord(1, "uri", "/storage/emulated/0/Download/a.tmp", "a.tmp", 10, 1, "").withVerifiedStorageIdentity()
        val subdir = old.copy(path = "/storage/emulated/0/Download/child/a.tmp")
        val fresh = old.copy(modifiedSeconds = 1_000_000)
        val result = LocalCustomRulePreview.preview("/storage/emulated/0/Download/*.tmp|7", StorageIndexResult(listOf(old, subdir, fresh), 0, false), 1_000_001_000L)
        assertEquals(listOf(old), result.matches)
        assertTrue(result.complete)
        assertTrue(runCatching { LocalCustomRulePreview.parse("/storage/emulated/0/../a|0") }.isFailure)
        assertTrue(runCatching { LocalCustomRulePreview.parse("/data/user/0/*|0") }.isFailure)
        assertTrue(runCatching { LocalCustomRulePreview.parse("/storage/emulated/0/Download/report#old.tmp|7") }.isFailure)
        assertTrue(runCatching { LocalCustomRulePreview.parse("/storage/emulated/0/Download/report |7") }.isFailure)
        assertFalse(LocalCustomRulePreview.preview(result.rule, StorageIndexResult(listOf(old), 0, true)).complete)
    }
}
