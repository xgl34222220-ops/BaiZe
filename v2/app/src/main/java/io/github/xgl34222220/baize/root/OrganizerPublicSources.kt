package io.github.xgl34222220.baize.root

/**
 * 文件归类在共享存储根下认可的“公共下载来源”（相对卷根的路径前缀，统一小写）。
 *
 * 索引复用、主动发现与执行前复核三处共用同一份清单；此前执行前复核只认名为
 * download / recv 之类的目录，导致 UCDownloads、BaiduNetdisk、Telegram 公共目录里
 * 进了计划的文件在执行时被判为“不再属于允许的归类来源”。
 *
 * 3.0.0 参考 Aurora 补齐 123云盘、阿里云盘、微云、QQ 浏览器、UC 极速版下载目录。
 * 归类只移动、不删除；DCIM / Pictures / Movies / 录屏目录始终由 UserMediaGuard 排除。
 */
internal object OrganizerPublicSources {
    /** 需要整棵树纳入的公共目录（相对卷根，原始大小写，用于主动发现）。 */
    val ROOTS: List<String> = listOf(
        "Download", "Downloads", "Documents", "Bluetooth",
        "UCDownloads", "UCTurbo/Download", "Quark/Download", "BaiduNetdisk",
        "123云盘", "AliYunPan", "微云保存的文件", "QQBrowser",
        "Tencent/QQfile_recv", "Tencent/TIMfile_recv",
        "Telegram/Telegram Documents", "Telegram/Telegram Images", "Telegram/Telegram Video",
        "Telegram/Telegram Audio", "Telegram/Telegram Files",
        "Nagram/Nagram Documents", "Nagram/Nagram Images", "Nagram/Nagram Video", "Nagram/Nagram Audio",
        "NagramX/NagramX Documents", "NagramX/NagramX Images", "NagramX/NagramX Video", "NagramX/NagramX Audio"
    )

    private val prefixes: List<String> = ROOTS.map { it.lowercase() + "/" }

    /** [relative] 为卷根之后的相对路径（不以 / 开头）。 */
    fun allows(relative: String): Boolean {
        val normalized = relative.replace('\\', '/').trimStart('/').lowercase()
        if (normalized.split('/').any { it == ".." }) return false
        return prefixes.any { normalized.startsWith(it) }
    }

    /** 应用私有外部目录中额外认可的用户下载子目录（相对 Android/data/<包名>/）。 */
    fun allowsAppPath(packageName: String, tail: String): Boolean {
        val t = tail.replace('\\', '/').trimStart('/')
        if (t.split('/').any { it == ".." }) return false
        val lower = t.lowercase()
        return when {
            packageName == "com.tencent.android.qqdownloader" && lower.startsWith("files/tassistant/apk/") -> true
            packageName.startsWith("com.tencent.") && lower.startsWith("tencent/") ->
                lower.removePrefix("tencent/").substringBefore('/', "").let { it.endsWith("file_recv") && lower.count { c -> c == '/' } >= 2 }
            lower.startsWith("files/telegram/telegram files/") -> true
            else -> false
        }
    }
}
