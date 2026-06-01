package com.github.pndv.typstrenderer.editor

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Tests for [PdfjsPreviewServer]'s pure routing logic.
 *
 * The production `process()` is a thin shell over [PdfjsPreviewServer.route];
 * the routing decision is what we care about (and what regressed in the past).
 * Testing the extracted pure helper avoids any need to run a Netty channel.
 *
 * `isSupported()` is similarly tested via its pure helper [PdfjsPreviewServer.isSupportedUri]
 * so we don't have to construct a mock [io.netty.handler.codec.http.FullHttpRequest].
 *
 * [PdfjsPreviewerRegistry] is a real singleton; each test registers and tears
 * down its own entries to keep them hermetic.
 */
class PdfjsPreviewServerTest {

    private lateinit var server: PdfjsPreviewServer
    private val registeredIds = mutableListOf<String>()
    private val tempFiles = mutableListOf<File>()

    @Before
    fun setUp() {
        server = PdfjsPreviewServer()
    }

    @After
    fun tearDown() {
        registeredIds.forEach { PdfjsPreviewerRegistry.unregister(it) }
        registeredIds.clear()
        tempFiles.forEach { it.delete() }
        tempFiles.clear()
    }

    private fun register(
        id: String,
        pdf: () -> File? = { null },
        bridge: () -> String = { "" },
    ) {
        PdfjsPreviewerRegistry.register(PdfjsPreviewerRegistration(id, pdf, bridge))
        registeredIds += id
    }

    private fun tempPdf(bytes: ByteArray): File {
        val f = Files.createTempFile("pdfjs-test-", ".pdf").toFile()
        f.writeBytes(bytes)
        tempFiles += f
        return f
    }

    @Test
    fun isSupported_trueForNamespacedPath() {
        assertTrue(server.isSupportedUri("/${PdfjsEndpoints.NAMESPACE}/viewer/web/viewer.html"))
        assertTrue(server.isSupportedUri("/${PdfjsEndpoints.NAMESPACE}/pdf/abc"))
        assertTrue(server.isSupportedUri("/${PdfjsEndpoints.NAMESPACE}/bridge/abc?v=1"))
    }

    @Test
    fun isSupported_falseForOtherPath() {
        assertFalse(server.isSupportedUri("/some-other-plugin/foo"))
        assertFalse(server.isSupportedUri("/"))
        // Missing trailing slash: bare namespace prefix must not match
        // (prevents collisions with hypothetical sibling plugins).
        assertFalse(server.isSupportedUri("/${PdfjsEndpoints.NAMESPACE}"))
    }

    @Test
    fun viewerRoute_returnsClasspathBytes() {
        val resource = server.route("/viewer/web/viewer.html")

        assertNotNull("Expected /pdfjs/web/viewer.html to be on the classpath", resource)
        assertEquals("text/html; charset=utf-8", resource!!.mime)
        assertTrue("Viewer HTML should be non-empty", resource.bytes.isNotEmpty())
    }

    @Test
    fun pdfRoute_unregisteredId_returnsNull() {
        assertNull(server.route("/pdf/unregistered-id-xyz"))
    }

    @Test
    fun pdfRoute_registeredId_returnsFileBytes() {
        val pdfBytes = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D) // %PDF-
        val pdfFile = tempPdf(pdfBytes)
        val id = "test-id-pdf"
        register(id, pdf = { pdfFile })

        val resource = server.route("/pdf/$id")

        assertNotNull(resource)
        assertEquals("application/pdf", resource!!.mime)
        assertArrayEquals(pdfBytes, resource.bytes)
    }

    @Test
    fun bridgeRoute_returnsJs() {
        val id = "test-id-bridge"
        val js = "console.log('bridge');"
        register(id, bridge = { js })

        val resource = server.route("/bridge/$id")

        assertNotNull(resource)
        assertEquals("application/javascript; charset=utf-8", resource!!.mime)
        assertEquals(js, String(resource.bytes, Charsets.UTF_8))
    }

    @Test
    fun unknownSubpath_returnsNull() {
        assertNull(server.route("/anything-else"))
        assertNull(server.route(""))
        assertNull(server.route("/"))
        assertNull(server.route("/viewer"))
        assertNull(server.route("/pdf"))
        assertNull(server.route("/bridge"))
    }
}
