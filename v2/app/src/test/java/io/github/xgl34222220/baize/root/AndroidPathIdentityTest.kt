package io.github.xgl34222220.baize.root

import org.junit.Assert.*
import org.junit.Test

class AndroidPathIdentityTest {
    @Test fun fuseAndBackingPathsShareAnIdentityOnlyWithinTheSameUser() {
        val identity = AndroidPathIdentity("/storage/emulated/10")
        assertEquals(identity.of("/storage/emulated/10/Android/data/com.example/cache"),
            identity.of("/data/media/10/Android/data/com.example/cache"))
        assertEquals(identity.of("/data/media/10/Download"), identity.of("/sdcard/Download"))
        assertEquals(identity.of("/data/media/10/Download"), identity.of("/storage/self/primary/Download"))
        assertNotEquals(identity.of("/data/media/0/Download"), identity.of("/sdcard/Download"))
        assertNotEquals(identity.of("/data/media/1/cache"), identity.of("/storage/emulated/10/cache"))
        assertEquals("/data/media/10_backup/cache", identity.of("/data/media/10_backup/cache"))
    }

    @Test fun ownerCredentialEncryptedAliasNeverMergesOtherUsersOrDeviceEncryptedData() {
        val identity = AndroidPathIdentity(null)
        assertEquals(identity.of("/data/data/com.example/files"), identity.of("/data/user/0/com.example/files"))
        assertNotEquals(identity.of("/data/data/com.example/files"), identity.of("/data/user/10/com.example/files"))
        assertNotEquals(identity.of("/data/data/com.example/files"), identity.of("/data/user_de/0/com.example/files"))
        assertEquals("/data/database/com.example", identity.of("/data/database/com.example"))
    }

    @Test fun unresolvedUserRelativeAliasesNeverAssumeTheOwnerUser() {
        assertEquals("/sdcard/Download", AndroidPathIdentity(null).of("/sdcard/Download"))
        assertEquals("/sdcard/Download", AndroidPathIdentity("/storage/emulated/10/Android/data/app").of("/sdcard/Download"))
        assertEquals("/storage/emulated/10/Download", AndroidPathIdentity("/data/media/10/").of("/sdcard/Download"))
    }
}
