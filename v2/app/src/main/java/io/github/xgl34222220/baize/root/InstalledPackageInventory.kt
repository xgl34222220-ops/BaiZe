package io.github.xgl34222220.baize.root

import android.content.Context
import android.content.pm.PackageManager

internal class PackageInventoryUnavailable(message: String) : IllegalStateException(message)

/** PackageManager queries are scoped to one Android user; absence is meaningful only in that scope. */
internal data class InstalledPackageInventory(val userId: Int, val packages: Set<String>) {
    fun requireUser(targetUser: Int?) {
        if (targetUser == null || targetUser != userId) throw PackageInventoryUnavailable(
            "无法核对此存储所属用户的应用安装状态，已停止卸载残留处理，未核对的内容继续保留。")
    }

    companion object {
        fun read(context: Context): InstalledPackageInventory {
            try {
                @Suppress("DEPRECATION")
                val entries = context.packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                return fromEntries(entries.map { it.packageName to it.uid })
            } catch (error: PackageInventoryUnavailable) { throw error
            } catch (_: Exception) {
                throw PackageInventoryUnavailable("应用安装清单读取失败，无法确认哪些应用已卸载；已停止继续处理卸载残留。")
            }
        }

        fun fromEntries(entries: List<Pair<String, Int>>): InstalledPackageInventory {
            // Every working Android user has the framework package. A null/empty/filtered or
            // mixed-user result cannot establish that another application is uninstalled.
            val users = entries.map { it.second / 100_000 }.distinct()
            if (entries.isEmpty() || entries.none { it.first == "android" } || users.size != 1 ||
                entries.any { it.second < 0 || !it.first.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*")) }) {
                throw PackageInventoryUnavailable("应用安装清单为空或不完整，无法判断卸载残留；请恢复应用查询权限后重试。")
            }
            return InstalledPackageInventory(users.single(), entries.map { it.first }.toSet())
        }

        /** Ambiguous removable-volume and unresolved aliases do not borrow the owner's inventory. */
        fun storageUser(path: String): Int? = Regex("^/(?:storage/emulated|data/media)/([0-9]+)(?:/.*)?$")
            .matchEntire(path)?.groupValues?.get(1)?.toIntOrNull()
    }
}
