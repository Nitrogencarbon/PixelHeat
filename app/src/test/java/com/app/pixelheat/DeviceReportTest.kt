package com.app.pixelheat

import com.app.pixelheat.model.DeviceReport
import com.app.pixelheat.model.isXposedModule
import com.app.pixelheat.model.rootModuleCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceReportTest {

    @Test
    fun rootModulesAreUniqueAndAMissingInstallIsZero() {
        val listed = """
            ADB:/data/adb
            BASE:/data/adb/modules
            MOD:zygisk_lsposed
            MOD:vector
            BASE:/data/adb/ksu/modules
            MOD:vector
        """.trimIndent()
        assertEquals(2, rootModuleCount(listed))
        assertEquals(0, rootModuleCount("ADB:ls: /data/adb: No such file or directory"))
        assertEquals(0, rootModuleCount("ADB:/data/adb\nBASE:/data/adb/modules"))
    }

    @Test
    fun anUnreadableModuleDirectoryIsUnknown() {
        assertNull(rootModuleCount("ADB:ls: /data/adb: Permission denied"))
        assertNull(rootModuleCount("ADB:/data/adb\nDENY:/data/adb/modules"))
        assertNull(rootModuleCount(""))
        assertEquals(1, rootModuleCount("ADB:/data/adb\nBASE:/data/adb/modules\nDENY:/data/adb/ksu/modules\nMOD:playintegrity"))
    }

    @Test
    fun xposedMarkersCoverLegacyAndLibxposed() {
        assertTrue(isXposedModule(setOf("xposedminversion")) { false })
        assertTrue(isXposedModule(setOf("xposedmodule")) { error("zip is unused") })
        assertTrue(isXposedModule(emptySet()) { it == "META-INF/xposed/java_init.list" })
        assertTrue(isXposedModule(emptySet()) { it == "META-INF/xposed/native_init.list" })
        assertTrue(isXposedModule(emptySet()) { it == "assets/xposed_init" })
        assertFalse(isXposedModule(setOf("android.app.lib_name")) { false })
    }

    @Test
    fun summaryNamesEachCount() {
        val line = DeviceReport(142, 8, 3, 5).summaryLine()
        assertEquals("142 apps · 8 root modules · 3 Xposed · 5 always on", line)
        assertEquals("142 apps · — root modules · 3 Xposed · 5 always on", DeviceReport(142, null, 3, 5).summaryLine())
    }
}
