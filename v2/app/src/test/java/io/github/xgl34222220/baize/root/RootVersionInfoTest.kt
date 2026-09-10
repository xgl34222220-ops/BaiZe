package io.github.xgl34222220.baize.root

import io.github.xgl34222220.baize.BuildConfig
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RootVersionInfoTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun addsRunningBuildConfigAndInstalledModuleWithoutChangingExistingFields() {
        val prop = temporaryFolder.newFile("module.prop")
        prop.writeText("name=白泽 v2\nversion=v2.8.1\nversionCode=28001\n")
        val result = RootVersionInfo.putInto(JSONObject().put("engine", "existing").put("root", true), prop)
        assertEquals("existing", result.getString("engine"))
        assertTrue(result.getBoolean("root"))
        assertEquals(BuildConfig.VERSION_NAME, result.getString("rootVersionName"))
        assertEquals(BuildConfig.VERSION_CODE, result.getInt("rootVersionCode"))
        assertEquals("白泽 v2", result.getString("moduleName"))
        assertEquals("v2.8.1", result.getString("moduleVersionName"))
        assertEquals("28001", result.getString("moduleVersionCode"))
    }

    @Test fun missingModuleDoesNotSubstituteAppVersion() {
        val result = RootVersionInfo.putInto(JSONObject(), java.io.File(temporaryFolder.root, "absent"))
        assertEquals(BuildConfig.VERSION_NAME, result.getString("rootVersionName"))
        assertTrue(result.isNull("moduleVersionName"))
        assertTrue(result.isNull("moduleVersionCode"))
        assertTrue(result.isNull("moduleName"))
    }

    @Test fun unreadablePropertiesLeaveVersionsUnknown() {
        val prop = temporaryFolder.newFile("broken.prop")
        prop.writeText("version=\\uZZZZ\n")
        val result = RootVersionInfo.putInto(JSONObject(), prop)
        assertTrue(result.isNull("moduleVersionName"))
        assertTrue(result.isNull("moduleVersionCode"))
    }
}
