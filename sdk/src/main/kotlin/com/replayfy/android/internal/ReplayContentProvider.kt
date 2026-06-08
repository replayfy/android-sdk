package com.replayfy.android.internal

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * Zero-config bootstrap. Android instantiates every ContentProvider
 * declared in the application's manifest BEFORE `Application.onCreate`
 * fires. By registering this no-op provider in the SDK's own manifest
 * (which gets merged into the host app's manifest at build time), we
 * get an [onCreate] callback that runs early enough to register
 * lifecycle observers without the host app having to write any
 * Application.onCreate code.
 *
 * Same pattern AndroidX Startup, Firebase, Crashlytics, and the reference mobile SDK all
 * use. The other ContentProvider methods (query/insert/update/delete/
 * getType) are required by the abstract base but are never called for
 * us — we always return null/0.
 */
class ReplayContentProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        try {
            LegacyCore.autoBootstrap(ctx)
        } catch (t: Throwable) {
            // Never crash the host app from our bootstrap. Logged so
            // customer-success can diagnose silent install failures.
            android.util.Log.e(
                "ReplaySdk",
                "ContentProvider bootstrap failed: ${t.message}",
                t,
            )
        }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
