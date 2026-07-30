package net.bdfz.weibian

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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

    @Test
    fun `concurrent installers serialize the full staged promotion transaction`() {
        val first = """{"release":1}"""
        val second = """{"release":2}"""
        val firstReachedStagedValidation = CountDownLatch(1)
        val allowFirstPromotion = CountDownLatch(1)
        val firstValidationCalls = AtomicInteger()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val firstInstall = executor.submit<Boolean> {
                ContentReleaseFiles(root).install(first, sha(first), "v1") { _, _ ->
                    if (firstValidationCalls.incrementAndGet() == 2) {
                        firstReachedStagedValidation.countDown()
                        check(allowFirstPromotion.await(5, TimeUnit.SECONDS))
                    }
                }
            }
            assertTrue(firstReachedStagedValidation.await(5, TimeUnit.SECONDS))
            val secondInstall = executor.submit<Boolean> {
                ContentReleaseFiles(root).install(second, sha(second), "v2") { _, _ -> }
            }
            allowFirstPromotion.countDown()

            assertTrue(firstInstall.get(5, TimeUnit.SECONDS))
            assertTrue(secondInstall.get(5, TimeUnit.SECONDS))
            assertEquals("v2", releases.readActiveOrPrevious()?.contentVersion)
            assertEquals(second, root.resolve("active/content.json").readText())
            assertEquals(first, root.resolve("previous/content.json").readText())
            assertFalse(root.resolve("staged").exists())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `validated reader cannot roll back a newer concurrent install`() {
        val first = """{"release":1}"""
        val rejected = """{"release":2}"""
        val newest = """{"release":3}"""
        assertTrue(releases.install(first, sha(first), "v1") { _, _ -> })
        assertTrue(releases.install(rejected, sha(rejected), "v2") { _, _ -> })
        val readerHasInspectedV2 = CountDownLatch(1)
        val allowReaderRollback = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val reader = executor.submit<ContentReleaseFiles.ValidatedRelease<String>?> {
                ContentReleaseFiles(root).readValidatedActiveOrPrevious(
                    validate = { _, version ->
                        if (version == "v2") {
                            readerHasInspectedV2.countDown()
                            check(allowReaderRollback.await(5, TimeUnit.SECONDS))
                            error("reject-v2")
                        }
                        version
                    },
                )
            }
            assertTrue(readerHasInspectedV2.await(5, TimeUnit.SECONDS))
            val installer = executor.submit<Boolean> {
                ContentReleaseFiles(root).install(
                    newest,
                    sha(newest),
                    "v3",
                ) { _, _ -> }
            }
            allowReaderRollback.countDown()

            assertEquals("v1", reader.get(5, TimeUnit.SECONDS)?.value)
            assertTrue(installer.get(5, TimeUnit.SECONDS))
            assertEquals("v3", releases.readActiveOrPrevious()?.contentVersion)
            assertEquals(newest, root.resolve("active/content.json").readText())
            assertEquals(first, root.resolve("previous/content.json").readText())
        } finally {
            executor.shutdownNow()
        }
    }

    private fun sha(body: String) = ContentStore.sha256(body.toByteArray())
}
