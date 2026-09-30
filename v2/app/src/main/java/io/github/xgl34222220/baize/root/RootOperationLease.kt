package io.github.xgl34222220.baize.root

import android.content.Context
import java.io.File

internal object RootOperationLease {
    fun acquire(context: Context, shared: Boolean): OperationLease? {
        val script = context.assets.open("operation-lock.sh").bufferedReader().use { it.readText() }
        return OperationLease.acquire("/system/bin/sh", script, File(RootPaths.STATE_DIR), shared)
    }
}
