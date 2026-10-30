package com.dcontrol.client.feature.files.data

import com.dcontrol.client.core.websocket.CommandException
import com.dcontrol.client.core.websocket.protocol.ErrorCodes
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileSystemTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var outside: File
    private lateinit var fs: FileSystem

    @Before
    fun setUp() {
        root = temp.newFolder("storage").canonicalFile
        outside = temp.newFolder("secret").canonicalFile
        File(outside, "keys.txt").writeText("nope")
        File(root, "Android/data/com.app").mkdirs()
        fs = FileSystem(
            roots = { listOf(root) },
            blocked = { listOf(File(root, "Android/data")) },
            mimeOf = { if (it.endsWith(".jpg")) "image/jpeg" else null },
        )
    }

    @Test
    fun `lists directories first then files by name ignoring case`() {
        File(root, "b.jpg").writeText("x")
        File(root, "A.txt").writeText("hello")
        File(root, "zdir").mkdir()

        val result = fs.list(root.path, 0, 48 * 1024) { 10 }

        assertEquals(listOf("Android", "zdir", "A.txt", "b.jpg"), result.entries.map { it.name })
        assertEquals(4, result.total)
        assertNull(result.nextOffset)
        val jpg = result.entries.last()
        assertEquals("image/jpeg", jpg.mime)
        assertEquals(1L, jpg.size)
        assertFalse(jpg.dir)
        assertEquals(0L, result.entries.first().size)
    }

    @Test
    fun `pages by encoded size and continues from nextOffset`() {
        repeat(5) { File(root, "f$it.txt").writeText("x") }

        val first = fs.list(root.path, 0, budgetBytes = 25) { 10 }
        assertEquals(2, first.entries.size)
        assertEquals(2, first.nextOffset)

        val rest = fs.list(root.path, 4, budgetBytes = 1000) { 10 }
        assertEquals(listOf("f3.txt", "f4.txt"), rest.entries.map { it.name })
        assertNull(rest.nextOffset)
    }

    @Test
    fun `an oversized single entry still makes progress`() {
        File(root, "only.txt").writeText("x")
        File(root, "Android").deleteRecursively()

        val page = fs.list(root.path, 0, budgetBytes = 5) { 100 }

        assertEquals(1, page.entries.size)
    }

    @Test
    fun `paths outside the roots are forbidden, including via dot-dot`() {
        assertCode(ErrorCodes.FORBIDDEN) { fs.list(outside.path, 0, 1000) { 1 } }
        assertCode(ErrorCodes.FORBIDDEN) { fs.resolveFile(root.path + File.separator + ".." + File.separator + "secret" + File.separator + "keys.txt") }
    }

    @Test
    fun `blocked paths and invalid paths are rejected`() {
        assertCode(ErrorCodes.FORBIDDEN) { fs.list(File(root, "Android/data").path, 0, 1000) { 1 } }
        assertCode(ErrorCodes.VALIDATION) { fs.list("relative/path", 0, 1000) { 1 } }
        assertCode(ErrorCodes.VALIDATION) { fs.list(root.path + "\u0000", 0, 1000) { 1 } }
        assertCode(ErrorCodes.NOT_FOUND) { fs.list(File(root, "missing").path, 0, 1000) { 1 } }
        assertCode(ErrorCodes.VALIDATION) { fs.list(root.path, -1, 1000) { 1 } }
    }

    @Test
    fun `mkdir and rename validate names and refuse to overwrite`() {
        val created = fs.mkdir(root.path, "Photos")
        assertTrue(created.dir)
        assertTrue(File(root, "Photos").isDirectory)

        assertCode(ErrorCodes.CONFLICT) { fs.mkdir(root.path, "Photos") }
        assertCode(ErrorCodes.VALIDATION) { fs.mkdir(root.path, "..") }
        assertCode(ErrorCodes.VALIDATION) { fs.mkdir(root.path, "a/b") }

        File(root, "Other").mkdir()
        assertCode(ErrorCodes.CONFLICT) { fs.rename(File(root, "Photos").path, "Other") }
        val renamed = fs.rename(File(root, "Photos").path, "Pictures")
        assertEquals("Pictures", renamed.name)
        assertCode(ErrorCodes.FORBIDDEN) { fs.rename(root.path, "x") }
    }

    @Test
    fun `delete removes directories recursively and reports per-path failures`() {
        File(root, "dir/sub").mkdirs()
        File(root, "dir/sub/file.txt").writeText("x")

        val result = fs.delete(listOf(File(root, "dir").path, File(root, "missing").path, root.path, File(outside, "keys.txt").path))

        assertEquals(1, result.deleted)
        assertFalse(File(root, "dir").exists())
        assertEquals(listOf(ErrorCodes.NOT_FOUND, ErrorCodes.FORBIDDEN, ErrorCodes.FORBIDDEN), result.failures.map { it.code })
        assertTrue(File(outside, "keys.txt").exists())
        assertCode(ErrorCodes.VALIDATION) { fs.delete(emptyList()) }
    }

    @Test
    fun `resolveFile rejects directories`() {
        assertCode(ErrorCodes.VALIDATION) { fs.resolveFile(File(root, "Android").path) }
    }

    private fun assertCode(code: String, block: () -> Unit) {
        try {
            block()
        } catch (e: CommandException) {
            assertEquals(code, e.code)
            return
        }
        throw AssertionError("Expected CommandException $code")
    }
}
