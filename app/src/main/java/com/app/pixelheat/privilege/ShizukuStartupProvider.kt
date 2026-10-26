package com.app.pixelheat.privilege

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import rikka.shizuku.ShizukuProvider

/**
 * Runs before [ShizukuProvider]. That provider's startup calls Sui, which looks up a hidden
 * system service. Android hides that method from release builds and the lookup then throws,
 * so the process dies before the first screen. Debug builds are exempt from the block.
 * Shizuku's own binder does not need this call.
 */
class ShizukuStartupProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        ShizukuProvider.disableAutomaticSuiInitialization()
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
