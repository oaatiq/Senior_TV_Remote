package app.seniorremote.data

import app.seniorremote.domain.Capability
import app.seniorremote.domain.CapabilityStatus
import app.seniorremote.domain.NameProblem
import app.seniorremote.domain.TvLibrary
import app.seniorremote.domain.TvNames
import app.seniorremote.testing.RecordingDispatcher
import app.seniorremote.testing.RecordingImages
import app.seniorremote.testing.TV_A
import app.seniorremote.testing.TV_B
import app.seniorremote.testing.TV_C
import app.seniorremote.testing.otherTv
import app.seniorremote.testing.testCredentials
import app.seniorremote.testing.testTv
import app.seniorremote.diagnostics.NoDiagnostics
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvLibraryTest {
    private val a = testTv()
    private val b = otherTv(TV_B, "10.0.0.6")
    private val c = otherTv(TV_C, "10.0.0.7")

    @Test
    fun `removing another TV keeps the selection`() {
        val library = TvLibrary(listOf(a, b, c), TV_A).removing(TV_B)
        assertEquals(TV_A, library.selectedId)
        assertEquals(listOf(a, c), library.tvs)
    }

    @Test
    fun `removing the selected TV selects the sole remaining TV`() {
        assertEquals(TV_B, TvLibrary(listOf(a, b), TV_A).removing(TV_A).selectedId)
    }

    @Test
    fun `removing the selected TV with several left selects nothing, so the chooser asks`() {
        val library = TvLibrary(listOf(a, b, c), TV_A).removing(TV_A)
        assertNull(library.selectedId)
        assertNull(library.selected)
        assertEquals(2, library.tvs.size)
    }

    @Test
    fun `selecting an unknown TV changes nothing`() {
        assertEquals(TV_A, TvLibrary(listOf(a), TV_A).selecting("nope").selectedId)
    }

    @Test
    fun `capabilities are per TV`() {
        val library = TvLibrary(listOf(a, b), TV_A)
            .updating(TV_B) { it.copy(capabilities = it.capabilities + (Capability.MUTE to CapabilityStatus.UNSUPPORTED)) }
        assertEquals(CapabilityStatus.NOT_VERIFIED, library.find(TV_A)?.status(Capability.MUTE))
        assertEquals(CapabilityStatus.UNSUPPORTED, library.find(TV_B)?.status(Capability.MUTE))
    }

    @Test
    fun `selection and all fields survive storage`() {
        val library = TvLibrary(
            listOf(a.copy(customName = "Grandma's TV", subtitle = "Upstairs", identityKey = "abc"), b),
            TV_B,
        )
        assertEquals(library, TvLibraryCodec.decode(TvLibraryCodec.encode(library)))
    }

    @Test
    fun `a saved TV from the single-TV version is upgraded with its pairing intact`() {
        val legacy = """{"displayName":"Living Room","deviceId":"AA:BB","serviceName":"Living Room",""" +
            """"lastKnownHost":"192.168.1.40","port":6466,"protocol":"ANDROID_TV_REMOTE_V2",""" +
            """"credentialRef":"tv_123","capabilities":{"MUTE":"UNSUPPORTED"},"lastConnectedAtEpochMs":5}"""
        val library = requireNotNull(TvLibraryCodec.decodeLegacy(legacy))
        val tv = requireNotNull(library.selected)
        assertEquals("tv_123", tv.id)
        assertEquals("tv_123", tv.credentialRef)
        assertEquals("Living Room", tv.displayName)
        assertEquals("Living Room", tv.originalName)
        assertEquals(CapabilityStatus.UNSUPPORTED, tv.status(Capability.MUTE))
    }

    @Test
    fun `unreadable storage is not mistaken for data`() {
        assertNull(TvLibraryCodec.decode("not json"))
        assertNull(TvLibraryCodec.decodeLegacy("{}"))
    }
}

class TvNamesTest {
    private val a = testTv()

    @Test
    fun `names are free form but tidied`() {
        assertEquals("Mum's big TV", TvNames.clean("  Mum's   big\tTV "))
        assertEquals("客厅的电视", TvNames.clean("客厅的电视"))
        assertNull(TvNames.clean("   "))
        assertNull(TvNames.clean(null))
        assertEquals(TvNames.MAX_LENGTH, TvNames.clean("x".repeat(500))?.length)
    }

    @Test
    fun `display name prefers the custom name and keeps the original`() {
        val named = a.copy(customName = "Grandma's TV")
        assertEquals("Grandma's TV", named.displayName)
        assertEquals("Living room TV", named.originalName)
    }

    @Test
    fun `fallback names are numbered and never collide`() {
        assertEquals("TV 1", TvNames.fallback(emptyList()))
        assertEquals("TV 2", TvNames.fallback(listOf(a.copy(customName = "TV 1"))))
        assertEquals("TV 1", TvNames.fallback(listOf(a.copy(customName = "TV 2"))))
    }

