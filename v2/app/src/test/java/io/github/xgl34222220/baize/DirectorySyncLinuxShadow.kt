package io.github.xgl34222220.baize

import android.system.ErrnoException
import android.system.OsConstants
import android.system.StructStat
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.IdentityHashMap
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Resetter
import org.robolectric.shadows.ShadowLinux

/**
 * Test only. Robolectric 4.14.1 ShadowLinux.open uses RandomAccessFile, which cannot open a
 * directory, and its fstat(fd) loses the path. Model just those directory descriptors using an
 * actual host directory FileChannel, including force(true). Production Os.open/fstat/fsync stays
 * untouched and is also covered by the disposable Android startup/relaunch harness.
 */
@Implements(className = "libcore.io.Linux", isInAndroidSdk = false, minSdk = 26)
class DirectorySyncLinuxShadow : ShadowLinux() {
    private data class DirectoryHandle(val path: String, val channel: FileChannel)

    @Implementation
    protected override fun open(path: String, flags: Int, mode: Int): FileDescriptor {
        val target = Paths.get(path)
        if (flags and OsConstants.O_NOFOLLOW != 0 && Files.isSymbolicLink(target)) {
            throw ErrnoException("open", OsConstants.ELOOP)
        }
        if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) return super.open(path, flags, mode)
        if (failOpen) throw ErrnoException("open", OsConstants.EIO)
        try {
            return FileDescriptor().also { directories[it] = DirectoryHandle(path, FileChannel.open(target, StandardOpenOption.READ)) }
        } catch (failure: IOException) {
            throw ErrnoException("open", OsConstants.EIO, failure)
        }
    }

    @Implementation
    protected override fun fstat(fd: FileDescriptor): StructStat =
        directories[fd]?.let { stat(it.path) } ?: super.fstat(fd)

    @Implementation
    fun fsync(fd: FileDescriptor) {
        val handle = directories[fd]
        if (handle == null) {
            try { fd.sync() } catch (failure: IOException) { throw ErrnoException("fsync", OsConstants.EIO, failure) }
            return
        }
        directorySyncCalls++
        if (failSyncAt == directorySyncCalls) throw ErrnoException("fsync", OsConstants.EIO)
        try { handle.channel.force(true) }
        catch (failure: IOException) { throw ErrnoException("fsync", OsConstants.EIO, failure) }
    }

    @Implementation
    fun close(fd: FileDescriptor) {
        try {
            val handle = directories.remove(fd)
            if (handle != null) handle.channel.close() else FileInputStream(fd).close()
        } catch (failure: IOException) { throw ErrnoException("close", OsConstants.EIO, failure) }
    }

    companion object {
        private val directories = IdentityHashMap<FileDescriptor, DirectoryHandle>()
        var failOpen = false
        var failSyncAt = -1
        var directorySyncCalls = 0
            private set
        val openDirectoryCount: Int get() = directories.size

        @JvmStatic @Resetter fun reset() {
            directories.values.forEach { it.channel.close() }
            directories.clear()
            failOpen = false
            failSyncAt = -1
            directorySyncCalls = 0
        }
    }
}
