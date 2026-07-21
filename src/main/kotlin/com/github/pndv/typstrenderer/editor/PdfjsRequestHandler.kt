package com.github.pndv.typstrenderer.editor

import com.intellij.openapi.diagnostic.logger
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.*
import org.jetbrains.ide.BuiltInServerManager
import org.jetbrains.ide.HttpRequestHandler
import org.jetbrains.io.send
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Endpoint helpers for the PDF.js viewer served by [PdfjsPreviewServer].
 *
 * We piggyback on IntelliJ's Built-In Netty Server (the same one used for LSP
 * and REST endpoints) instead of registering a JCEF
 * Reasons:
 *  - Works in both in-process and remote JCEF (the latter is the default in
 *    2024.3+ IDEs and bypasses per-browser request handlers for sub-resources).
 *  - Standard pattern used by JetBrains' own Markdown plugin and the
 *    intellij-pdf-viewer plugin.
 *
 * The plugin namespaces all its endpoints under a UUID prefix so traffic
 * doesn't collide with other plugins also extending `httpRequestHandler`.
 */
internal object PdfjsEndpoints {
    /** Plugin-private namespace prefix on the built-in server. */
    const val NAMESPACE = "typst-renderer-7f3a9c12"

    /** Returns the full `http://localhost:PORT/NAMESPACE` base URL for the current IDE run. */
    fun baseUrl(): String {
        val port = BuiltInServerManager.getInstance().port
        return "http://localhost:$port/$NAMESPACE"
    }

    /** URL of the PDF.js viewer page. */
    fun viewerUrl(): String = "${baseUrl()}/viewer/web/viewer.html"

    /** URL serving the compiled PDF for a given previewer. */
    fun pdfUrl(previewerId: String, cacheBust: Long): String =
        "${baseUrl()}/pdf/$previewerId?v=$cacheBust"

    /** URL serving the JSQuery-injected bridge JS for a given previewer. */
    @Suppress("unused")
    fun bridgeUrl(previewerId: String): String = "${baseUrl()}/bridge/$previewerId"
}

/**
 * Per-previewer registration. Each [TypstFilePreviewer] registers its current
 * PDF and bridge-JS suppliers under a unique [id]; the server looks them up
 * on every incoming request.
 */
internal class PdfjsPreviewerRegistration internal constructor(
    val id: String,
    val currentPdf: () -> File?,
    val bridgeJs: () -> String,
)

/**
 * Registers and tracks live previewers. The [PdfjsPreviewServer] consults this
 * map on each request to resolve `/pdf/<id>` and `/bridge/<id>` URLs.
 */
internal object PdfjsPreviewerRegistry {
    private val byId = ConcurrentHashMap<String, PdfjsPreviewerRegistration>()

    fun register(reg: PdfjsPreviewerRegistration) {
        byId[reg.id] = reg
    }

    fun unregister(id: String) {
        byId.remove(id)
    }

    fun get(id: String): PdfjsPreviewerRegistration? = byId[id]
}

/**
 * `HttpRequestHandler` extension serving PDF.js viewer assets, the compiled PDF,
 * and the bridge JS over IntelliJ's built-in Netty server.
 *
 * Routes (all under `/typst-renderer-<...>/`):
 *  - `/viewer/...`        — PDF.js classpath assets under `/pdfjs/...`.
 *  - `/pdf/<id>`          — the compiled PDF for previewer `<id>`.
 *  - `/bridge/<id>`       — the bridge JS for previewer `<id>` (with the
 *                           JBCefJSQuery-inject snippet substituted in).
 *
 * Registered in plugin.xml:
 *   `<httpRequestHandler implementation="...PdfjsPreviewServer"/>`
 *
 * Note on PDF.js version: pinned to 4.10.x in [gradle/libs.versions.toml].
 * PDF.js 5.x aggressively adopts new JS APIs (URL.parse static, Uint8Array
 * toHex/fromHex, Map.prototype.getOrInsertComputed) that JCEF's bundled
 * Chromium (currently 137) doesn't yet ship. The 4.x line predates that
 * adoption pattern. When JetBrains upgrades JCEF to a newer Chromium build,
 * bump pdfjs in libs.versions.toml back to a 5.x release.
 */
internal class PdfjsPreviewServer : HttpRequestHandler() {

    private val log = logger<PdfjsPreviewServer>()

    override fun isSupported(request: FullHttpRequest): Boolean =
        isSupportedUri(request.uri())

    internal fun isSupportedUri(uri: String): Boolean {
        val path = QueryStringDecoder(uri).path()
        return path.startsWith("/${PdfjsEndpoints.NAMESPACE}/")
    }

    override fun process(
        urlDecoder: QueryStringDecoder,
        request: FullHttpRequest,
        context: ChannelHandlerContext
    ): Boolean {
        val fullPath = urlDecoder.path()
        val path = fullPath.removePrefix("/${PdfjsEndpoints.NAMESPACE}")

        log.info("[pdfjs] http request: $fullPath")

        val resource: Resource? = route(path)

        if (resource == null) {
            log.warn("[pdfjs] 404: $fullPath")
            val resp = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND)
            resp.send(context.channel(), request)
            return true
        }
        log.info("[pdfjs] 200: $fullPath -> ${resource.bytes.size} bytes (${resource.mime})")

        val response = DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            HttpResponseStatus.OK,
            Unpooled.wrappedBuffer(resource.bytes)
        )
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, resource.mime)
        // PDF.js assets are immutable per IDE run; allow the browser to cache them.
        // The /pdf/ endpoint relies on a cache-bust query param instead.
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache")
        response.send(context.channel(), request)
        return true
    }

    internal fun route(path: String): Resource? = when {
        path.startsWith("/viewer/") -> {
            val resourcePath = "/pdfjs/" + path.removePrefix("/viewer/")
            classpath(resourcePath)
        }
        path.startsWith("/pdf/") -> {
            val id = path.removePrefix("/pdf/")
            PdfjsPreviewerRegistry.get(id)?.let { reg ->
                reg.currentPdf()?.let { if (!it.isFile) null else Resource(it.readBytes(), "application/pdf") }
            }
        }
        path.startsWith("/bridge/") -> {
            val id = path.removePrefix("/bridge/")
            PdfjsPreviewerRegistry.get(id)?.let { reg ->
                Resource(reg.bridgeJs().toByteArray(Charsets.UTF_8), "application/javascript; charset=utf-8")
            }
        }
        else -> {
            log.warn("[pdfjs] unsupported path: $path. Returning null.")
            null
        }
    }

    internal data class Resource(val bytes: ByteArray, val mime: String) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Resource

            if (!bytes.contentEquals(other.bytes)) return false
            if (mime != other.mime) return false

            return true
        }

        override fun hashCode(): Int {
            var result = bytes.contentHashCode()
            result = 31 * result + mime.hashCode()
            return result
        }
    }

    private fun classpath(resourcePath: String): Resource? {
        val bytes = javaClass.getResourceAsStream(resourcePath)?.use { it.readBytes() } ?: return null
        return Resource(bytes, mimeFor(resourcePath))
    }

    private fun mimeFor(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "html" -> "text/html; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "mjs", "js" -> "application/javascript; charset=utf-8"
        "map", "json" -> "application/json; charset=utf-8"
        "png" -> "image/png"
        "svg" -> "image/svg+xml"
        "gif" -> "image/gif"
        "pdf" -> "application/pdf"
        "properties", "ftl" -> "text/plain; charset=utf-8"
        "wasm" -> "application/wasm"
        "bcmap", "pfb", "icc" -> "application/octet-stream"
        else -> "application/octet-stream"
    }
}
