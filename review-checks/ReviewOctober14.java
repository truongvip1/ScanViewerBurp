package com.scanviewer;

import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.http.handler.HttpRequestToBeSent;
import burp.api.montoya.http.handler.HttpResponseReceived;
import burp.api.montoya.persistence.Persistence;
import burp.api.montoya.persistence.PersistedObject;
import burp.api.montoya.scanner.audit.Audit;
import java.lang.reflect.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Review-only fault injection. Uses simulated Montoya objects and never sends real traffic. */
public final class ReviewOctober14 {
    static Object helper(String name, Class<?>[] types, Object... args) {
        try { Method m = RegressionTests.class.getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(null, args); }
        catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
    }
    static Object instance(String suffix) {
        try { Constructor<?> c = Class.forName("com.scanviewer.RegressionTests$" + suffix).getDeclaredConstructor(); c.setAccessible(true); return c.newInstance(); }
        catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
    }
    static Object field(Object object, String name) {
        try { Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object); }
        catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
    }
    static Persistence persistence(Object object) {
        try { Method m = object.getClass().getDeclaredMethod("persistence"); m.setAccessible(true); return (Persistence)m.invoke(object); }
        catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
    }
    static PersistedObject newObject() {
        try { Method m = Class.forName("com.scanviewer.RegressionTests$MemoryObject").getDeclaredMethod("newPersistedObject"); m.setAccessible(true); return (PersistedObject)m.invoke(null); }
        catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
    }
    static HttpRequest req(String raw) { return (HttpRequest) helper("request", new Class<?>[]{String.class}, raw); }
    static HttpResponse res(String raw) { return (HttpResponse) helper("response", new Class<?>[]{String.class}, raw); }
    static HttpRequestResponse exchange(HttpRequest request, HttpResponse response) { return (HttpRequestResponse)helper("exchange", new Class<?>[]{HttpRequest.class, HttpResponse.class}, request, response); }
    static HttpRequestToBeSent outbound(String raw, int id) { return (HttpRequestToBeSent)helper("outbound", new Class<?>[]{String.class, int.class}, raw, id); }
    static HttpResponseReceived inbound(String raw, int id) { return (HttpResponseReceived)helper("inbound", new Class<?>[]{String.class, int.class}, raw, id); }
    static Audit audit() { return (Audit) field(instance("TestAudit"), "proxy"); }
    static final String GET = "GET /item HTTP/1.1\r\nHost: example.test\r\nAuthorization: Bearer old\r\nCookie: session=old\r\n\r\n";
    static final String LOGIN = "POST /login HTTP/1.1\r\nHost: example.test\r\n\r\n";
    static final String DELETE = "DELETE /item HTTP/1.1\r\nHost: example.test\r\n\r\n";
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        testDeleteTestOverlap();
        testMissingProfile();
        testEmptyAuth();
        testExampleFailureStillPassesVerification();
        testRestoreFailureNotLatched();
        System.out.println("JSON_SWAPPED_VALUES: similarity=" + ResponseMetrics.similarityPercent("{\"id\":1,\"isAdmin\":0}", "{\"id\":0,\"isAdmin\":1}") + "%");
        System.out.println("Review-only reproductions completed; production sources unchanged.");
    }
    static void testDeleteTestOverlap() throws Exception {
        Persistence p = persistence(instance("MemoryPersistence"));
        ScanSessionStore store = new ScanSessionStore(p, ReviewOctober14::newObject);
        DeleteMacroStore flows = new DeleteMacroStore(p, ReviewOctober14::newObject);
        long flow = flows.create("Overlap", List.of(1L), List.of(req(DELETE)));
        CountDownLatch sending = new CountDownLatch(1), release = new CountDownLatch(1);
        DeleteMacroRunner runner = new DeleteMacroRunner(store, request -> {
            sending.countDown();
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return exchange(request, res("HTTP/1.1 204 No Content\r\n\r\n"));
        });
        AtomicInteger audits = new AtomicInteger();
        ScanQueue queue = new ScanQueue(store, () -> { audits.incrementAndGet(); return audit(); }, (message, error) -> {}, null, flows, runner, false);
        try {
            queue.enqueue(List.of(new ScanQueue.Seed(req(GET), null)), false).join();
            CompletableFuture<LoginMacroRunner.Result> testing = queue.testDeleteMacro(flow);
            check(sending.await(2, TimeUnit.SECONDS), "Delete test did not start");
            queue.resume(false).get(2, TimeUnit.SECONDS);
            check(audits.get() == 1 && !testing.isDone(), "Expected audit/test overlap not observed");
            boolean cancelRejected = false;
            try { queue.cancelMacro().get(2, TimeUnit.SECONDS); } catch (ExecutionException expected) { cancelRejected = true; }
            System.out.println("DELETE_TEST_OVERLAP: auditStarted=" + audits.get() + ", testStillRunning=" + !testing.isDone() + ", cancelRejected=" + cancelRejected);
            release.countDown(); testing.get(2, TimeUnit.SECONDS);
        } finally { release.countDown(); queue.close(); store.close(); }
    }
    static void testMissingProfile() {
        Persistence p = persistence(instance("MemoryPersistence"));
        ScanSessionStore store = new ScanSessionStore(p, ReviewOctober14::newObject);
        DeleteMacroStore initial = new DeleteMacroStore(p, ReviewOctober14::newObject);
        initial.create("Missing", List.of(1L), List.of(req(DELETE)));
        p.extensionData().deleteChildObject("active-scan-timeline-delete-macro-v1-flow-1");
        DeleteMacroStore restored = new DeleteMacroStore(p, ReviewOctober14::newObject);
        AtomicInteger sent = new AtomicInteger();
        ScannerCleanupGate gate = new ScannerCleanupGate(store, restored, new DeleteMacroRunner(store, request -> { sent.incrementAndGet(); return exchange(request, res("HTTP/1.1 204 No Content\r\n\r\n")); }));
        try {
            helper("activateQueueTarget", new Class<?>[]{ScanSessionStore.class, HttpRequest.class}, store, req(GET));
            store.recordRequest(outbound(GET, 910)); gate.captureResponse(() -> store.recordResponse(inbound("HTTP/1.1 201 Created\r\n\r\n", 910)));
            String outcome = store.allEntries().get(0).cleanupStatus();
            check(gate.failure(1).isBlank() && outcome.contains("empty draft"), "Missing-root skip not observed");
            System.out.println("MISSING_PROFILE: catalogRecovery=" + restored.snapshot().recoveryRequired() + ", failure='" + gate.failure(1) + "', outcome='" + outcome + "'");
        } finally { gate.close(); store.close(); }
    }
    static void testEmptyAuth() {
        for (boolean cookie : new boolean[]{false, true}) {
            Persistence p = persistence(instance("MemoryPersistence"));
            ScanSessionStore store = new ScanSessionStore(p, ReviewOctober14::newObject);
            LoginMacroStore profile = new LoginMacroStore(p, ReviewOctober14::newObject);
            profile.addSteps(List.of(req(LOGIN)));
            profile.saveBinding(new LoginMacroStore.Binding(0, LoginMacroStore.AUDIT_REQUEST,
                    cookie ? LoginMacroStore.TargetLocation.COOKIE_PARAMETER : LoginMacroStore.TargetLocation.HEADER,
                    cookie ? "session" : "Authorization", cookie ? LoginMacroStore.SourceKind.RESPONSE_COOKIE : LoginMacroStore.SourceKind.RESPONSE_JSON,
                    1, cookie ? "session" : "$.token", ""));
            profile.setEnabled(true);
            LoginMacroRunner runner = new LoginMacroRunner(profile, store, request -> exchange(request, res(cookie
                    ? "HTTP/1.1 200 OK\r\nSet-Cookie: session=; Path=/\r\n\r\n"
                    : "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{\"token\":\"Bearer  \"}")));
            try {
                long id = store.startCustomSession(req(GET), null);
                LoginMacroRunner.Result result = runner.prepare(id, req(GET), progress -> {});
                String value = result.request().headerValue(cookie ? "Cookie" : "Authorization");
                check(result.verification().successful(), "Empty authentication rejection already implemented");
                System.out.println((cookie ? "EMPTY_COOKIE" : "EMPTY_BEARER_CREDENTIAL") + ": passed=" + result.verification().successful() + ", sentHeader='" + value + "'");
            } finally { runner.close(); store.close(); }
        }
    }
    static void testRestoreFailureNotLatched() throws Exception {
        Persistence p = persistence(instance("MemoryPersistence"));
        ScanSessionStore initial = new ScanSessionStore(p, ReviewOctober14::newObject);
        initial.queueSession(req(GET), null); initial.close();
        p.extensionData().getChildObject("active-scan-timeline-v2").setString("clear_watermark", "broken");
        ScanSessionStore restored = new ScanSessionStore(p, ReviewOctober14::newObject);
        AtomicInteger audits = new AtomicInteger();
        ScanQueue queue = new ScanQueue(restored, () -> { audits.incrementAndGet(); return audit(); }, (message, error) -> {}, false);
        try {
            ScanSessionStore.PersistenceState before = restored.persistence().state();
            queue.enqueue(List.of(new ScanQueue.Seed(req(GET), null)), true).get(2, TimeUnit.SECONDS);
            check(before == ScanSessionStore.PersistenceState.FAILED && audits.get() == 1, "Restore failure did not permit dispatch as expected");
            queue.close(); restored.close();
            ScanSessionStore again = new ScanSessionStore(p, ReviewOctober14::newObject);
            try { System.out.println("RESTORE_FAILURE: before=" + before + ", dispatched=" + audits.get() + ", afterReloadSessions=" + again.sessions().size() + ", state=" + again.persistence().state()); }
            finally { again.close(); }
        } finally { queue.close(); restored.close(); }
    }
    static void testExampleFailureStillPassesVerification() {
        Persistence p = persistence(instance("MemoryPersistence"));
        ScanSessionStore store = new ScanSessionStore(p, ReviewOctober14::newObject);
        LoginMacroStore profile = new LoginMacroStore(p, ReviewOctober14::newObject);
        profile.addSteps(List.of(req(LOGIN)));
        LoginMacroRunner runner = new LoginMacroRunner(profile, store, request -> {
            if (request.method().equals("GET")) throw new IllegalStateException("simulated example transport failure");
            return exchange(request, res("HTTP/1.1 200 OK\r\n\r\n"));
        });
        try {
            LoginMacroRunner.Result result = runner.runForTest(req(GET), progress -> {}, new LoginMacroRunner.Cancellation());
            check(result.verification().successful() && result.exampleTrace().state() == LoginMacroRunner.StepState.REQUEST_FAILED, "Expected mixed test result not observed");
            System.out.println("EXAMPLE_FAILURE_BANNER: macroVerification=" + result.verification().successful() + ", example=" + result.exampleTrace().state() + ", bannerPassedCondition=" + (result.verification() != null && result.verification().successful()));
        } finally { runner.close(); store.close(); }
    }
}
