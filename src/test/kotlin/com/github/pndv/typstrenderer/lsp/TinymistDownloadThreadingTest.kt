package com.github.pndv.typstrenderer.lsp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Regression test for a CI-only deadlock that surfaced when
 * [TinymistDownloadService.downloadInBackground] was scheduled via
 * `ProgressManager.getInstance().run(task)` from inside a non-blocking
 * read action — the context the LSP framework uses to invoke
 * `TinymistLspServerSupportProvider.fileOpened`.
 *
 * Before the fix, the call site threw:
 *   `IllegalStateException("Calling invokeAndWait from read-action leads to possible deadlock")`
 *
 * After the fix (`Task.Backgroundable.queue()`), scheduling is asynchronous
 * and does not call `invokeAndWait`, so the read action exits cleanly.
 *
 * The test points the download service at a [MockWebServer] so it stays
 * hermetic — no network access, no files written, no leftover state.
 */
class TinymistDownloadThreadingTest : BasePlatformTestCase() {

    private lateinit var server: MockWebServer

    override fun setUp() {
        super.setUp()
        server = MockWebServer().apply { start() }
        // Point TinymistDownloadService at the mock so the queued task body
        // doesn't reach out to GitHub. The HEAD request below returns 404,
        // so resolveLatestDownloadUrl returns null and the task short-circuits
        // — we don't actually attempt a download.
        PlatformConfig.tinymistBaseUrlOverride = server.url("/").toString().trimEnd('/')
    }

    override fun tearDown() {
        try {
            PlatformConfig.tinymistBaseUrlOverride = null
            server.shutdown()
        } finally {
            super.tearDown()
        }
    }

    fun testDownloadInBackgroundIsSafeFromReadAction() {
        // 404 → resolveLatestDownloadUrl returns null → task body bails after
        // notifyError(...) and onComplete(false). All we care about here is
        // that the *scheduling* itself (which happens INSIDE the read action)
        // doesn't throw — that's the regression we're guarding against.
        server.enqueue(MockResponse().setResponseCode(404))

        val service = TinymistDownloadService.getInstance()
        val done = CountDownLatch(1)

        // Wrapping in a read action reproduces the LSP-framework call context.
        // With the buggy ProgressManager.getInstance().run(...), this throws
        // IllegalStateException. With Task.Backgroundable.queue(), it returns
        // cleanly and the task runs in the background.
        ApplicationManager.getApplication().runReadAction {
            service.downloadInBackground(project) { _ -> done.countDown() }
        }

        // Give the queued task a chance to settle so we don't leak it into
        // the next test. Either outcome (success or graceful failure) is
        // acceptable — this test only fails if scheduling threw above.
        done.await(10, TimeUnit.SECONDS)
    }
}
