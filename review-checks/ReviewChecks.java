package com.scanviewer;

import burp.api.montoya.core.ToolType;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.handler.HttpRequestToBeSent;
import burp.api.montoya.http.handler.HttpResponseReceived;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.logging.Logging;
import burp.api.montoya.persistence.Persistence;
import burp.api.montoya.persistence.PersistedObject;
import burp.api.montoya.scanner.audit.Audit;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Isolated diagnostics, not part of extension sources. Reuses existing fake API fixtures. */
public final class ReviewChecks {
    static final Class<?> TEST = RegressionTests.class;
    static Object call(String name, Class<?>[] types, Object... args) {
        try { Method m = TEST.getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(null, args); }
        catch (ReflectiveOperationException ex) { throw new RuntimeException(ex); }
    }
    static HttpRequest req(String raw) { return (HttpRequest) call("request", new Class<?>[]{String.class}, raw); }
    static HttpResponse resp(String raw) { return (HttpResponse) call("response", new Class<?>[]{String.class}, raw); }
    static HttpRequestResponse exchange(HttpRequest request, HttpResponse response) {
        return (HttpRequestResponse) call("exchange", new Class<?>[]{HttpRequest.class, HttpResponse.class}, request, response);
    }
    static HttpRequestToBeSent outbound(String raw, int id) {
        return (HttpRequestToBeSent) call("outbound", new Class<?>[]{String.class, int.class}, raw, id);
    }
    static HttpResponseReceived inbound(String raw, int id) {
        return (HttpResponseReceived) call("inbound", new Class<?>[]{String.class, int.class}, raw, id);
    }
    static class Fixture implements AutoCloseable {
        final Persistence persistence;
        final PersistedObjectFactory factory;
        final ScanSessionStore store;
        Fixture() throws Exception {
            Class<?> memory = Class.forName("com.scanviewer.RegressionTests$MemoryPersistence");
            Constructor<?> ctor = memory.getDeclaredConstructor(); ctor.setAccessible(true);
            Object instance = ctor.newInstance();
            Method method = memory.getDeclaredMethod("persistence"); method.setAccessible(true);
            persistence = (Persistence) method.invoke(instance);
            Method create = Class.forName("com.scanviewer.RegressionTests$MemoryObject").getDeclaredMethod("newPersistedObject");
            create.setAccessible(true);
            factory = () -> { try { return (PersistedObject) create.invoke(null); } catch (Exception ex) { throw new RuntimeException(ex); } };
            store = new ScanSessionStore(persistence, factory);
        }
        public void close() { store.close(); }
    }
    static class FakeAudit {
        final AtomicReference<String> status = new AtomicReference<>("running");
        final AtomicInteger added = new AtomicInteger();
        final Audit audit = (Audit) Proxy.newProxyInstance(Audit.class.getClassLoader(), new Class<?>[]{Audit.class}, (p,m,a) -> {
            if (m.getName().equals("statusMessage")) return status.get();
            if (m.getName().equals("addRequest")) { added.incrementAndGet(); return null; }
            if (m.getName().equals("requestCount")) return added.get();
            if (m.getName().equals("issues")) return List.of();
            return call("primitiveDefault", new Class<?>[]{Class.class}, m.getReturnType());
        });
    }
    static String get(String path) { return "GET " + path + " HTTP/1.1\r\nHost: example.test\r\n\r\n"; }
    static String ok(int status) { return "HTTP/1.1 " + status + " Test\r\n\r\nbody"; }
    static void waitUntil(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        if (!condition.getAsBoolean()) throw new AssertionError("Diagnostic timed out");
    }
    public static void main(String[] args) throws Exception {
        corruptProfileSkips();
        staleRunCondition();
        testFlowAndQueueOverlap();
        unrelatedScanOwnership();
        emptyNonAuthorizationTokens();
        cleanupOnlyVerifiesLastStep();
        compareAuthReplacement();
        deleteHistoryBypassesDurableQueueRemoval();
        testExampleFailureKeepsPassedVerification();
    }
    static void corruptProfileSkips() throws Exception {
        try (Fixture f = new Fixture()) {
            DeleteMacroStore macros = new DeleteMacroStore(f.persistence, f.factory);
            macros.create("Cleanup", List.of(1L), List.of(req("DELETE /resource/1 HTTP/1.1\r\nHost: example.test\r\n\r\n")));
            f.persistence.extensionData().getChildObject("active-scan-timeline-delete-macro-v1-flow-1").setLong("schema_version", 999L);
            DeleteMacroStore restored = new DeleteMacroStore(f.persistence, f.factory);
            AtomicInteger cleanups = new AtomicInteger(); FakeAudit audit = new FakeAudit();
            DeleteMacroRunner runner = new DeleteMacroRunner(f.store, sent -> { cleanups.incrementAndGet(); return exchange(sent, resp(ok(204))); });
            try (ScanQueue queue = new ScanQueue(f.store, () -> audit.audit, (m,e) -> {}, null, restored, runner, false)) {
                queue.enqueue(List.of(new ScanQueue.Seed(req(get("/create")), resp(ok(201)))), true).join();
                audit.status.set("finished"); queue.pause().join();
                System.out.println("CORRUPT: catalogRecovery=" + restored.snapshot().recoveryRequired()
                        + "; profile=" + restored.flow(1).profile().snapshot().persistenceMessage()
                        + "; cleanupSends=" + cleanups.get() + "; queueState=" + f.store.session(1).queueState()
                        + "; queueStatus=" + f.store.session(1).status());
                if (f.store.session(1).queueState() != ScanQueueState.COMPLETED || cleanups.get() != 0) throw new AssertionError();
            }
        }
    }
    static void staleRunCondition() throws Exception {
        try (Fixture f = new Fixture()) {
            DeleteMacroStore macros = new DeleteMacroStore(f.persistence, f.factory);
            long id = macros.create("Cleanup", List.of(1L), List.of(req("DELETE /resource/1 HTTP/1.1\r\nHost: example.test\r\n\r\n")));
            macros.setCondition(id, new DeleteMacroCondition.Spec("201", "", "", "", "", false));
            AtomicInteger cleanups = new AtomicInteger(); FakeAudit audit = new FakeAudit();
            DeleteMacroRunner runner = new DeleteMacroRunner(f.store, sent -> { cleanups.incrementAndGet(); return exchange(sent, resp(ok(204))); });
            try (ScanQueue queue = new ScanQueue(f.store, () -> audit.audit, (m,e) -> {}, null, macros, runner, false)) {
                queue.enqueue(List.of(new ScanQueue.Seed(req(get("/create")), resp(ok(201)))), true).join();
                f.store.recordRequest(outbound(get("/create"), 10));
                f.store.recordResponse(inbound(ok(500), 10));
                System.out.println("CONDITION: savedStatus=201; capturedStatus=500; cleanupBeforeAuditComplete=" + cleanups.get());
                audit.status.set("finished"); queue.pause().join();
                waitUntil(() -> f.store.session(1).queueState() == ScanQueueState.COMPLETED);
                System.out.println("CONDITION: cleanupAfterAuditComplete=" + cleanups.get());
                if (cleanups.get() != 1) throw new AssertionError();
            }
        }
    }
    static void testFlowAndQueueOverlap() throws Exception {
        try (Fixture f = new Fixture()) {
            DeleteMacroStore macros = new DeleteMacroStore(f.persistence, f.factory);
            long id = macros.create("Cleanup", List.of(1L), List.of(req("DELETE /resource/1 HTTP/1.1\r\nHost: example.test\r\n\r\n")));
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1); FakeAudit audit = new FakeAudit();
            DeleteMacroRunner runner = new DeleteMacroRunner(f.store, sent -> {
                entered.countDown(); try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                return exchange(sent, resp(ok(204)));
            });
            try (ScanQueue queue = new ScanQueue(f.store, () -> audit.audit, (m,e) -> {}, null, macros, runner, false)) {
                queue.enqueue(List.of(new ScanQueue.Seed(req(get("/scan")), null)), false).join();
                CompletableFuture<LoginMacroRunner.Result> test = queue.testDeleteMacro(id);
                if (!entered.await(2, TimeUnit.SECONDS)) throw new AssertionError();
                queue.resume(false).join();
                System.out.println("TEST OVERLAP: testStillRunning=" + !test.isDone() + "; scannerDispatches=" + audit.added.get()
                        + "; scannerMacroRunning=" + queue.isScannerMacroRunning());
                if (audit.added.get() != 1 || test.isDone()) throw new AssertionError();
                release.countDown(); test.get(3, TimeUnit.SECONDS);
            } finally { release.countDown(); }
        }
    }
    static void unrelatedScanOwnership() throws Exception {
        try (Fixture f = new Fixture()) {
            f.store.startCustomSession(req(get("/owned")), resp(ok(200)));
            HttpService other = (HttpService) call("service", new Class<?>[]{String.class,int.class,boolean.class}, "elsewhere.test",443,true);
            HttpRequestToBeSent external = (HttpRequestToBeSent) call("outbound", new Class<?>[]{String.class,int.class,ToolType.class,HttpService.class},
                    "GET /external HTTP/1.1\r\nHost: elsewhere.test\r\n\r\n", 11, ToolType.SCANNER, other);
            Logging log = (Logging) Proxy.newProxyInstance(Logging.class.getClassLoader(), new Class<?>[]{Logging.class}, (p,m,a) -> null);
            new ScannerTrafficHandler(f.store, log).prepareRequest(external);
            System.out.println("OWNERSHIP: unrelatedHost=" + external.httpService().host() + "; capturedSession=" + f.store.allEntries().get(0).sessionId());
            if (!Long.valueOf(1).equals(f.store.allEntries().get(0).sessionId())) throw new AssertionError();
        }
    }
    static void emptyNonAuthorizationTokens() throws Exception {
        for (LoginMacroStore.TargetLocation location : List.of(LoginMacroStore.TargetLocation.HEADER, LoginMacroStore.TargetLocation.COOKIE_PARAMETER)) {
            try (Fixture f = new Fixture()) {
                LoginMacroStore profile = new LoginMacroStore(f.persistence, f.factory);
                profile.addSteps(List.of(req(get("/login"))));
                profile.saveBinding(new LoginMacroStore.Binding(0, LoginMacroStore.AUDIT_REQUEST, location,
                        location == LoginMacroStore.TargetLocation.HEADER ? "X-Access-Token" : "session",
                        LoginMacroStore.SourceKind.RESPONSE_JSON, profile.snapshot().steps().get(0).id(), "$.token", ""));
                profile.setEnabled(true);
                HttpRequest base = req("GET /auth HTTP/1.1\r\nHost: example.test\r\nX-Access-Token: old\r\nCookie: session=old\r\n\r\n");
                long session = f.store.queueSession(base, null);
                try (LoginMacroRunner runner = new LoginMacroRunner(profile, f.store,
                        sent -> exchange(sent, resp("HTTP/1.1 200 OK\r\n\r\n{\"token\":\"\"}")))) {
                    LoginMacroRunner.Result result = runner.prepare(session, base, ignored -> {});
                    System.out.println("EMPTY TOKEN: target=" + location + "; verified=" + result.verification().successful()
                            + "; header=" + result.request().headerValue("X-Access-Token")
                            + "; cookie=" + result.request().headerValue("Cookie"));
                }
            }
        }
    }
    static void cleanupOnlyVerifiesLastStep() throws Exception {
        try (Fixture f = new Fixture()) {
            DeleteMacroStore macros = new DeleteMacroStore(f.persistence, f.factory);
            long id = macros.create("Delete two resources", List.of(1L), List.of(
                    req("DELETE /first HTTP/1.1\r\nHost: example.test\r\n\r\n"),
                    req("DELETE /second HTTP/1.1\r\nHost: example.test\r\n\r\n")));
            AtomicInteger sentCount = new AtomicInteger();
            DeleteMacroRunner runner = new DeleteMacroRunner(f.store,
                    sent -> exchange(sent, resp(ok(sentCount.incrementAndGet() == 1 ? 500 : 204))));
            LoginMacroRunner.Result result = runner.run(1L, macros.flow(id), ignored -> {}, new LoginMacroRunner.Cancellation());
            System.out.println("CLEANUP STEP: firstStatus=500; lastStatus=204; sends=" + sentCount.get()
                    + "; verification=" + result.verification().successful() + "; message=" + result.verification().message());
            if (!result.verification().successful() || sentCount.get() != 2) throw new AssertionError();
        }
    }
    static void compareAuthReplacement() throws Exception {
        try (Fixture f = new Fixture()) {
            String original = "GET /target HTTP/1.1\r\nHost: example.test\r\nAuthorization: Bearer old\r\n\r\n";
            String effective = original.replace("Bearer old", "Bearer fresh");
            f.store.startCustomSession(req(original), resp(ok(200)));
            f.store.recordRequest(outbound(original, 12), req(effective), 1L, null);
            ScanRequestEntry.Snapshot entry = f.store.allEntries().get(0);
            System.out.println("AUTH COMPARE: payload=" + entry.payload() + "; diff="
                    + RequestComparison.compare(f.store.effectiveScanRequestForAnalysis(1), entry.request()).changes());
        }
    }
    static void deleteHistoryBypassesDurableQueueRemoval() throws Exception {
        try (Fixture f = new Fixture()) {
            f.store.queueSession(req(get("/queued")), null);
            Method save = ScanSessionStore.class.getDeclaredMethod("persistNow"); save.setAccessible(true);
            if (!Boolean.TRUE.equals(save.invoke(f.store))) throw new AssertionError();
            PersistedObject sessionRoot = f.persistence.extensionData().getChildObject("active-scan-timeline-v2").getChildObject("sessions");
            Object savedSessions = Proxy.getInvocationHandler(sessionRoot);
            Field fail = savedSessions.getClass().getDeclaredField("failChildDeletes"); fail.setAccessible(true); fail.setBoolean(savedSessions, true);
            boolean deleted = f.store.deleteSession(1);
            boolean persisted = (Boolean) save.invoke(f.store);
            f.store.close();
            fail.setBoolean(savedSessions, false);
            try (ScanSessionStore restored = new ScanSessionStore(f.persistence, f.factory)) {
                System.out.println("HISTORY DELETE: reportedDeleted=" + deleted + "; persisted=" + persisted
                        + "; restoredQueued=" + restored.queueSessions().size());
                if (!deleted || persisted || restored.queueSessions().size() != 1) throw new AssertionError();
            }
        }
    }
    static void testExampleFailureKeepsPassedVerification() throws Exception {
        try (Fixture f = new Fixture()) {
            LoginMacroStore profile = new LoginMacroStore(f.persistence, f.factory);
            profile.addSteps(List.of(req(get("/login"))));
            try (LoginMacroRunner runner = new LoginMacroRunner(profile, f.store, sent -> {
                if (sent.path().equals("/login")) return exchange(sent, resp(ok(200)));
                throw new IllegalStateException("simulated example transport failure");
            })) {
                LoginMacroRunner.Result result = runner.runForTest(req(get("/example")), ignored -> {}, new LoginMacroRunner.Cancellation());
                System.out.println("TEST EXAMPLE: topLevelPassed=" + result.verification().successful()
                        + "; exampleState=" + result.exampleTrace().state() + "; exampleError=" + result.exampleTrace().error());
                if (!result.verification().successful() || result.exampleTrace().state() != LoginMacroRunner.StepState.REQUEST_FAILED) throw new AssertionError();
            }
        }
    }
}
