package com.scanviewer;

import burp.api.montoya.http.message.params.HttpParameterType;
import burp.api.montoya.http.message.params.ParsedHttpParameter;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.http.handler.HttpResponseReceived;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.*;

/** Read-only project review probes: simulated messages, no real network or project edits. */
public final class ReviewOctober18 {
    static ParsedHttpParameter param(String name, String value) {
        return (ParsedHttpParameter) ReviewOctober14.helper("parsedParameter",
                new Class<?>[]{String.class, String.class, HttpParameterType.class}, name, value, HttpParameterType.URL);
    }
    static HttpRequest request(String raw, List<ParsedHttpParameter> parameters) {
        HttpRequest base = (HttpRequest) ReviewOctober14.helper("requestWithParameters",
                new Class<?>[]{String.class, List.class}, raw, parameters);
        return (HttpRequest) Proxy.newProxyInstance(HttpRequest.class.getClassLoader(), new Class<?>[]{HttpRequest.class},
                (self, method, args) -> method.getName().equals("parameters") && args != null && args.length == 1
                        ? parameters.stream().filter(parameter -> parameter.type() == args[0]).toList()
                        : method.invoke(base, args));
    }
    static ScanRequestEntry.Snapshot row(long id) {
        return new ScanRequestEntry(id, 1L, 1, ReviewOctober14.req(ReviewOctober14.GET),
                "GET /item", PayloadInfo.UNKNOWN).snapshot();
    }
    public static void main(String[] args) throws Exception {
        mixedPayload();
        duplicateComparison();
        filterShortCircuit();
        headerReflection();
        failedTempCopy();
        SwingUtilities.invokeAndWait(ReviewOctober18::sparseBatch);
        SwingUtilities.invokeAndWait(ReviewOctober18::queueLayout);
    }
    static void mixedPayload() {
        String before = "GET /?q=old HTTP/1.1\r\nHost: example.test\r\nX-Input: safe\r\n\r\n";
        String after = "GET /?q=new HTTP/1.1\r\nHost: example.test\r\nX-Input: header-probe\r\n\r\n";
        PayloadInfo result = PayloadDetector.compare(PayloadDetector.baselineFrom(List.of(param("q", "old")), before),
                List.of(param("q", "new")), after);
        ReviewOctober14.check(!result.insertionPoint().contains("X-Input"), "Mixed-change omission no longer present");
        System.out.println("MIXED_CHANGES: payload='" + result.payload() + "', insertion='" + result.insertionPoint() + "'");
    }
    static void duplicateComparison() {
        HttpRequest before = request("GET /?tag=first&tag=last HTTP/1.1\r\nHost: example.test\r\n\r\n",
                List.of(param("tag", "first"), param("tag", "last")));
        HttpRequest after = request("GET /?tag=first&tag=probe&tag=last HTTP/1.1\r\nHost: example.test\r\n\r\n",
                List.of(param("tag", "first"), param("tag", "probe"), param("tag", "last")));
        var result = RequestComparison.compare(before, after);
        System.out.println("DUPLICATE_PARAMETER_DIFF: " + result.changes());
    }
    static void filterShortCircuit() {
        AtomicInteger reads = new AtomicInteger();
        HttpResponse base = ReviewOctober14.res("HTTP/1.1 200 OK\r\n\r\nneedle");
        HttpResponse tracked = (HttpResponse) Proxy.newProxyInstance(HttpResponse.class.getClassLoader(),
                new Class<?>[]{HttpResponse.class}, (self, method, args) -> {
                    if (method.getName().equals("toString")) reads.incrementAndGet();
                    return method.invoke(base, args);
                });
        ScanRequestEntry entry = new ScanRequestEntry(1, 1L, 1, ReviewOctober14.req(ReviewOctober14.GET), "GET /item", PayloadInfo.UNKNOWN);
        entry.complete(tracked, 10, null);
        reads.set(0);
        FilterSpec filter = new FilterSpec("needle", FilterSpec.SearchScope.RESPONSE, FilterSpec.MatchMode.LITERAL,
                false, FilterSpec.JoinMode.ALL, FilterSpec.StatusMode.SERVER_ERROR, FilterSpec.ResponseMode.ANY,
                2000, FilterSpec.BookmarkMode.ANY);
        boolean match = filter.matches(entry.snapshot());
        System.out.println("AND_FILTER: match=" + match + ", rejected200RawReads=" + reads.get());
    }
    static void headerReflection() {
        var metrics = ResponseMetrics.compare(null,
                ReviewOctober14.res("HTTP/1.1 302 Found\r\nLocation: /tokenXYZ\r\n\r\n"), "tokenXYZ", false);
        System.out.println("HEADER_REFLECTION: reflected=" + metrics.payloadReflected() + ", offsets=" + metrics.reflectionOffsets());
    }
    static void failedTempCopy() {
        var memory = ReviewOctober14.instance("MemoryPersistence");
        ScanSessionStore store = new ScanSessionStore(ReviewOctober14.persistence(memory), ReviewOctober14::newObject);
        try {
            store.startCustomSession(ReviewOctober14.req(ReviewOctober14.GET), null);
            store.recordRequest(ReviewOctober14.outbound(ReviewOctober14.GET, 701));
            HttpResponseReceived normal = ReviewOctober14.inbound("HTTP/1.1 201 Created\r\n\r\ncreated", 701);
            HttpResponseReceived broken = (HttpResponseReceived) Proxy.newProxyInstance(HttpResponseReceived.class.getClassLoader(),
                    new Class<?>[]{HttpResponseReceived.class}, (self, method, args) -> {
                        if (method.getName().equals("copyToTempFile")) throw new IllegalStateException("Simulated temp copy failure");
                        return method.invoke(normal, args);
                    });
            boolean failed = false;
            try { store.recordResponse(broken); } catch (IllegalStateException expected) { failed = true; }
            var retried = store.recordResponse(normal);
            System.out.println("FAILED_RESPONSE_TEMP_COPY: firstFailed=" + failed + ", retryMatched=" + (retried != null)
                    + ", storedResponse=" + (store.allEntries().get(0).response() != null));
        } finally { store.close(); }
    }
    static void sparseBatch() {
        ScanRequestTableModel model = new ScanRequestTableModel();
        for (int i = 0; i < 10000; i++) model.add(row(i + 1));
        JTable table = new JTable(model);
        IncrementalScanRowSorter sorter = new IncrementalScanRowSorter(model); table.setRowSorter(sorter);
        AtomicInteger publishes = new AtomicInteger();
        sorter.addRowSorterListener(event -> publishes.incrementAndGet());
        long start = System.nanoTime();
        model.beginBatch();
        for (int i = 0; i < 10000; i += 20) model.update(row(i + 1));
        model.endBatch();
        long elapsed = (System.nanoTime() - start) / 1000000;
        System.out.println("SPARSE_BATCH: rows=10000, changed=500, mappingPublications=" + publishes.get() + ", EDT_ms=" + elapsed);
    }
    static void queueLayout() {
        var memory = ReviewOctober14.instance("MemoryPersistence");
        ScanSessionStore store = new ScanSessionStore(ReviewOctober14.persistence(memory), ReviewOctober14::newObject);
        ScanQueue queue = new ScanQueue(store, ReviewOctober14::audit, (message, error) -> {}, false);
        try {
            ScanQueuePanel panel = new ScanQueuePanel(queue);
            panel.setSize(900, 600); layout(panel);
            var top = (java.awt.Container) panel.getComponent(0);
            var controls = (java.awt.Container) top.getComponent(0);
            int clipped = 0;
            for (var component : controls.getComponents()) if (component.getY() + component.getHeight() > controls.getHeight()) clipped++;
            System.out.println("QUEUE_TOOLBAR: width=900, controlsHeight=" + controls.getHeight() + ", clippedButtons=" + clipped);
        } finally { queue.close(); store.close(); }
    }
    static void layout(java.awt.Container container) {
        container.doLayout();
        for (var child : container.getComponents()) if (child instanceof java.awt.Container nested) layout(nested);
    }
}
