package io.github.xgl34222220.baize

data class ProfileCandidate(
    val id: String,
    val appName: String,
    val packageName: String,
    val categoryLabel: String,
    val risk: String,
    val path: String,
    val bytes: Long,
    val files: Long,
    val directories: Long,
    val measured: Boolean,
    val complete: Boolean,
    val note: String,
    var selected: Boolean = false
)
