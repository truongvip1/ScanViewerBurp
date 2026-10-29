package com.scanviewer;

import burp.api.montoya.http.handler.HttpRequestToBeSent;
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
import java.util.function.*;

/** Read-only product review: all traffic/persistence is simulated using existing test fixtures. */
public final class TimedRefreshReview {
    private static final Class<?> TESTS = RegressionTests.class;
    private static final String SEED = "GET /target HTTP/1.1\r\nHost: example.test\r\nAuthorization: Bearer original\r\n\r\n";

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("extraction")) { repeatedBindingExtraction(); return; }
        sameSettingsStopsTimer();
        refreshFailureRecovery();
        initialLoginProfileChange();
        payloadAndDisableTimer();
        cancellationDuringPublication();
        pendingLoginAge();
        repeatedBindingExtraction();
    }

    static void sameSettingsStopsTimer() throws Exception {
        try (Fixture f = new Fixture(1_000, TimedRefreshReview::ok)) {
            f.start();
            long revision = f.profile.snapshot().revision();
            f.profile.setSettings(f.profile.snapshot().settings());
            Thread.sleep(1_150);
            int beforeProbe = f.sends.get();
            ScannerTrafficHandler.PreparedRequest probe = f.probe(f.audit.requests.get(0).toString(), 901);
            System.out.println("UNCHANGED_SETTINGS: revision " + revision + " -> " + f.profile.snapshot().revision()
                    + "; logins after timer deadline=" + beforeProbe + "; next probe dropped=" + probe.dropped()
                    + "; blocked=" + f.runner.scannerMacro().isBlocked());
        }
    }

    static void refreshFailureRecovery() throws Exception {
        try (Fixture f = new Fixture(1_000, (n, request) -> n == 1 ? ok(n, request)
                : exchange(request, response("HTTP/1.1 401 Unauthorized\r\n\r\n")))) {
            f.start();
            await(() -> f.runner.scannerMacro().isBlocked(), "scheduled failure");
            f.audit.status = "finished";
            f.queue.pause().join();
            ScanQueueState ended = f.store.session(1).queueState();
            f.queue.retryAsNew(1).join();
            f.queue.resume(false).join();
            System.out.println("FAILURE_RECOVERY: ended queue state=" + ended + "; Retry + Resume paused="
                    + f.queue.snapshot().paused() + "; waiting=" + f.queue.snapshot().waitingCount()
                    + "; audit submissions=" + f.audit.requests.size() + "; logins=" + f.sends.get());
        }
    }

    static void initialLoginProfileChange() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (Fixture f = new Fixture(60_000, (n, request) -> {
            entered.countDown(); awaitLatch(release); return ok(n, request);
        })) {
            f.queue.enqueue(List.of(new ScanQueue.Seed(request(SEED), null)), true).join();
            awaitLatch(entered);
            f.profile.setSettings(new LoginMacroStore.Settings(5_000,
                    new LoginMacroStore.SuccessCondition("200-399", "", "X-Required", "", 0), false, 60_000));
            release.countDown();
            await(() -> f.audit.requests.size() == 1, "audit dispatch after profile edit");
            ScannerTrafficHandler.PreparedRequest probe = f.probe(f.audit.requests.get(0).toString(), 902);
            System.out.println("EDIT_DURING_INITIAL_LOGIN: required header=X-Required (absent in response); audit submissions="
                    + f.audit.requests.size() + "; next probe dropped=" + probe.dropped()
                    + "; token=" + probe.request().headerValue("Authorization"));
        } finally { release.countDown(); }
    }

    static void payloadAndDisableTimer() throws Exception {
        try (Fixture f = new Fixture(1_000, TimedRefreshReview::ok)) {
            f.start();
            await(() -> f.sends.get() >= 2 && !f.runner.scannerMacro().isRunning(), "second login");
            String unchangedScannerBase = f.audit.requests.get(0).toString();
            ScannerTrafficHandler.PreparedRequest probe = f.probe(unchangedScannerBase, 903);
            ScanRequestEntry.Snapshot entry = f.store.sessionEntries(1).stream().max(Comparator.comparingLong(ScanRequestEntry.Snapshot::id)).orElseThrow();
            System.out.println("AUTH_AS_PAYLOAD: Scanner request unchanged from audit seed; sent token="
                    + probe.request().headerValue("Authorization") + "; payload=" + entry.payload()
                    + "; insertion=" + entry.insertionPoint() + "; login trace ids=" + entry.loginTraceEntryIds());
            LoginMacroStore.Settings prior = f.profile.snapshot().settings();
            f.profile.setSettings(new LoginMacroStore.Settings(prior.stepTimeoutMillis(), prior.success(), prior.cookieJarEnabled(), 0));
            ScannerTrafficHandler.PreparedRequest afterDisable = f.probe(unchangedScannerBase, 904);
            System.out.println("DISABLE_TIMER: latest session=Bearer token-2; next sent token="
                    + afterDisable.request().headerValue("Authorization") + "; dropped=" + afterDisable.dropped());
        }
    }

    static void cancellationDuringPublication() throws Exception {
        CountDownLatch updating = new CountDownLatch(1), release = new CountDownLatch(1);
        try (Fixture f = new Fixture(1_000, TimedRefreshReview::ok)) {
            f.store.addListener(event -> {
                if (event.kind() == ScanSessionStore.StoreEventKind.SESSION_UPDATED && f.sends.get() == 2
                        && Thread.currentThread().getName().equals("audit-timeline-periodic-login")) {
                    updating.countDown(); awaitLatch(release);
                }
            });
            f.start();
            awaitLatch(updating);
            f.runner.scannerMacro().clearSession(1);
            boolean cleared = field(f.runner.scannerMacro(), "timedSession") == null;
            release.countDown();
            await(() -> !f.runner.scannerMacro().isRunning(), "refresh exit");
            boolean resurrected = field(f.runner.scannerMacro(), "timedSession") != null;
            await(() -> f.sends.get() >= 3, "unexpected login after clearSession");
            System.out.println("CLEAR_DURING_PUBLICATION: initially cleared=" + cleared
                    + "; state restored after cancellation=" + resurrected + "; logins after clear=" + f.sends.get());
        } finally { release.countDown(); }
    }

    static void pendingLoginAge() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicLong completedAt = new AtomicLong();
        try (Fixture f = new Fixture(1_000, (n, request) -> {
            entered.countDown(); awaitLatch(release);
            completedAt.set(System.nanoTime()); return ok(n, request);
        })) {
            f.queue.enqueue(List.of(new ScanQueue.Seed(request(SEED), null)), true).join();
            awaitLatch(entered); f.queue.pause().join(); release.countDown();
            await(() -> f.queue.snapshot().notice().contains("paused before Scanner dispatch"), "paused after login");
            Thread.sleep(700);
            f.queue.resume(false).join();
            await(() -> f.audit.requests.size() == 1, "resumed audit");
            Thread.sleep(450);
            ScannerTrafficHandler.PreparedRequest probe = f.probe(f.audit.requests.get(0).toString(), 905);
            System.out.println("LOGIN_AGE: configured interval=1000ms; login age="
                    + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - completedAt.get())
                    + "ms; logins=" + f.sends.get() + "; sent token=" + probe.request().headerValue("Authorization"));
        } finally { release.countDown(); }
    }

    static void repeatedBindingExtraction() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        try (Fixture f = new Fixture(60_000, (n, request) -> {
            HttpResponse underlying = response("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{\"access_token\":\"token-" + n + "\",\"padding\":\"" + "x".repeat(100_000) + "\"}");
            HttpResponse tracked = (HttpResponse) Proxy.newProxyInstance(TESTS.getClassLoader(), new Class<?>[]{HttpResponse.class}, (p,m,a) -> {
                if (m.getName().equals("bodyToString")) reads.incrementAndGet();
                return m.invoke(underlying, a);
            });
            return exchange(request, tracked);
        })) {
            LoginMacroStore.Binding binding = f.profile.snapshot().bindings().get(0);
            f.profile.saveBinding(new LoginMacroStore.Binding(binding.id(), binding.targetStepId(), binding.targetLocation(),
                    binding.targetName(), LoginMacroStore.SourceKind.RESPONSE_JSON, binding.sourceStepId(), "$.access_token", ""));
            f.start();
            int before = reads.get();
            for (int n = 0; n < 25; n++) f.runner.scannerMacro().prepare(f.audit.requests.get(0), 1L, true);
            System.out.println("REPEATED_EXTRACTION: 25 probes, same successful login; JSON response reads=" + (reads.get() - before));
        }
    }

    static class Fixture implements AutoCloseable {
        final AtomicInteger sends = new AtomicInteger();
        final ScanSessionStore store;
        final LoginMacroStore profile;
        final LoginMacroRunner runner;
        final AuditStub audit = new AuditStub();
        final ScanQueue queue;
        final ScannerTrafficHandler handler;
        Fixture(long interval, BiFunction<Integer, HttpRequest, HttpRequestResponse> sender) {
            Object memory = construct("com.scanviewer.RegressionTests$MemoryPersistence");
            Persistence persistence = (Persistence) invoke(memory.getClass(), memory, "persistence", new Class<?>[0]);
            store = new ScanSessionStore(persistence, TimedRefreshReview::object);
            profile = new LoginMacroStore(persistence, TimedRefreshReview::object);
            profile.addSteps(List.of(request("GET /login HTTP/1.1\r\nHost: example.test\r\n\r\n")));
            profile.saveBinding(new LoginMacroStore.Binding(0, LoginMacroStore.AUDIT_REQUEST,
                    LoginMacroStore.TargetLocation.HEADER, "Authorization", LoginMacroStore.SourceKind.RESPONSE_HEADER,
                    profile.snapshot().steps().get(0).id(), "X-Token", ""));
            profile.setSettings(new LoginMacroStore.Settings(5_000, LoginMacroStore.SuccessCondition.defaults(), false, interval));
            profile.setEnabled(true);
            runner = new LoginMacroRunner(profile, store, request -> sender.apply(sends.incrementAndGet(), request));
            queue = new ScanQueue(store, () -> audit.proxy, (message, error) -> { }, runner, false);
            Logging logs = (Logging) Proxy.newProxyInstance(TESTS.getClassLoader(), new Class<?>[]{Logging.class}, (p,m,a) -> null);
            handler = new ScannerTrafficHandler(store, logs, runner.scannerMacro());
        }
        void start() throws Exception {
            queue.enqueue(List.of(new ScanQueue.Seed(request(SEED), null)), true).join();
            await(() -> audit.requests.size() == 1, "initial login and dispatch");
        }
        ScannerTrafficHandler.PreparedRequest probe(String raw, int id) {
            HttpRequestToBeSent request = (HttpRequestToBeSent) invoke(TESTS, null, "outbound", new Class<?>[]{String.class, int.class}, raw, id);
            return handler.prepareRequest(request);
        }
        @Override public void close() { queue.close(); store.close(); }
    }

    static class AuditStub {
        volatile String status = "running";
        final List<HttpRequest> requests = new CopyOnWriteArrayList<>();
        final Audit proxy = (Audit) Proxy.newProxyInstance(TESTS.getClassLoader(), new Class<?>[]{Audit.class}, (p,m,a) -> switch (m.getName()) {
            case "statusMessage" -> status;
            case "addRequest" -> { requests.add((HttpRequest) a[0]); yield null; }
            case "requestCount" -> requests.size();
            case "issues" -> List.of();
            default -> invoke(TESTS, null, "primitiveDefault", new Class<?>[]{Class.class}, m.getReturnType());
        });
    }
    static HttpRequestResponse ok(int n, HttpRequest request) {
        return exchange(request, response("HTTP/1.1 200 OK\r\nX-Token: token-" + n + "\r\n\r\n"));
    }
    static HttpRequest request(String raw) { return (HttpRequest) invoke(TESTS, null, "request", new Class<?>[]{String.class}, raw); }
    static HttpResponse response(String raw) { return (HttpResponse) invoke(TESTS, null, "response", new Class<?>[]{String.class}, raw); }
    static HttpRequestResponse exchange(HttpRequest request, HttpResponse response) {
        return (HttpRequestResponse) invoke(TESTS, null, "exchange", new Class<?>[]{HttpRequest.class, HttpResponse.class}, request, response);
    }
    static PersistedObject object() {
        try { return (PersistedObject) invoke(Class.forName("com.scanviewer.RegressionTests$MemoryObject"), null, "newPersistedObject", new Class<?>[0]); }
        catch (ClassNotFoundException e) { throw new RuntimeException(e); }
    }
    static Object construct(String name) {
        try { Constructor<?> c = Class.forName(name).getDeclaredConstructor(); c.setAccessible(true); return c.newInstance(); }
        catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
    }
    static Object invoke(Class<?> type, Object receiver, String name, Class<?>[] types, Object... args) {
        try { Method m = type.getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(receiver, args); }
        catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
    }
    static Object field(Object receiver, String name) {
        try { Field f = receiver.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(receiver); }
        catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
    }
    static void await(BooleanSupplier check, String label) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (!check.getAsBoolean() && System.nanoTime() < until) Thread.sleep(5);
        if (!check.getAsBoolean()) throw new AssertionError("Review fixture timed out: " + label);
    }
    static void awaitLatch(CountDownLatch latch) {
        try { if (!latch.await(4, TimeUnit.SECONDS)) throw new AssertionError("Review latch timed out."); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
    }
}
