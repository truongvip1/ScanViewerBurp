package com.scanviewer;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.handler.HttpRequestToBeSent;
import javax.swing.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class UiProbes {
    static Object invoke(Object object, String name, Object... args) throws Exception {
        for (Method m : object.getClass().getDeclaredMethods()) if (m.getName().equals(name)) { m.setAccessible(true); return m.invoke(object, args); }
        throw new IllegalArgumentException(name);
    }
    static void waitIdle(ScanTimelinePanel panel) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (((AtomicBoolean) ReviewProbes.field(panel, "filterWorkerRunning")).get()) {
            if (System.nanoTime() > end) throw new AssertionError("worker still running"); Thread.sleep(5);
        }
    }
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        PersistenceProbes.factory();
        ScanSessionStore store = new ScanSessionStore(ReviewProbes.persistence(), ReviewProbes::memoryObject);
        ScanQueue queue = new ScanQueue(store, () -> null, (m,e) -> {}, false);
        long session = store.startCustomSession(ReviewProbes.sample("/target"), null);
        store.recordRequest((HttpRequestToBeSent) ReviewProbes.fixture("outbound", ReviewProbes.sample("/target?probe=test").toString(), 930));
        AtomicReference<ScanTimelinePanel> ref = new AtomicReference<>();
        ReviewProbes.edt(() -> {
            ref.set(new ScanTimelinePanel((MontoyaApi) ReviewProbes.dummy(MontoyaApi.class), store, queue));
            for (String name : List.of("displayTimer", "trafficFilterTimer", "searchFilterDebounce")) ((javax.swing.Timer) ReviewProbes.field(ref.get(), name)).stop();
        });
        ScanTimelinePanel panel = ref.get();
        try {
            ReviewProbes.filtered(panel);
            ReviewProbes.edt(() -> {
                JTabbedPane tabs = (JTabbedPane) ReviewProbes.field(panel, "scanTabs");
                JTable all = (JTable) ((JScrollPane) tabs.getComponentAt(0)).getViewport().getView();
                all.setRowSelectionInterval(0, 0);
                tabs.setSelectedIndex(1);
                JTable own = (JTable) ((JScrollPane) tabs.getComponentAt(1)).getViewport().getView();
                own.setRowSelectionInterval(0, 0);
                String overview = ((JTextPane) ReviewProbes.field(panel, "comparisonOverview")).getText();
                tabs.setSelectedIndex(0);
                String after = ((JTextPane) ReviewProbes.field(panel, "comparisonOverview")).getText();
                ReviewProbes.expect(!overview.isEmpty() && after.equals(overview), "expected context-insensitive detail cache");
                System.out.println("DETAIL_CONTEXT: return to same row in All traffic -> Compare remains session-only text: " + after);
            });
            // Hold EDT while two complete incremental passes publish their results to that EDT.
            // No raw processing is being blocked: only applying the already-computed UI result is delayed.
            ReviewProbes.edt(() -> {
                ReviewProbes.field(panel, "visibleEntryIds", Set.of());
                ((AtomicBoolean) ReviewProbes.field(panel, "filterFullRecompute")).set(false);
                Map<Long, ScanRequestEntry.Snapshot> dirty = (Map<Long, ScanRequestEntry.Snapshot>) ReviewProbes.field(panel, "filterDirtyEntries");
                var first = new ScanRequestEntry(101, null, 1, ReviewProbes.sample("/one"), "first", PayloadInfo.BASELINE).snapshot();
                var second = new ScanRequestEntry(102, null, 1, ReviewProbes.sample("/two"), "second", PayloadInfo.BASELINE).snapshot();
                dirty.clear(); dirty.put(first.id(), first);
                ((AtomicBoolean) ReviewProbes.field(panel, "filterRefreshRequested")).set(true);
                ReviewProbes.call(panel, "startFilterWorker"); waitIdle(panel);
                dirty.put(second.id(), second);
                ((AtomicBoolean) ReviewProbes.field(panel, "filterRefreshRequested")).set(true);
                ReviewProbes.call(panel, "startFilterWorker"); waitIdle(panel);
            });
            ReviewProbes.edt(() -> {});
            Set<Long> visible = (Set<Long>) ReviewProbes.field(panel, "visibleEntryIds");
            ReviewProbes.expect(visible.equals(Set.of(102L)), "expected second worker to overwrite first matching set");
            System.out.println("INCREMENTAL_FILTER: two matching batches published before UI applies results -> visible=" + visible + " (expected [101,102])");
        } finally { ReviewProbes.edt(panel::close); queue.close(); store.close(); }
    }
}
