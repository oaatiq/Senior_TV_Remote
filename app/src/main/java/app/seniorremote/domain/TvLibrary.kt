package app.seniorremote.domain

import kotlinx.serialization.Serializable

/**
 * All paired TVs and which one is selected. Selection is only a choice of target: a selected TV
 * may be unavailable, and nothing here ever changes the selection because of reachability.
 */
@Serializable
data class TvLibrary(
    val tvs: List<SavedTv> = emptyList(),
    val selectedId: String? = null,
) {
    val selected: SavedTv? get() = tvs.firstOrNull { it.id == selectedId }

    fun find(id: String): SavedTv? = tvs.firstOrNull { it.id == id }

    fun adding(tv: SavedTv, select: Boolean): TvLibrary =
        copy(tvs = tvs + tv, selectedId = if (select) tv.id else selectedId)

    fun updating(id: String, transform: (SavedTv) -> SavedTv): TvLibrary =
        copy(tvs = tvs.map { if (it.id == id) transform(it) else it })

    fun selecting(id: String): TvLibrary = if (find(id) == null) this else copy(selectedId = id)

    /**
     * Removes one TV and leaves the others untouched. If the selected TV was removed, the sole
     * remaining TV is selected; with several left nothing is, so the chooser asks. A TV is never
     * picked on the senior's behalf.
     */
    fun removing(id: String): TvLibrary {
        val remaining = tvs.filterNot { it.id == id }
        val nextSelected = when {
            selectedId != id -> selectedId
            remaining.size == 1 -> remaining.single().id
            else -> null
        }
        return TvLibrary(remaining, nextSelected)
    }
}

enum class NameProblem {
    /** Another TV has this name and nothing tells the two apart. */
    NEEDS_SUBTITLE,

    /** Another TV has both this name and this subtitle. */
    SAME_AS_ANOTHER_TV,
}

/** Name rules. Names are free-form: no rooms, labels or formats are imposed. */
object TvNames {
    /** Only a safety limit: long enough for any real name, short enough to keep screens usable. */
    const val MAX_LENGTH = 100
    private val whitespace = Regex("\\s+")

    /** Tidies typed or discovered text; null if nothing usable remains. */
    fun clean(raw: String?): String? =
        raw?.replace(whitespace, " ")?.trim()?.take(MAX_LENGTH)?.trim()?.takeIf { it.isNotEmpty() }

    /** Editable name for a TV that offered no usable name of its own: TV 1, TV 2, ... */
    fun fallback(tvs: List<SavedTv>): String = firstFree("TV") { candidate ->
        tvs.any { it.displayName.equals(candidate, ignoreCase = true) }
    }

    fun isDuplicate(name: String, tvs: List<SavedTv>, exceptId: String? = null): Boolean =
        tvs.any { it.id != exceptId && it.displayName.equals(name, ignoreCase = true) }

    /** Starting subtitle for a TV whose name is already taken, so the two are never identical. */
    fun defaultSubtitle(name: String, tvs: List<SavedTv>, exceptId: String? = null): String {
        val sameName = tvs.filter { it.id != exceptId && it.displayName.equals(name, ignoreCase = true) }
        return firstFree("TV", start = sameName.size + 1) { candidate ->
            sameName.any { it.subtitle.equals(candidate, ignoreCase = true) }
        }
    }

    fun problem(name: String, subtitle: String?, tvs: List<SavedTv>, exceptId: String? = null): NameProblem? {
        val sameName = tvs.filter { it.id != exceptId && it.displayName.equals(name, ignoreCase = true) }
        return when {
            sameName.isEmpty() -> null
            subtitle == null -> NameProblem.NEEDS_SUBTITLE
            sameName.any { it.subtitle.equals(subtitle, ignoreCase = true) } -> NameProblem.SAME_AS_ANOTHER_TV
            else -> null
        }
    }

    private fun firstFree(prefix: String, start: Int = 1, taken: (String) -> Boolean): String {
        var n = start
        while (taken("$prefix $n")) n++
        return "$prefix $n"
    }
}
