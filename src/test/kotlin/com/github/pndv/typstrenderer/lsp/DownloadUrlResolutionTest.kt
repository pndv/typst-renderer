package com.github.pndv.typstrenderer.lsp

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for download URL resolution HEAD-request logic in [TinymistDownloadService]
 * and [TypstDownloadService]. Runs against a local [MockWebServer] by passing the
 * server's URL as `baseUrl` to `resolveLatestDownloadUrl`.
 *
 * Covers Batch 2 of the test-coverage plan:
 *  - D.54  HEAD 404 → returns null
 *  - D.55  HEAD network error (connection dropped) → returns null
 *  - Happy path: HEAD 200 → returns the constructed URL
 *
 * Services are instantiated directly via `Class.newInstance()` because they are
 * declared `@Service` — the `getInstance()` accessor would require a running
 * IntelliJ application, which these pure-logic tests deliberately avoid.
 */
class DownloadUrlResolutionTest {

    private lateinit var server: MockWebServer
    private lateinit var tinymistService: TinymistDownloadService
    private lateinit var typstService: TypstDownloadService

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        // App-level @Service classes have a no-arg constructor; instantiate directly
        // to avoid requiring the full IntelliJ ApplicationManager.
        tinymistService = TinymistDownloadService::class.java.getDeclaredConstructor().newInstance()
        typstService = TypstDownloadService::class.java.getDeclaredConstructor().newInstance()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun baseUrl(): String = server.url("/download").toString().removeSuffix("/")

    // ---- Happy path (sanity check that the mock wiring works) ----

    @Test
    fun tinymist_resolveLatestDownloadUrl_head200_returnsConstructedUrl() {
        server.enqueue(MockResponse().setResponseCode(200))

        val url = tinymistService.resolveLatestDownloadUrl(baseUrl(), "tinymist-darwin-arm64")

        assertNotNull(url)
        assertEquals("${baseUrl()}/tinymist-darwin-arm64", url)

        val request = server.takeRequest()
        assertEquals("HEAD", request.method)
        assertTrue(request.path?.endsWith("/tinymist-darwin-arm64") == true)
    }

    @Test
    fun typst_resolveLatestDownloadUrl_head200_returnsConstructedUrl() {
        server.enqueue(MockResponse().setResponseCode(200))

        val url = typstService.resolveLatestDownloadUrl(baseUrl(), "typst-aarch64-apple-darwin.tar.xz")

        assertNotNull(url)
        assertEquals("${baseUrl()}/typst-aarch64-apple-darwin.tar.xz", url)
    }

    // ---- D.54  HEAD 404 → returns null ----

    @Test
    fun tinymist_resolveLatestDownloadUrl_head404_returnsNull() {
        server.enqueue(MockResponse().setResponseCode(404))

        val url = tinymistService.resolveLatestDownloadUrl(baseUrl(), "tinymist-bogus")

        assertNull("404 should cause null URL (asset doesn't exist)", url)
    }

    @Test
    fun typst_resolveLatestDownloadUrl_head404_returnsNull() {
        server.enqueue(MockResponse().setResponseCode(404))

        val url = typstService.resolveLatestDownloadUrl(baseUrl(), "typst-bogus.tar.xz")

        assertNull(url)
    }

    // ---- D.55  HEAD network error → returns null ----

    @Test
    fun tinymist_resolveLatestDownloadUrl_networkError_returnsNull() {
        // DISCONNECT_AT_START drops the connection before any bytes are sent,
        // which surfaces as an IOException in HttpRequests.
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        val url = tinymistService.resolveLatestDownloadUrl(baseUrl(), "tinymist-darwin-arm64")

        assertNull("Network error should cause null URL, not propagate exception", url)
    }

    @Test
    fun typst_resolveLatestDownloadUrl_networkError_returnsNull() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        val url = typstService.resolveLatestDownloadUrl(baseUrl(), "typst-aarch64-apple-darwin.tar.xz")

        assertNull(url)
    }

    @Test
    fun resolveLatestDownloadUrl_serverNotRunning_returnsNull() {
        // Shut down the server to simulate an unreachable host
        val deadBaseUrl = server.url("/dead").toString().removeSuffix("/")
        server.shutdown()

        val url = tinymistService.resolveLatestDownloadUrl(deadBaseUrl, "tinymist-darwin-arm64")

        assertNull(url)
    }

    // ---- 5xx response ----

    @Test
    fun resolveLatestDownloadUrl_head500_returnsNull() {
        server.enqueue(MockResponse().setResponseCode(503))

        val url = tinymistService.resolveLatestDownloadUrl(baseUrl(), "tinymist-darwin-arm64")

        assertNull("5xx should not be treated as a valid URL", url)
    }
}
