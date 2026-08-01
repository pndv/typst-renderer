package com.github.pndv.typstrenderer.lsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests for [TypstExportCoalescer], the guard against concurrent exports of one target.
 *
 * The regression it exists for: with a main entry pinned, every open previewer resolves to the
 * *same* export target, so one pin change fired 21 simultaneous `exportPdf(main.typ)` calls, all
 * renaming a temp file onto the same `output/main.pdf`. Three collided with
 * `failed to persist temporary file: Access is denied. (os error 5)`; the rest were duplicate
 * compiles of an identical document.
 *
 * Fixture-free — the coalescer takes its export function as a lambda, so the tests drive it with
 * a fake that counts invocations and can be held open to create real overlap.
 */
class TypstExportCoalescerTest {

    private fun target(name: String): Path = Path.of(System.getProperty("java.io.tmpdir"), name)

    @Test
    fun concurrentRequestsForOneTarget_compileOnce() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()

        val coalescer = TypstExportCoalescer { path ->
            calls.incrementAndGet()
            started.countDown() // Hold the export open so the other callers genuinely overlap rather than
            // arriving after it has already finished.
            release.await(10, TimeUnit.SECONDS)
            ExportPdfResult.Exported(path)
        }

        val main = target("main.typ")
        val pool = Executors.newFixedThreadPool(8)
        try { // One owner, then seven joiners once the owner is provably inside doExport.
            // Callable, not the Runnable overload — the latter yields a Future<*> whose get()
            // is always null, which would silently assert nothing.
            val ownerResult = pool.submit(Callable { coalescer.export(main) })
            assertTrue("owner should enter doExport", started.await(10, TimeUnit.SECONDS))

            val joiners = (1..7).map { pool.submit(Callable { coalescer.export(main) }) }
            Thread.sleep(200) // let the joiners reach the in-flight check
            release.countDown()

            val results = (listOf(ownerResult) + joiners).map { it.get(15, TimeUnit.SECONDS) }

            assertEquals("every caller must get a result", 8, results.size)
            results.forEach {
                assertEquals(
                    ExportPdfResult.Exported(main), it
                )
            } // One compile for the owner; joiners arm the trailing edge, so at most one more.
            assertTrue("expected 1-2 compiles for 8 callers, got ${calls.get()}", calls.get() in 1..2)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun distinctTargets_areNotCoalesced() { // Without a pin every previewer exports its own file; those must stay independent.
        val seen = ConcurrentHashMap<Path, Int>()
        val coalescer = TypstExportCoalescer { path ->
            seen.merge(path, 1, Int::plus)
            ExportPdfResult.Exported(path)
        }

        val targets = (1..5).map { target("chapter$it.typ") }
        targets.forEach { assertEquals(ExportPdfResult.Exported(it), coalescer.export(it)) }

        assertEquals("each distinct target compiles on its own", 5, seen.size)
        assertTrue("no target compiled more than once", seen.values.all { it == 1 })
    }

    @Test
    fun sequentialRequests_eachCompile() { // Coalescing must not memoise: a later request has to recompile, otherwise an edited
        // document would keep serving the first PDF forever.
        val calls = AtomicInteger()
        val coalescer = TypstExportCoalescer { path ->
            calls.incrementAndGet()
            ExportPdfResult.Exported(path)
        }

        val main = target("main.typ")
        repeat(3) { coalescer.export(main) }

        assertEquals("no caching between non-overlapping requests", 3, calls.get())
    }

    @Test
    fun failingExport_releasesTheSlotForTheNextCaller() { // A thrown export must not leave the target permanently "in flight", which would wedge
        // every future export of it behind a future that never completes.
        val calls = AtomicInteger()
        val coalescer = TypstExportCoalescer { path ->
            if (calls.incrementAndGet() == 1) error("boom") else ExportPdfResult.Exported(path)
        }

        val main = target("main.typ")
        runCatching { coalescer.export(main) }

        assertEquals(ExportPdfResult.Exported(main), coalescer.export(main))
        assertEquals(2, calls.get())
    }

    @Test
    fun serverFailure_isPropagatedNotSwallowed() { // A tinymist-side rejection is a legitimate result and must reach the caller so the
        // diagnostic is shown, rather than being masked as Unavailable.
        val coalescer = TypstExportCoalescer { ExportPdfResult.Failed("error: label does not exist") }
        val result = coalescer.export(target("main.typ"))
        assertTrue(result is ExportPdfResult.Failed)
        assertEquals("error: label does not exist", (result as ExportPdfResult.Failed).detail)
    }
}
