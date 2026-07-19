package io.github.xgl34222220.baize

data class ScanCandidate(
    val appName: String,
    val packageName: String,
    val categoryLabel: String,
    val path: String,
    val userId: Int,
    val bytes: Long,
    val files: Long,
    val directories: Long,
    val whitelisted: Boolean,
    val readable: Boolean,
    val measured: Boolean,
    val complete: Boolean,
    val selected: Boolean = !whitelisted
)
