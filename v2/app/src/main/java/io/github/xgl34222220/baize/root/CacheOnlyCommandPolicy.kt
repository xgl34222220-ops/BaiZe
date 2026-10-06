package io.github.xgl34222220.baize.root

internal object CacheOnlyCommandPolicy {
    fun command(packageName: String, userId: Int): List<String> {
        require(userId in 0..999 && Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+").matches(packageName))
        require(packageName !in setOf("android", "com.android.systemui", "io.github.xgl34222220.baize"))
        return listOf("/system/bin/cmd", "package", "clear", "--cache-only", "--user", userId.toString(), packageName)
    }
}
