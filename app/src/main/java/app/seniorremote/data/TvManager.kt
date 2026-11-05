package app.seniorremote.data

import app.seniorremote.diagnostics.Diagnostics
import app.seniorremote.domain.CredentialStore
import app.seniorremote.domain.DeviceRepository
import app.seniorremote.domain.NameProblem
import app.seniorremote.domain.RemoteCommandDispatcher
import app.seniorremote.domain.TvImageStore
import app.seniorremote.domain.TvNames
import app.seniorremote.domain.updateTv

/** Changes to the set of saved TVs. Pairing, selection and connection stay separate concerns. */
class TvManager(
    private val repository: DeviceRepository,
    private val credentials: CredentialStore,
    private val images: TvImageStore,
    private val dispatcher: RemoteCommandDispatcher,
    private val diagnostics: Diagnostics,
) {
    /**
     * Makes [id] the target. Repetition and pending commands for the previous TV are cancelled
     * first; the connection manager then follows the new selection. Selecting the current TV
     * changes nothing, so an open session is not disturbed.
     */
    suspend fun select(id: String) {
        val library = repository.library.value
        val tv = library.find(id) ?: return
        if (library.selectedId == id) return
        dispatcher.cancelAll()
        repository.edit { it.selecting(id) }
        diagnostics.log("selection", "Selected TV \"${tv.displayName}\"")
    }

    /** Applies a display name and subtitle. Blank name restores the original. Pairing is untouched. */
    suspend fun rename(id: String, name: String, subtitle: String): NameProblem? {
        val library = repository.library.value
        val tv = library.find(id) ?: return null
        val shown = TvNames.clean(name) ?: tv.originalName ?: TvNames.fallback(library.tvs.filter { it.id != id })
        val cleanSubtitle = TvNames.clean(subtitle)
        TvNames.problem(shown, cleanSubtitle, library.tvs, id)?.let { return it }
        repository.updateTv(id) {
            it.copy(customName = shown.takeUnless { value -> value == it.originalName }, subtitle = cleanSubtitle)
        }
        return null
    }

    suspend fun setImage(id: String, fileName: String?) {
        val previous = repository.library.value.find(id)?.imageFile
        repository.updateTv(id) { it.copy(imageFile = fileName) }
        if (previous != null && previous != fileName) images.delete(previous)
    }

    /** Removes one TV with its credentials and picture. Other TVs are not touched. */
    suspend fun remove(id: String) {
        val library = repository.library.value
        val tv = library.find(id) ?: return
        if (library.selectedId == id) dispatcher.cancelAll()
        repository.edit { it.removing(id) }
        credentials.delete(tv.credentialRef)
        tv.imageFile?.let { images.delete(it) }
        // With nothing left, also drop the encryption key and any leftovers.
        if (repository.library.value.tvs.isEmpty()) credentials.deleteAll()
        diagnostics.log("admin", "Removed TV \"${tv.displayName}\" with its pairing and test results")
    }
}
