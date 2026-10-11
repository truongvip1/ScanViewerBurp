package com.scanviewer;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.handler.HttpRequestToBeSent;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.persistence.Persistence;
import burp.api.montoya.persistence.PersistedObject;
import javax.swing.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Review-only reproductions, isolated from production sources and the shipped JAR. */
public class ReviewProbes {
    static Object fixture(String name, Object... args) {
        try {
            for (Method method : RegressionTests.class.getDeclaredMethods()) if (method.getName().equals(name)) {
                method.setAccessible(true); return method.invoke(null, args);
            }
            throw new IllegalArgumentException(name);
        } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
    }
    static HttpRequest request(String raw) { return (HttpRequest) fixture("request", raw); }
    static HttpResponse response(String raw) { return (HttpResponse) fixture("response", raw); }
    static HttpRequestResponse exchange(HttpRequest req, HttpResponse res) { return (HttpRequestResponse) fixture("exchange", req, res); }
    static Object field(Object object, String name) throws Exception { Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object); }
    static void field(Object object, String name, Object value) throws Exception { Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); f.set(object, value); }
    static Object call(Object object, String name) throws Exception { Method m = object.getClass().getDeclaredMethod(name); m.setAccessible(true); return m.invoke(object); }
    static PersistedObject memoryObject() {
        try { Method m = Class.forName("com.scanviewer.RegressionTests$MemoryObject").getDeclaredMethod("newPersistedObject"); m.setAccessible(true); return (PersistedObject) m.invoke(null); }
        catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
    }
    static Persistence persistence() {
        PersistedObject root = memoryObject();
        return (Persistence) Proxy.newProxyInstance(ReviewProbes.class.getClassLoader(), new Class[]{Persistence.class}, (p,m,a) -> m.getName().equals("extensionData") ? root : null);
    }
    static HttpRequest sample(String path) { return request("GET " + path + " HTTP/1.1\r\nHost: example.test\r\nAuthorization: old-token\r\n\r\n"); }
    static void expect(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static void effectiveBaseline() {
        ScanSession session = new ScanSession(1, "1", sample("/target"), null);
        HttpRequest updated = sample("/target").withUpdatedHeader("Authorization", "fresh-token");
        session.setEffectiveScanRequest(updated);
        PayloadInfo info = session.describe(updated.toString(), updated.parameters());
        expect(info.payload().contains("fresh"), "expected stale inference baseline reproduction");
        System.out.println("BASELINE: effective request unchanged by Scanner is inferred as payload='" + info.payload() + "', insertion='" + info.insertionPoint() + "'");
    }
    static void macroChecks() {
        Persistence persistence = persistence();
        try (ScanSessionStore store = new ScanSessionStore(persistence, ReviewProbes::memoryObject)) {
            LoginMacroStore profile = new LoginMacroStore(persistence, ReviewProbes::memoryObject);
            profile.addSteps(List.of(sample("/login"))); profile.setEnabled(true);
            long first = profile.snapshot().steps().get(0).id();
            profile.saveBinding(new LoginMacroStore.Binding(0, -1, LoginMacroStore.TargetLocation.HEADER, "Authorization", LoginMacroStore.SourceKind.RESPONSE_HEADER, first, "X-Missing-Token", ""));
            try (LoginMacroRunner runner = new LoginMacroRunner(profile, store, req -> exchange(req, response("HTTP/1.1 200 OK\r\n\r\n{}")))) {
                LoginMacroRunner.Result tested = runner.runForTest(p -> {}, new LoginMacroRunner.Cancellation());
                expect(tested.verification().successful(), "expected macro-only verification to pass");
                String failure = "NONE";
                try { runner.prepare(1, sample("/target"), p -> {}); } catch (IllegalStateException e) { failure = e.getMessage(); }
                expect(!failure.equals("NONE"), "expected real audit binding to fail");
                System.out.println("TEST_COVERAGE: Test macro PASSED; prepare(real request) failed: " + failure);
            }
            profile.removeBinding(profile.snapshot().bindings().get(0).id());
            profile.saveBinding(new LoginMacroStore.Binding(0, -1, LoginMacroStore.TargetLocation.HEADER, "Authorization", LoginMacroStore.SourceKind.RESPONSE_JSON, first, "$.access_token", ""));
            try (LoginMacroRunner runner = new LoginMacroRunner(profile, store, req -> exchange(req, response("HTTP/1.1 200 OK\r\n\r\n{}")))) {
                var result = runner.prepare(1, sample("/target"), p -> {});
                expect(result.verification().successful() && result.request().headerValue("Authorization").isEmpty(), "expected missing JSON to silently bind empty");
                System.out.println("MISSING_JSON: login verified=true, absent $.access_token replaced Authorization with empty string");
            }
            profile.removeBinding(profile.snapshot().bindings().get(0).id());
            profile.addSteps(List.of(sample("/next")));
            long second = profile.snapshot().steps().get(1).id();
            profile.saveBinding(new LoginMacroStore.Binding(0, second, LoginMacroStore.TargetLocation.HEADER, "Authorization", LoginMacroStore.SourceKind.RESPONSE_HEADER, first, "X-Missing", ""));
            AtomicInteger sent = new AtomicInteger();
            try (LoginMacroRunner runner = new LoginMacroRunner(profile, store, req -> { sent.incrementAndGet(); return exchange(req, response("HTTP/1.1 200 OK\r\n\r\n{}")); })) {
                boolean threw = false;
                try { runner.runForTest(p -> {}, new LoginMacroRunner.Cancellation()); }
                catch (IllegalStateException e) { threw = true; System.out.println("LOST_TRACE: after " + sent + " successful request(s), binding failure throws instead of returning step traces: " + e.getMessage()); }
                expect(threw && sent.get() == 1, "expected trace loss on pre-send binding failure");
            }
            profile.removeBinding(profile.snapshot().bindings().get(0).id());
            profile.moveStep(second, -1);
            profile.saveBinding(new LoginMacroStore.Binding(0, first, LoginMacroStore.TargetLocation.HEADER, "X-Test", LoginMacroStore.SourceKind.RESPONSE_HEADER, second, "X-Token", ""));
            try (LoginMacroRunner runner = new LoginMacroRunner(profile, store, req -> exchange(req, response("HTTP/1.1 200 OK\r\nX-Token: token\r\n\r\n")))) {
                var result = runner.runForTest(p -> {}, new LoginMacroRunner.Cancellation());
                System.out.println("STEP_LABEL: displayed Step 2 uses first step's response, but source text says '" + result.steps().get(1).appliedBindings().get(0).source() + "'");
            }
        }
    }
    static Object dummy(Class<?> type) {
        if (type == void.class) return null;
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (java.awt.Component.class.isAssignableFrom(type)) return new JPanel();
        if (type.isInterface()) return Proxy.newProxyInstance(ReviewProbes.class.getClassLoader(), new Class[]{type}, (p,m,a) -> {
            if (m.getName().equals("equals")) return p == a[0];
            if (m.getName().equals("hashCode")) return System.identityHashCode(p);
            if (m.getName().equals("toString")) return "UI test proxy";
            return dummy(m.getReturnType());
        });
        return null;
    }
    static void edt(Throwing action) throws Exception {
        AtomicReference<Throwable> failed = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> { try { action.run(); } catch (Throwable e) { failed.set(e); } });
        if (failed.get() != null) throw new RuntimeException(failed.get());
    }
    interface Throwing { void run() throws Exception; }
    static void filtered(ScanTimelinePanel panel) throws Exception {
        edt(() -> call(panel, "flushTrafficFilterRefresh"));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (((AtomicBoolean) field(panel, "filterWorkerRunning")).get()) {
            if (System.nanoTime() > deadline) throw new AssertionError("filter did not finish");
            Thread.sleep(5);
        }
        edt(() -> {});
    }
    static void pauseAndDelete() throws Exception {
        ScanSessionStore store = new ScanSessionStore(persistence(), ReviewProbes::memoryObject);
        ScanQueue queue = new ScanQueue(store, () -> null, (m,e) -> {}, false);
        AtomicReference<ScanTimelinePanel> ref = new AtomicReference<>();
        edt(() -> {
            ref.set(new ScanTimelinePanel((MontoyaApi) dummy(MontoyaApi.class), store, queue));
            for (String name : List.of("displayTimer", "trafficFilterTimer", "searchFilterDebounce")) ((javax.swing.Timer) field(ref.get(), name)).stop();
        });
        ScanTimelinePanel panel = ref.get();
        try {
            filtered(panel);
            edt(() -> field(panel, "displayPaused", true));
            long id = store.startCustomSession(sample("/paused"), null);
            store.recordRequest((HttpRequestToBeSent) fixture("outbound", sample("/paused").toString(), 901));
            edt(() -> { field(panel, "displayPaused", false); call(panel, "rebuildViewsFromStore"); });
            filtered(panel);
            ScanRequestTableModel model = (ScanRequestTableModel) field(panel, "allTrafficModel");
            Set<?> visible = (Set<?>) field(panel, "visibleEntryIds");
            expect(model.getRowCount() == 1 && visible.isEmpty(), "expected resumed row excluded by stale filter");
            System.out.println("PAUSE: resumed model rows=" + model.getRowCount() + ", matching rows=" + visible.size() + " (no filter conditions)");
            edt(() -> call(panel, "recomputeFilterInBackground")); filtered(panel);
            expect(((Set<?>) field(panel, "visibleEntryIds")).size() == 1, "full recompute should show row");
            expect(store.deleteSession(id), "delete must succeed");
            edt(() -> call(panel, "flushStoreEvents")); filtered(panel);
            expect(store.totalEntries() == 0 && model.getRowCount() == 1, "expected deleted row to remain in All traffic model");
            System.out.println("DELETE: store rows=" + store.totalEntries() + ", All traffic model rows=" + model.getRowCount() + ", matching rows=" + ((Set<?>) field(panel, "visibleEntryIds")).size());
        } finally { edt(panel::close); queue.close(); store.close(); }
    }
    public static void main(String[] args) throws Exception {
        effectiveBaseline(); macroChecks(); pauseAndDelete();
        System.out.println("Review probes completed; production sources were not changed.");
    }
}
