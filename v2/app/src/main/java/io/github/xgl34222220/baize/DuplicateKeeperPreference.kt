package io.github.xgl34222220.baize

internal enum class DuplicateKeeperPreference(val label: String) {
    NEWEST("优先最新副本"), OLDEST("优先最早副本"), CAMERA("优先相机目录"), DIRECTORY("优先指定目录")
}
internal fun preferredDuplicateKeeper(records: List<StorageFileRecord>, preference: DuplicateKeeperPreference,
    directory: String = ""): StorageFileRecord? {
    val eligible = records.filter { it.verifiedBytes > 0 }
    val path = directory.trimEnd('/')
    return eligible.sortedWith(when (preference) {
        DuplicateKeeperPreference.NEWEST -> compareByDescending<StorageFileRecord> { it.modifiedSeconds }
        DuplicateKeeperPreference.OLDEST -> compareBy<StorageFileRecord> { it.modifiedSeconds }
        DuplicateKeeperPreference.CAMERA -> compareByDescending<StorageFileRecord> { it.path.contains("/DCIM/Camera/", true) }
            .thenByDescending { it.modifiedSeconds }
        DuplicateKeeperPreference.DIRECTORY -> compareByDescending<StorageFileRecord> { path.isNotBlank() && it.path.startsWith("$path/") }
            .thenByDescending { it.modifiedSeconds }
    }.thenBy { it.path }).firstOrNull()
}