    @Test
    fun `duplicate names need a subtitle that actually differs`() {
        val other = otherTv(TV_B, "10.0.0.6", name = "Android TV")
        val tvs = listOf(a, other)
        assertNull(TvNames.problem("Unique", null, tvs))
        assertEquals(NameProblem.NEEDS_SUBTITLE, TvNames.problem("android tv", null, tvs))
        assertNull(TvNames.problem("Android TV", "Bedroom", tvs))
        val withSubtitle = listOf(a, other.copy(subtitle = "Bedroom"))
        assertEquals(NameProblem.SAME_AS_ANOTHER_TV, TvNames.problem("Android TV", "bedroom", withSubtitle))
        assertNull("a TV does not clash with itself", TvNames.problem("Android TV", null, tvs, exceptId = TV_B))
    }

    @Test
    fun `default subtitle tells a duplicate apart`() {
        val tvs = listOf(a.copy(subtitle = "TV 2"), otherTv(TV_B, "10.0.0.6", name = "Living room TV"))
        val subtitle = TvNames.defaultSubtitle("Living room TV", tvs)
        assertEquals("TV 3", subtitle)
        assertNull(TvNames.problem("Living room TV", subtitle, tvs))
    }
}

class TvManagerTest {
    private val images = RecordingImages()
    private val credentials = InMemoryCredentialStore()
    private val b = otherTv(TV_B, "10.0.0.6").copy(imageFile = "b.jpg")
    private val repository = InMemoryDeviceRepository(TvLibrary(listOf(testTv(), b, otherTv(TV_C, "10.0.0.7")), TV_A))

    private suspend fun storeAll() = repository.library.value.tvs.forEach { credentials.save(it.credentialRef, testCredentials) }

    private fun manager(dispatcher: RecordingDispatcher = RecordingDispatcher()) =
        TvManager(repository, credentials, images, dispatcher, NoDiagnostics)

    @Test
    fun `selecting cancels pending commands before the selection changes`() = runTest {
        var selectionWhenCancelled: String? = null
        val dispatcher = RecordingDispatcher { selectionWhenCancelled = repository.library.value.selectedId }
        manager(dispatcher).select(TV_B)
        assertEquals(TV_A, selectionWhenCancelled)
        assertEquals(TV_B, repository.library.value.selectedId)
    }

    @Test
    fun `selecting the current TV disturbs nothing`() = runTest {
        val dispatcher = RecordingDispatcher()
        manager(dispatcher).select(TV_A)
        assertEquals(0, dispatcher.cancelAllCalls)
    }

    @Test
    fun `renaming keeps pairing and identity`() = runTest {
        val before = repository.library.value.find(TV_A)!!
        assertNull(manager().rename(TV_A, "Grandma's TV", "Upstairs"))
        val after = repository.library.value.find(TV_A)!!
        assertEquals("Grandma's TV", after.displayName)
        assertEquals("Upstairs", after.subtitle)
        assertEquals(before.credentialRef, after.credentialRef)
        assertEquals(before.id, after.id)
        assertEquals(before.originalName, after.originalName)
    }

    @Test
    fun `blank name restores the original name`() = runTest {
        manager().rename(TV_A, "Grandma's TV", "")
        manager().rename(TV_A, "   ", "")
        val tv = repository.library.value.find(TV_A)!!
        assertNull(tv.customName)
        assertEquals("Living room TV", tv.displayName)
    }

    @Test
    fun `blank name on a TV with no original name gets a fallback`() = runTest {
        repository.edit { it.updating(TV_A) { tv -> tv.copy(originalName = null, customName = null) } }
        manager().rename(TV_A, "", "")
        assertEquals("TV 1", repository.library.value.find(TV_A)?.displayName)
    }

    @Test
    fun `a duplicate name without a subtitle is refused and nothing changes`() = runTest {
        assertEquals(NameProblem.NEEDS_SUBTITLE, manager().rename(TV_B, "Living room TV", ""))
        assertNull(repository.library.value.find(TV_B)?.customName)
        assertNull(manager().rename(TV_B, "Living room TV", "Bedroom"))
        assertEquals("Living room TV", repository.library.value.find(TV_B)?.displayName)
    }

    @Test
    fun `removing one TV leaves the others and their pairing untouched`() = runTest {
        storeAll()
        manager().remove(TV_B)
        val library = repository.library.value
        assertNull(library.find(TV_B))
        assertEquals(TV_A, library.selectedId)
        assertNull(credentials.load(b.credentialRef))
        assertNotNull(credentials.load("ref1"))
        assertNotNull(credentials.load("ref-$TV_C"))
        assertEquals(listOf("b.jpg"), images.deleted)
    }

    @Test
    fun `removing the selected TV cancels its commands`() = runTest {
        val dispatcher = RecordingDispatcher()
        storeAll()
        manager(dispatcher).remove(TV_A)
        assertEquals(1, dispatcher.cancelAllCalls)
        assertFalse(repository.library.value.tvs.any { it.id == TV_A })
    }

    @Test
    fun `removing the last TV removes everything`() = runTest {
        storeAll()
        val manager = manager()
        listOf(TV_A, TV_B, TV_C).forEach { manager.remove(it) }
        assertTrue(repository.library.value.tvs.isEmpty())
        assertNull(repository.library.value.selectedId)
        assertNull(credentials.load("ref1"))
    }

    @Test
    fun `replacing a picture deletes the old file only`() = runTest {
        val manager = manager()
        manager.setImage(TV_B, "b2.jpg")
        assertEquals("b2.jpg", repository.library.value.find(TV_B)?.imageFile)
        assertEquals(listOf("b.jpg"), images.deleted)
    }
}
