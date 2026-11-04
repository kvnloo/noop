package com.noop.ui

import com.noop.data.DataBackup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression for ryanbr/noop#2836: Backup & Sync must offer the same explicit oversize retry as
 * Settings, for both a folder snapshot and the one-off file picker. The UI's source contract is
 * asserted without a device; the staging cap is exercised by BackupOversizeOverrideTest.
 */
class BackupSyncOversizeRestoreTest {

    private fun backupSyncScreenSource(): String {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        val source = listOf(
            File(cwd, "src/main/java/com/noop/ui/BackupSyncScreen.kt"),
            File(cwd, "app/src/main/java/com/noop/ui/BackupSyncScreen.kt"),
            File(cwd, "android/app/src/main/java/com/noop/ui/BackupSyncScreen.kt"),
        ).firstOrNull { it.isFile }
            ?: error("BackupSyncScreen.kt not found")
        return source.readText().replace(Regex("\\s+"), " ")
    }

    @Test
    fun backupSyncRequiresASecondConfirmationBeforeLiftingTheCap() {
        val source = backupSyncScreenSource()
        assertTrue(
            "ordinary restore must keep the 2 GiB guard",
            source.contains("fun runRestore(uri: Uri, allowOversize: Boolean = false)"),
        )
        assertTrue(
            "both folder and file picker must pass through the real guarded importer",
            source.contains("DataBackup.importFrom(context, uri, allowOversize = allowOversize)"),
        )
        assertTrue(
            "the first size refusal must retain the picked URI instead of ending in a failure dialog",
            source.contains("oversizeRestore = uri to r.message"),
        )
        assertTrue(
            "after confirming, an additional size refusal must fail rather than loop",
            source.contains("if (allowOversize) { backupFailure = r.message } else { oversizeRestore = uri to r.message }"),
        )
        assertTrue(
            "Cancel/back must clear the pending override",
            source.contains("onDismissRequest = { oversizeRestore = null }") &&
                source.contains("TextButton(onClick = { oversizeRestore = null })"),
        )
        assertTrue(
            "only the confirmation action may retry the same document with an override",
            source.contains("oversizeRestore = null runRestore(pendingUri, allowOversize = true)"),
        )
        assertTrue(
            "the Restore button must use an existing translated string",
            source.contains("uiString(R.string.l10n_settings_screen_restore_3cbe6d6b)"),
        )
    }

    @Test
    fun theOverrideUsesTheExistingSQLiteCapAndNotAnAlwaysOnBypass() {
        assertEquals(2_147_483_648L, DataBackup.sqliteCap(false))
        assertEquals(Long.MAX_VALUE, DataBackup.sqliteCap(true))
    }
}
