package com.github.pndv.typstrenderer.lsp

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.pndv.typstrenderer.lsp.PlatformConfig.tinymistBaseUrl
import com.intellij.openapi.diagnostic.logger
import org.jetbrains.annotations.VisibleForTesting

private val LOG = logger<PlatformConfig>()

data class PlatformKey(val os: String, val arch: String) {
    override fun toString(): String = "$os/$arch"

    companion object {
        /**
         * Normalizes the host's `os.name` / `os.arch` system properties into the
         * canonical values used in `platforms.json` (`darwin` / `linux` / `windows`,
         * `arm64` / `x64`). Returns null if either property is missing or unrecognised.
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

data class ToolConfig(val baseUrl: String, val platforms: Map<PlatformKey, PlatformEntry>) {
    fun assetFor(key: PlatformKey): PlatformEntry? = platforms[key]
    fun supportedPlatforms(): Set<PlatformKey> = platforms.keys
}

/**
 * Declarative platform matrix for `tinymist` and `typst` downloads, loaded from
 * `/platforms.json` on the plugin classpath. Exposes per-tool configs plus a
 * single authoritative `supported` set — the intersection of the two tools'
 * platforms. The plugin requires both binaries to function end-to-end, so the
 * intersection is what gates the auto-download flow and the unsupported-platform
 * error message.
 */
object PlatformConfig {

    private val configs: Map<String, ToolConfig> by lazy { load() }

    val tinymist: ToolConfig
        get() = configs["tinymist"] ?: error("platforms.json missing 'tinymist' section")
    val typst: ToolConfig
        get() = configs["typst"] ?: error("platforms.json missing 'typst' section")

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
     * Platforms on which BOTH tools are available. This is the authoritative
     * "supported platform" set for the auto-download flow.
     */
    val supported: Set<PlatformKey> by lazy {
        tinymist.supportedPlatforms() intersect typst.supportedPlatforms()
    }

    fun isSupported(key: PlatformKey): Boolean = key in supported

    private fun load(): Map<String, ToolConfig> {
        val stream =
            PlatformConfig::class.java.getResourceAsStream("/platforms.json")
            ?: error("platforms.json not found on classpath")
        val raw: Map<String, Any> = stream.use {
            val typeReference = object : com.fasterxml.jackson.core.type.TypeReference<Map<String, Any>>() {}
            ObjectMapper().readValue(it, typeReference)
        }
        return raw.mapValues { (tool, value) -> parseToolConfig(tool, value) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseToolConfig(tool: String, value: Any): ToolConfig {
        val node = value as? Map<String, Any> ?: error("platforms.json: '$tool' is not an object")
        val baseUrl = node["baseUrl"] as? String ?: error("platforms.json: '$tool' missing 'baseUrl'")
        val platforms =
            node["platforms"] as? List<Map<String, Any?>> ?: error("platforms.json: '$tool' missing 'platforms' array")

        val map = LinkedHashMap<PlatformKey, PlatformEntry>()
        for (p in platforms) {
            val os = p["os"] as? String ?: error("platforms.json: '$tool' entry missing 'os'")
            val arch = p["arch"] as? String ?: error("platforms.json: '$tool' entry missing 'arch'")
            val asset = p["asset"] as? String ?: error("platforms.json: '$tool' entry missing 'asset'")
            val archive = p["archive"] as? String
            val key = PlatformKey(os, arch)
            if (map.put(key, PlatformEntry(asset, archive)) != null) {
                LOG.warn("platforms.json: duplicate entry for '$tool' $key — later entry wins")
            }
        }
        return ToolConfig(baseUrl, map)
    }

    /**
     * Human-readable summary of supported platforms, e.g.
     * `darwin/arm64, darwin/x64, linux/arm64, linux/x64, windows/x64`.
     * Used in the unsupported-platform error notification.
     */
    fun supportedPlatformsDescription(): String =
        supported.sortedWith(compareBy({ it.os }, { it.arch })).joinToString(", ") { it.toString() }
}
