package com.neoproc.neoproccamx.recording

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore

/** Pending rows are excluded from ordinary collection queries, including some owner queries. */
internal object OwnedPendingMediaStoreQuery {
    data class Plan(val modernArguments: Boolean, val selection: String, val arguments: Array<String>)

    fun plan(sdk: Int, ownerPackage: String, selection: String, arguments: Array<String>): Plan {
        require(sdk >= 29) { "Owned pending MediaStore rows require Android 10 or later" }
        require(ownerPackage.isNotBlank() && selection.isNotBlank()) { "Pending query must have an owner and scope" }
        return Plan(
            modernArguments = sdk >= 30,
            selection = "(${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=?) AND ($selection)",
            arguments = arrayOf(ownerPackage, *arguments)
        )
    }

    fun query(
        resolver: ContentResolver,
        collection: Uri,
        projection: Array<String>,
        ownerPackage: String,
        selection: String,
        arguments: Array<String>
    ): Cursor? {
        val plan = plan(Build.VERSION.SDK_INT, ownerPackage, selection, arguments)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            resolver.query(collection, projection, Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, plan.selection)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, plan.arguments)
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            }, null)
        } else {
            resolver.query(includePendingOnAndroid10(collection), projection, plan.selection, plan.arguments, null)
        }
    }

    // The modern Bundle argument was introduced in API 30; this is the documented API-29 route.
    // Its deprecation is isolated to the version which needs it, not used to ignore a failed check.
    @Suppress("DEPRECATION")
    private fun includePendingOnAndroid10(collection: Uri): Uri = MediaStore.setIncludePending(collection)
}
