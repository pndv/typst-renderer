// PDF.js viewer <-> Kotlin bridge.
//
// Injected into the PDF.js viewer page after loading. Reports the current page and
// scroll offset back to Kotlin (throttled), and exposes two globals:
//   window.__typstOpenPdf(url)            — hot-swap the displayed PDF (no page reload).
//   window.__typstSetPendingRestore(json) — stash a viewport to apply on the next 'pagesloaded'.
//
// Kotlin substitutes the '/*__REPORT_CALL__*/' marker with the JS snippet
// returned from JBCefJSQuery.inject("payload") so reports flow back to the IDE.
(function () {
  if (window.__typstBridgeInstalled) return;
  window.__typstBridgeInstalled = true;

  const app = () => window.PDFViewerApplication;
  let lastReported = null;
  let pendingRestore = null;
  let reportTimer = null;

  function schedule() {
    if (reportTimer) return;
    reportTimer = setTimeout(() => {
      reportTimer = null;
      report();
    }, 150);
  }

  function captureViewport() {
    const a = app();
    if (!a || !a.pdfViewer) return null;
    const page = a.pdfViewer.currentPageNumber || 1;
    const container = a.pdfViewer.container;
    const yOffset = container ? container.scrollTop : 0;
    return { page: page, yOffset: yOffset };
  }

  function report() {
    const v = captureViewport();
    if (!v) return;
    if (
      lastReported &&
      v.page === lastReported.page &&
      Math.abs(v.yOffset - lastReported.yOffset) < 2
    ) return;
    lastReported = v;
    const payload = JSON.stringify(v);
    /*__REPORT_CALL__*/
  }

  function applyRestore() {
    if (!pendingRestore) return;
    const a = app();
    if (!a || !a.pdfViewer) return;
    const target = pendingRestore;
    pendingRestore = null;
    try {
      a.pdfViewer.currentPageNumber = target.page;
      requestAnimationFrame(() => {
        const c = a.pdfViewer && a.pdfViewer.container;
        if (c) c.scrollTop = target.yOffset;
      });
    } catch (e) {
      console.warn("[typst] restore failed", e);
    }
  }

  function install() {
    const a = app();
    if (!a || !a.initializedPromise) {
      setTimeout(install, 50);
      return;
    }
    a.initializedPromise.then(() => {
      a.eventBus.on("pagesloaded", applyRestore);
      a.eventBus.on("pagechanging", schedule);
      const c = a.pdfViewer && a.pdfViewer.container;
      if (c) c.addEventListener("scroll", schedule, { passive: true });
    });
  }

  window.__typstSetPendingRestore = function (json) {
    try {
      pendingRestore = json ? JSON.parse(json) : null;
    } catch (e) {
      pendingRestore = null;
    }
  };

  window.__typstOpenPdf = function (url) {
    const a = app();
    if (!a) return;
    // Before swapping documents, snapshot the viewport so the next load
    // restores to the same spot (within-session scroll preservation).
    const v = captureViewport();
    if (v) pendingRestore = v;
    try {
      a.open({ url: url });
    } catch (e) {
      console.warn("[typst] open failed", e);
    }
  };

  install();
})();
