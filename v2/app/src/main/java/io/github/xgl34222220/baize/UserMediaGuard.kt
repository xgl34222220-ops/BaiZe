package io.github.xgl34222220.baize

/**
 * 用户自己拍摄或保存的相机 / 相册 / 录屏媒体。
 *
 * 这些文件永远不进入可“全选后批量删除”的分组：存储分析的分类视图里只能查看，
 * 大文件、重复文件、聊天媒体等视图中不进入默认勾选与“全选”，需要逐项勾选、逐项确认。
 * 旧截图与录屏视图只按截图 / 录屏目录本身识别，因此不受此限制。
 */
internal object UserMediaGuard {
    private val volumePrefix = Regex("^(?:/storage/(?:emulated/[0-9]+|self/primary|[A-Za-z0-9-]+)|/sdcard|/data/media/[0-9]+|/mnt/media_rw/[A-Za-z0-9-]+)/(.+)$")
    private val protectedTops = setOf("dcim", "pictures", "movies")
    private val recorderDirectories = listOf("miui/screenrecorder/", "screenrecorder/", "screenrecords/", "screen recordings/", "录屏/")

    const val READ_ONLY_LABEL = "相机与相册原件 · 仅查看"
    const val INDIVIDUAL_LABEL = "相机与相册原件 · 需逐项勾选"

    /** 卷根之后的相对路径；不在共享存储内时返回 null。 */
    fun relative(path: String): String? = volumePrefix.matchEntire(path)?.groupValues?.get(1)

    /** DCIM、Pictures、Movies 目录（含子目录）以及系统录屏目录下的文件。 */
    fun isUserMedia(path: String): Boolean {
        val relative = relative(path)?.lowercase() ?: return false
        if (!relative.contains('/')) return false
        if (relative.substringBefore('/') in protectedTops) return true
        return recorderDirectories.any { relative.startsWith(it) }
    }
}
