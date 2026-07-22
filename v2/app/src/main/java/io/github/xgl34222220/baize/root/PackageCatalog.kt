package io.github.xgl34222220.baize.root

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

internal class PackageCatalog {
    fun installedPackagesJson(): String {
        val systemPackages = queryPackageNames("cmd package list packages -s").toSet()
        val thirdPartyPackages = queryPackageNames("cmd package list packages -3").toSet()
        val allPackages = linkedSetOf<String>()
        allPackages += queryPackageNames("cmd package list packages")
        allPackages += systemPackages
        allPackages += thirdPartyPackages

        if (allPackages.isEmpty()) {
            listOf("/data/user/0", "/data/user_de/0").forEach { rootPath ->
                File(rootPath).listFiles()
                    ?.asSequence()
                    ?.filter { it.isDirectory && RootValidation.packageName.matches(it.name) }
                    ?.mapTo(allPackages) { it.name }
            }
        }

        val packages = JSONArray()
        allPackages.asSequence()
            .filter { RootValidation.packageName.matches(it) }
            .sorted()
            .forEach { packageName ->
                packages.put(
                    JSONObject()
                        .put("packageName", packageName)
                        .put("system", packageName in systemPackages && packageName !in thirdPartyPackages)
                )
            }
        return JSONObject()
            .put("success", packages.length() > 0)
            .put("source", if (systemPackages.isNotEmpty() || thirdPartyPackages.isNotEmpty()) "root-cmd" else "data-fallback")
            .put("count", packages.length())
            .put("packages", packages)
            .toString()
    }

    private fun queryPackageNames(command: String): List<String> = runCatching {
        val process = ProcessBuilder("/system/bin/sh", "-c", command)
            .redirectErrorStream(true)
            .start()
        val lines = process.inputStream.bufferedReader().use { it.readLines() }
        if (!process.waitFor(8, TimeUnit.SECONDS)) process.destroyForcibly()
        lines.asSequence()
            .map { it.trim().removePrefix("package:").substringBefore(' ') }
            .filter { RootValidation.packageName.matches(it) }
            .distinct()
            .toList()
    }.getOrDefault(emptyList())
}
