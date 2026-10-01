package com.github.pndv.typstrenderer.lsp

/**
 * A tinymist version, parsed from a release tag (`v0.15.8`) or from the binary's own
 * `tinymist -V` output (`tinymist 0.15.2`).
 *
 * Ordering follows semantic-version precedence, including the rule that a pre-release
 * sorts *before* the release it leads up to (`0.15.4-rc1` < `0.15.4`). Build metadata
 * (`+sha`) is ignored, as the specification requires.
 */
internal data class TinymistVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: String? = null,
) : Comparable<TinymistVersion> {

    override fun compareTo(other: TinymistVersion): Int {
        major.compareTo(other.major).let { if (it != 0) return it }
        minor.compareTo(other.minor).let { if (it != 0) return it }
        patch.compareTo(other.patch).let { if (it != 0) return it }
        return comparePreRelease(preRelease, other.preRelease)
    }

    override fun toString(): String = "$major.$minor.$patch" + (preRelease?.let { "-$it" } ?: "")

    companion object {

        /**
         * Matches a semantic version, optionally prefixed with `v` and optionally carrying a
         * pre-release suffix and build metadata.
         */
        private val VERSION = Regex("""v?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?""")

        /**
         * Parses a complete version string, such as a release tag. Returns `null` when the
         * text carries anything beyond the version itself — use [parseFirst] for free-form
         * output.
         */
        fun parse(text: String): TinymistVersion? = VERSION.matchEntire(text.trim())?.let(::fromMatch)

        /**
         * Extracts the first version found in free-form text — the shape of `tinymist -V`
         * output, which reads `tinymist 0.15.2`.
         */
        fun parseFirst(text: String): TinymistVersion? = VERSION.find(text)?.let(::fromMatch)

        private fun fromMatch(match: MatchResult): TinymistVersion? {
            val (major, minor, patch, pre) = match.destructured
            return TinymistVersion(
                major = major.toIntOrNull() ?: return null,
                minor = minor.toIntOrNull() ?: return null,
                patch = patch.toIntOrNull() ?: return null,
                preRelease = pre.ifEmpty { null },
            )
        }

        /**
         * Semantic-version pre-release precedence: absent outranks present, and otherwise the
         * dot-separated identifiers are compared one by one, numerically where both sides are
         * numeric and lexically otherwise.
         */
        private fun comparePreRelease(left: String?, right: String?): Int {
            if (left == null && right == null) return 0
            if (left == null) return 1
            if (right == null) return -1

            val leftParts = left.split('.')
            val rightParts = right.split('.')
            for (i in 0 until minOf(leftParts.size, rightParts.size)) {
                val l = leftParts[i]
                val r = rightParts[i]
                val lNum = l.toIntOrNull()
                val rNum = r.toIntOrNull()
                val result = when {
                    lNum != null && rNum != null -> lNum.compareTo(rNum)
                    lNum != null -> -1 // numeric identifiers rank below alphanumeric ones
                    rNum != null -> 1
                    else -> l.compareTo(r)
                }
                if (result != 0) return result
            }
            return leftParts.size.compareTo(rightParts.size)
        }
    }
}
