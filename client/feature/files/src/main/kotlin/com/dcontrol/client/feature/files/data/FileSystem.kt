package com.dcontrol.client.feature.files.data

import com.dcontrol.client.core.websocket.CommandException
import com.dcontrol.client.core.websocket.protocol.DeleteFailure
import com.dcontrol.client.core.websocket.protocol.ErrorCodes
import com.dcontrol.client.core.websocket.protocol.FileEntry
import com.dcontrol.client.core.websocket.protocol.FilesDeleteResult
import com.dcontrol.client.core.websocket.protocol.FilesListResult
import java.io.File
import java.io.IOException
import java.time.Instant

/**
 * File operations for the web file manager, confined to the storage volume roots. Every path from the
 * controller is untrusted: it is validated, canonicalized (resolving `..` and symlinks) and must stay
 * inside a root and outside the [blocked] paths.
 */
class FileSystem(
    private val roots: () -> List<File>,
    private val blocked: () -> List<File> = { emptyList() },
    private val mimeOf: (String) -> String? = { null },
) {

    /** Lists one page of [path]: directories first, then by name, filled up to [budgetBytes] of encoded entries. */
    fun list(path: String, offset: Int, budgetBytes: Int, sizeOf: (FileEntry) -> Int): FilesListResult {
        if (offset < 0) throw CommandException(ErrorCodes.VALIDATION, "offset must be >= 0")
        val dir = resolveDirectory(path)
        val children = dir.listFiles()?.toList()
            ?: throw CommandException(ErrorCodes.FORBIDDEN, "Cannot read ${dir.path}")
        val sorted = children.sortedWith(compareBy<File> { !it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        val entries = mutableListOf<FileEntry>()
        var used = 0
        var index = offset
        while (index < sorted.size && entries.size < MAX_PAGE_ENTRIES) {
            val entry = entry(sorted[index])
            val size = sizeOf(entry)
            if (entries.isNotEmpty() && used + size > budgetBytes) break
            entries += entry
            used += size
            index++
        }
        return FilesListResult(
            path = dir.path,
            entries = entries,
            total = sorted.size,
            nextOffset = index.takeIf { it < sorted.size },
        )
    }

    fun mkdir(parentPath: String, name: String): FileEntry {
        validateName(name)
        val parent = resolveDirectory(parentPath)
        val target = File(parent, name)
        if (target.exists()) throw CommandException(ErrorCodes.CONFLICT, "$name already exists")
        if (!target.mkdir()) throw CommandException(ErrorCodes.INTERNAL, "Could not create $name")
        return entry(target)
    }

    fun rename(path: String, newName: String): FileEntry {
        validateName(newName)
        val source = resolveExisting(path)
        if (isRoot(source)) throw CommandException(ErrorCodes.FORBIDDEN, "A storage root can't be renamed")
        val target = File(source.parentFile, newName)
        if (target.exists()) throw CommandException(ErrorCodes.CONFLICT, "$newName already exists")
        if (!source.renameTo(target)) throw CommandException(ErrorCodes.INTERNAL, "Could not rename ${source.name}")
        return entry(target)
    }

    fun delete(paths: List<String>): FilesDeleteResult {
        if (paths.isEmpty() || paths.size > MAX_DELETE_PATHS) {
            throw CommandException(ErrorCodes.VALIDATION, "paths must contain 1-$MAX_DELETE_PATHS entries")
        }
        var deleted = 0
        val failures = mutableListOf<DeleteFailure>()
        for (path in paths) {
            try {
                val file = resolveExisting(path)
                if (isRoot(file)) throw CommandException(ErrorCodes.FORBIDDEN, "A storage root can't be deleted")
                if (!file.deleteRecursively()) throw CommandException(ErrorCodes.INTERNAL, "Could not delete ${file.name}")
                deleted++
            } catch (e: CommandException) {
                failures += DeleteFailure(path.take(MAX_PATH), e.code, e.message.take(MAX_MESSAGE))
            }
        }
        return FilesDeleteResult(deleted, failures)
    }

    /** An existing regular file (source of an upload to the controller). */
    fun resolveFile(path: String): File {
        val file = resolveExisting(path)
        if (file.isDirectory) throw CommandException(ErrorCodes.VALIDATION, "${file.path} is a directory")
        if (!file.canRead()) throw CommandException(ErrorCodes.FORBIDDEN, "Cannot read ${file.path}")
        return file
    }

    /** An existing directory (listing target, mkdir parent, download target). */
    fun resolveDirectory(path: String): File {
        val dir = resolveExisting(path)
        if (!dir.isDirectory) throw CommandException(ErrorCodes.VALIDATION, "${dir.path} is not a directory")
        return dir
    }

    fun entry(file: File): FileEntry {
        val dir = file.isDirectory
        return FileEntry(
            name = file.name,
            path = file.path,
            dir = dir,
            size = if (dir) 0 else file.length(),
            modified = Instant.ofEpochMilli(file.lastModified()).toString(),
            mime = if (dir) null else mimeOf(file.name)?.takeIf { it.length <= MAX_MIME },
            hidden = file.name.startsWith("."),
        )
    }

    private fun resolveExisting(path: String): File {
        val file = resolve(path)
        if (!file.exists()) throw CommandException(ErrorCodes.NOT_FOUND, "$path does not exist")
        return file
    }

    /** Validates and canonicalizes [path]; it must lie inside a root and outside the blocked paths. */
    fun resolve(path: String): File {
        if (path.isEmpty() || path.length > MAX_PATH || '\u0000' in path || !File(path).isAbsolute) {
            throw CommandException(ErrorCodes.VALIDATION, "path must be an absolute path of 1-$MAX_PATH chars")
        }
        val file = try {
            File(path).canonicalFile
        } catch (_: IOException) {
            throw CommandException(ErrorCodes.VALIDATION, "Invalid path")
        }
        if (canonicalRoots().none { file.isInside(it) }) {
            throw CommandException(ErrorCodes.FORBIDDEN, "$path is outside the storage roots")
        }
        if (blocked().mapNotNull { it.canonicalOrNull() }.any { file.isInside(it) }) {
            throw CommandException(ErrorCodes.FORBIDDEN, "$path is not accessible to apps on this Android version")
        }
        return file
    }

    private fun isRoot(file: File): Boolean = canonicalRoots().any { it == file }

    private fun canonicalRoots(): List<File> = roots().mapNotNull { it.canonicalOrNull() }

    private fun File.canonicalOrNull(): File? = try {
        canonicalFile
    } catch (_: IOException) {
        null
    }

    private fun File.isInside(root: File): Boolean = this == root || path.startsWith(root.path.trimEnd(File.separatorChar) + File.separator)

    companion object {
        const val MAX_PATH = 4096
        const val MAX_NAME = 255
        const val MAX_PAGE_ENTRIES = 1000
        const val MAX_DELETE_PATHS = 100
        const val MAX_MESSAGE = 200
        private const val MAX_MIME = 127

        fun validateName(name: String) {
            if (name.isEmpty() || name.length > MAX_NAME || '/' in name || File.separatorChar in name || '\u0000' in name || name == "." || name == "..") {
                throw CommandException(ErrorCodes.VALIDATION, "name must be 1-$MAX_NAME chars without '/'")
            }
        }
    }
}
