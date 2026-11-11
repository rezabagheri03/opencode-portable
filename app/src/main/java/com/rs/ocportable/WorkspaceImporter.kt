package com.rs.ocportable

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.IOException

/**
 * Copies a SAF document tree into the app-private workspace.
 *
 * SAF is a document API, not a POSIX tree, so it cannot host the workspace directly: opencode's
 * git snapshots need hardlinks and stable mtimes, which `/sdcard` does not provide. Import is
 * therefore a copy-in door, not a mount.
 */
object WorkspaceImporter {

    fun copyTree(context: Context, treeUri: Uri, targetRoot: File): Int {
        targetRoot.mkdirs()
        val resolver = context.contentResolver
        val rootID = DocumentsContract.getTreeDocumentId(treeUri)
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootID)
        val name = displayName(resolver, rootUri) ?: "imported"
        val target = unique(File(targetRoot, safeName(name)))
        target.mkdirs()
        val counter = IntArray(1)
        copyChildren(resolver, treeUri, rootID, target, counter)
        return counter[0]
    }

    private fun copyChildren(
        resolver: ContentResolver,
        treeUri: Uri,
        parentID: String,
        target: File,
        counter: IntArray,
    ) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentID)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val childID = cursor.getString(0)
                val childName = cursor.getString(1) ?: "file"
                val mime = cursor.getString(2)
                val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childID)
                val destination = File(target, safeName(childName))
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    destination.mkdirs()
                    copyChildren(resolver, treeUri, childID, destination, counter)
                } else {
                    destination.parentFile?.mkdirs()
                    resolver.openInputStream(childUri)?.use { input ->
                        destination.outputStream().use { output -> input.copyTo(output) }
                    } ?: throw IOException("cannot open $childName")
                    counter[0]++
                }
            }
        }
    }

    private fun displayName(resolver: ContentResolver, uri: Uri): String? {
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        resolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        return null
    }

    private fun safeName(name: String): String {
        val cleaned = name.replace(Regex("[/\\\\:*?\"<>|]"), "_").trim()
        return cleaned.ifBlank { "imported" }.take(120)
    }

    private fun unique(file: File): File {
        if (!file.exists()) return file
        val base = file.nameWithoutExtension
        val extension = file.extension.takeIf { it.isNotBlank() }?.let { ".$it" } ?: ""
        var index = 1
        while (true) {
            val candidate = File(file.parentFile, "$base ($index)$extension")
            if (!candidate.exists()) return candidate
            index++
        }
    }
}
