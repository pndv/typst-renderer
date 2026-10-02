package com.github.pndv.typstrenderer.lsp

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.intellij.openapi.diagnostic.logger
import org.jetbrains.annotations.VisibleForTesting

private val LOG = logger<PlatformConfig>()

data class PlatformKey(val os: String, val arch: String) {
    override fun toString(): String = "$os/$arch"

    companion object {
        /**
         * Normalises the host's `os.name` / `os.arch` system properties into the
         * canonical values used in `platforms.json` (`darwin` / `linux` / `windows`,`arm64` / `x64`).
         * Returns null if either property is missing or unrecognised.
         */
        fun currentHost(
            osName: String? = System.getProperty("os.name"),
            osArch: String? = System.getProperty("os.arch"),
        ): PlatformKey? {
            val os = normalizeOs(osName) ?: return null
            val arch = normalizeArch(osArch) ?: return null
            return PlatformKey(os, arch)
        }

        internal fun normalizeOs(osName: String?): String? {
            val n = osName?.lowercase() ?: return null
            return when {
                "mac" in n || "darwin" in n -> "darwin"
                "win" in n -> "windows"
                "linux" in n -> "linux"
                else -> null
            }
        }

        internal fun normalizeArch(osArch: String?): String? {
            val a = osArch?.lowercase() ?: return null
            return when (a) {
                "aarch64", "arm64" -> "arm64"
                "x86_64", "amd64", "x64" -> "x64"
                else -> null
            }
        }
    }
}

data class PlatformEntry(val asset: String, val archive: String?)

/**
 * The tinymist release this plugin version is built and tested against.
 *
 * Read out of the download URL in `platforms.json` rather than configured separately, so bumping
 * the pin stays a one-line change with no second place to drift. [releaseUrl] is the release's
 * page on GitHub, for "what changed" links.
 */
internal data class TinymistPin(val version: TinymistVersion, val releaseUrl: String)

private val PINNED_DOWNLOAD_URL = Regex("""https://github\.com/([^/]+)/([^/]+)/releases/download/(v?[0-9][^/]*)/?""")

/**
 * Parses the pin out of a release-download base URL such as
 * `https://github.com/Myriad-Dreamin/tinymist/releases/download/v0.15.8`. Returns `null` for
 * anything else — a `releases/latest` URL pins nothing, and a test override pointing at a local
 * server is not a release.
 */
internal fun parseTinymistPin(baseUrl: String): TinymistPin? {
    val match = PINNED_DOWNLOAD_URL.matchEntire(baseUrl.trim()) ?: return null
    val (owner, repo, tag) = match.destructured
    val version = TinymistVersion.parse(tag) ?: return null
    return TinymistPin(version, "https://github.com/$owner/$repo/releases/tag/$tag")
}

data class ToolConfig(val baseUrl: String, val platforms: Map<PlatformKey, PlatformEntry>) {
    fun assetFor(key: PlatformKey): PlatformEntry? = platforms[key]
    fun supportedPlatforms(): Set<PlatformKey> = platforms.keys
}

/**
 * Declarative platform matrix for the `tinymist` download, loaded from
 * `/platforms.json` on the plugin classpath. Exposes the tinymist config plus a
 * single authoritative `supported` set — the platforms tinymist ships a binary
 * for — which gates the auto-download flow and the unsupported-platform error
 * message.
 */
object PlatformConfig {

    private val configs: Map<String, ToolConfig> by lazy { load() }

    val tinymist: ToolConfig
        get() = configs["tinymist"] ?: error("platforms.json missing 'tinymist' section")

    /**
     * Test-only override for the tinymist download base URL. When non-null,
     * [tinymistBaseUrl] returns this instead of `tinymist.baseUrl`. Lets tests
     * point [TinymistDownloadService.downloadInBackground] at a [okhttp3.mockwebserver.MockWebServer]
     * so they don't hit the real GitHub releases endpoint. Reset to null in tearDown.
     */
    @get:VisibleForTesting
    @set:VisibleForTesting
    internal var tinymistBaseUrlOverride: String? = null

    /** The tinymist download base URL, with a test-only override layered on top. */
    val tinymistBaseUrl: String
        get() = tinymistBaseUrlOverride ?: tinymist.baseUrl

    /**
     * The pinned tinymist release, read from the real base URL — never the test override, so the
     * managed binary's location does not move when a test points downloads at a local server.
     * `null` only if `platforms.json` stops pinning a release, which a test guards against.
     */
    internal val tinymistPin: TinymistPin? by lazy {
        parseTinymistPin(tinymist.baseUrl).also {
            if (it == null) LOG.warn("platforms.json base URL does not pin a tinymist release: ${tinymist.baseUrl}")
        }
    }

    /**
     * Platforms tinymist ships a binary for. This is the authoritative
     * "supported platform" set for the auto-download flow.
     */
    val supported: Set<PlatformKey> by lazy {
        tinymist.supportedPlatforms()
    }

    fun isSupported(key: PlatformKey): Boolean = key in supported

    /**
     * 1-to-1 DTOs for the `platforms.json` schema. Kept separate from [ToolConfig] /
     * [PlatformEntry] because the JSON ships `platforms` as an array of `{os, arch, ...}`
     * objects, while the public [ToolConfig] exposes them as a `Map<PlatformKey, …>`
     * for fast lookup.
     */
    private data class PlatformEntryDto(
        val os: String,
        val arch: String,
        val asset: String,
        val archive: String? = null,
    )

    private data class ToolConfigDto(
        val baseUrl: String,
        val platforms: List<PlatformEntryDto>,
    ) {
        fun toToolConfig(): ToolConfig {
            val map = LinkedHashMap<PlatformKey, PlatformEntry>()
            for ((os, arch, asset, archive) in platforms) {
                val key = PlatformKey(os, arch)
                if (map.put(key, PlatformEntry(asset, archive)) != null) {
                    LOG.warn("platforms.json: duplicate entry for $key — later entry wins")
                }
            }
            return ToolConfig(baseUrl, map)
        }
    }

    private fun load(): Map<String, ToolConfig> {
        val stream = PlatformConfig::class.java.getResourceAsStream("/platforms.json")
            ?: error("platforms.json not found on classpath")
        val typeRef = object : TypeReference<Map<String, ToolConfigDto>>() {}
        val raw: Map<String, ToolConfigDto> = stream.use {
            jacksonObjectMapper().readValue(it, typeRef)
        }
        return raw.mapValues { (_, dto) -> dto.toToolConfig() }
    }

    /**
     * Human-readable summary of supported platforms, e.g.
     * `darwin/arm64, darwin/x64, linux/arm64, linux/x64, windows/x64`.
     * Used in the unsupported-platform error notification.
     */
    fun supportedPlatformsDescription(): String =
        supported.sortedWith(compareBy({ it.os }, { it.arch })).joinToString(", ") { it.toString() }
}
