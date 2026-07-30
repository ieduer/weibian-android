package net.bdfz.weibian.content

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContentStoreProcessAuthorityTest {

    @Test
    fun installPublishesOneSharedBundleAndGenerationAcrossStoreInstances() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val body = context.assets.open("content.json").bufferedReader().use { it.readText() }
        val firstStore = ContentStore(context)
        val secondStore = ContentStore(context)
        val firstVersion = "1111111111111111"
        val secondVersion = "2222222222222222"

        assertTrue(firstStore.install(body, ContentStore.sha256(body.toByteArray()), firstVersion))
        assertEquals(firstVersion, firstStore.bundle().version)
        val firstGeneration = firstStore.generationFlow.value

        assertTrue(secondStore.install(body, ContentStore.sha256(body.toByteArray()), secondVersion))

        assertEquals(firstGeneration + 1, firstStore.generationFlow.value)
        assertEquals(secondVersion, firstStore.bundle().version)
        assertEquals(secondVersion, secondStore.bundle().version)
        assertEquals(secondVersion, firstStore.activeSnapshot().contentVersion)
        assertEquals(secondVersion, secondStore.activeVersion())
    }
}
