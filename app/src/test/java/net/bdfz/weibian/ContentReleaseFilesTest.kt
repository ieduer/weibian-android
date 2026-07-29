package net.bdfz.weibian

import java.nio.file.Files
import net.bdfz.weibian.content.ContentReleaseFiles
import net.bdfz.weibian.content.ContentStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ContentReleaseFilesTest {
    private lateinit var root: java.io.File
    private lateinit var releases: ContentReleaseFiles

    @Before
    fun setUp() {
        root = Files.createTempDirectory("weibian-content-test").toFile()
        releases = ContentReleaseFiles(root)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `staged release becomes active only after validation`() {
        val body = """{"release":1}"""
        val installed = releases.install(body, sha(body), "v1") { candidate, version ->
            require(candidate.contains("\"release\":1"))
            require(version == "v1")
        }

        assertTrue(installed)
        assertEquals("v1", releases.readActiveOrPrevious()?.contentVersion)
        assertEquals(body, releases.readActiveOrPrevious()?.body)
    }

    @Test
    fun `bad digest never replaces active release`() {
        val first = """{"release":1}"""
        assertTrue(releases.install(first, sha(first), "v1") { _, _ -> })

        val second = """{"release":2}"""
        assertFalse(releases.install(second, "0".repeat(64), "v2") { _, _ -> })
        assertEquals("v1", releases.readActiveOrPrevious()?.contentVersion)
    }

    @Test
    fun `previous release is retained and restorable`() {
        val first = """{"release":1}"""
        val second = """{"release":2}"""
        assertTrue(releases.install(first, sha(first), "v1") { _, _ -> })
        assertTrue(releases.install(second, sha(second), "v2") { _, _ -> })
        assertEquals("v2", releases.readActiveOrPrevious()?.contentVersion)

        assertTrue(releases.restorePrevious())
        assertEquals("v1", releases.readActiveOrPrevious()?.contentVersion)
    }

    @Test
    fun `corrupt active release automatically restores previous`() {
        val first = """{"release":1}"""
        val second = """{"release":2}"""
        assertTrue(releases.install(first, sha(first), "v1") { _, _ -> })
        assertTrue(releases.install(second, sha(second), "v2") { _, _ -> })
        root.resolve("active/content.json").writeText("""{"corrupt":true}""")

        assertEquals("v1", releases.readActiveOrPrevious()?.contentVersion)
        assertEquals(first, root.resolve("active/content.json").readText())
    }

    @Test
    fun `interrupted promotion restores previous when active is missing`() {
        val first = """{"release":1}"""
        assertTrue(releases.install(first, sha(first), "v1") { _, _ -> })
        assertTrue(root.resolve("active").renameTo(root.resolve("previous")))

        assertEquals("v1", releases.readActiveOrPrevious()?.contentVersion)
        assertTrue(root.resolve("active/content.json").isFile)
        assertFalse(root.resolve("previous").exists())
    }

    private fun sha(body: String) = ContentStore.sha256(body.toByteArray())
}
