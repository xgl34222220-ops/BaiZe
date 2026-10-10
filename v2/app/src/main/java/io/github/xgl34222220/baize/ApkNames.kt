package io.github.xgl34222220.baize

/**
 * 安装包文件名识别。QQ / TIM / 微信收到 APK 后常把它改名为 `xxx.apk.1`（或 `.apk.2` …），
 * 以防用户误点安装；只看最后一个扩展名会把这些安装包全部漏掉。
 *
 * 这里只接受“安装包扩展名 + 可选的 1~3 位数字副本后缀”，不接受 `.apk.txt`、`.apk.tmp` 等。
 */
internal object ApkNames {
    val EXTENSIONS = setOf("apk", "apks", "xapk", "apkm", "aab")
    /** `.apk.1` 这类副本按它的基础扩展名归类。 */
    const val COPY_BASE_EXTENSION = "apk"
    private val NAME = Regex("""(?i)^.+\.(apk|apks|xapk|apkm|aab)(\.[0-9]{1,3})?$""")
    private val PLAIN_APK = Regex("""(?i)^.+\.apk(\.[0-9]{1,3})?$""")

    /** 文件名（或路径最后一段）是否为安装包，含聊天软件改名后的 `.apk.1`。 */
    fun isApk(nameOrPath: String): Boolean = NAME.matches(nameOrPath.substringAfterLast('/'))

    /** 单个 .apk（含 `.apk.1`），可做 APK 预览解析；分包格式不在此列。 */
    fun isPlainApk(nameOrPath: String): Boolean = PLAIN_APK.matches(nameOrPath.substringAfterLast('/'))

    /** 聊天软件改名的副本后缀（`.apk.1` 之类）。 */
    fun hasCopySuffix(nameOrPath: String): Boolean {
        val name = nameOrPath.substringAfterLast('/')
        return isApk(name) && name.substringAfterLast('.', "").all { it.isDigit() }
    }

    /** MediaStore 的 DISPLAY_NAME LIKE 条件：标准扩展名与 `.apk.N` 副本后缀。 */
    val MEDIA_STORE_PATTERNS: List<String> = EXTENSIONS.map { "%.$it" } + listOf("%.apk.1", "%.apk.2", "%.apk.3", "%.apk.4", "%.apk.5")
}
