package io.github.xgl34222220.baize

import android.app.Application
import android.content.Context
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class CheckedLegacyPreferencesTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun uniqueName() = "checked-cold-${UUID.randomUUID()}"
    private fun file(name: String) = File(context.dataDir, "shared_prefs/$name.xml")
    private fun write(name: String, xml: String) = file(name).apply { parentFile!!.mkdirs(); writeText(xml) }

    @Test fun coldMalformedXmlIsNotTrustedAsSharedPreferencesEmptyFallback() {
        val name = uniqueName() // Never ask Android for this name before writing the broken file.
        write(name, "<map><set name=\"path_whitelist\"><string>/Keep</string>")
        assertTrue(runCatching { CheckedLegacyPreferences.read(context, name) }.isFailure)
        assertTrue(context.getSharedPreferences(name, Context.MODE_PRIVATE).all.isEmpty())
        assertTrue(runCatching { CheckedLegacyPreferences.read(context, name) }.isFailure)
    }

    @Test fun coldCorruptObservedMarkerCannotTurnIntoFalse() {
        val name = uniqueName()
        write(name, "<map><boolean name=\"observed\" value=\"true\"></map")
        assertTrue(runCatching { CheckedLegacyPreferences.read(context, name) }.isFailure)
        assertFalse(context.getSharedPreferences(name, Context.MODE_PRIVATE).getBoolean("observed", false))
    }

    @Test fun coldBackupIsRejectedBeforeAndroidCanRestoreIt() {
        val name = uniqueName()
        write(name, "<map />")
        val backup = File(file(name).path + ".bak").apply { writeText("<map><set name=\"path_whitelist\"><string>/Keep</string></set></map>") }
        assertTrue(runCatching { CheckedLegacyPreferences.read(context, name) }.isFailure)
        assertTrue(backup.exists())
    }

    @Test fun fullMapParserPreservesUnknownFieldsExplicitEmptyAndWhitespace() {
        val name = uniqueName()
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        assertTrue(prefs.edit().putString("unknown", "  keep & <text>\n ")
            .putStringSet("path_whitelist", emptySet()).putStringSet("package_whitelist", setOf("keep.app"))
            .putBoolean("observed", true).putInt("int", 4).putLong("long", 1234L).putFloat("float", 0.25f).commit())
        assertEquals(prefs.all, CheckedLegacyPreferences.read(context, name))
        assertEquals("  keep & <text>\n ", CheckedLegacyPreferences.read(context, name)["unknown"])
    }

    @Test fun duplicateTrailingDoctypeWrongShapeAndTruncationAreRejected() {
        for (xml in listOf(
            "<map><boolean name=\"observed\" value=\"true\"/><boolean name=\"observed\" value=\"false\"/></map>",
            "<map/><map/>", "<!DOCTYPE map [<!ENTITY x 'test'>]><map><string name=\"unknown\">&x;</string></map>",
            "<map><set name=\"path_whitelist\"><int value=\"2\"/></set></map>",
            "<map><boolean name=\"observed\" value=\"maybe\"/></map>",
            "<map><string name=\"unknown\">unfinished", "<map><future name=\"unknown\"/></map>")) {
            val name = uniqueName(); write(name, xml)
            assertTrue(xml, runCatching { CheckedLegacyPreferences.read(context, name) }.isFailure)
        }
    }

    @Test fun missingAfterVerifiedAndDiskMemoryDivergenceBothFailClosed() {
        val name = uniqueName()
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        prefs.edit().putBoolean("observed", true).commit()
        assertEquals(true, CheckedLegacyPreferences.read(context, name)["observed"])
        write(name, "<map><boolean name=\"observed\" value=\"false\" /></map>")
        assertTrue(runCatching { CheckedLegacyPreferences.read(context, name) }.isFailure)
        assertTrue(file(name).delete())
        assertTrue(runCatching { CheckedLegacyPreferences.read(context, name) }.isFailure)
    }

    @Test fun ordinaryAsynchronousMetadataApplyCanSettleWithoutAuthorizingAnEmptyFallback() {
        val name = uniqueName()
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        prefs.edit().putStringSet("path_whitelist", setOf("/Keep")).commit()
        prefs.edit().putString("plan_metadata", "new plan").apply()
        val result = CheckedLegacyPreferences.read(context, name)
        assertEquals("new plan", result["plan_metadata"])
        assertEquals(setOf("/Keep"), result["path_whitelist"])
    }
}
