package com.scanviewer;

import burp.api.montoya.http.handler.*;
import burp.api.montoya.http.message.*;
import burp.api.montoya.http.message.requests.*;
import burp.api.montoya.http.message.responses.*;
import burp.api.montoya.logging.Logging;
import burp.api.montoya.persistence.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Offline review probes. No network operations and no production-source edits. */
public class CaptureReview {
    static final String RAW = "GET /review HTTP/1.1\r\nHost: example.test\r\n\r\n";
    static final String RES = "HTTP/1.1 200 OK\r\n\r\nreview";
    static Object call(Class<?> type, Object receiver, String name, Class<?>[] types, Object... args) {
        try { Method m = type.getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(receiver, args); }
        catch (InvocationTargetException e) { throw new RuntimeException(e.getCause()); }
        catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
    }
    static Object fixture(String name, Class<?>[] types, Object... args) { return call(RegressionTests.class, null, name, types, args); }
    static Object construct(String name) {
        try { Constructor<?> c = Class.forName("com.scanviewer.RegressionTests$" + name).getDeclaredConstructor(); c.setAccessible(true); return c.newInstance(); }
        catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
    }
    static Persistence memory() { Object m = construct("MemoryPersistence"); return (Persistence)call(m.getClass(), m, "persistence", new Class<?>[0]); }
    static PersistedObject object() { Object m = construct("MemoryObject"); return (PersistedObject)call(m.getClass(), m, "proxy", new Class<?>[0]); }
    static ScanSessionStore store() { return new ScanSessionStore(memory(), CaptureReview::object); }
    static HttpRequest req() { return (HttpRequest)fixture("request", new Class<?>[]{String.class}, RAW); }
    static HttpResponse res() { return (HttpResponse)fixture("response", new Class<?>[]{String.class}, RES); }
    static HttpRequestToBeSent out(int id) { return (HttpRequestToBeSent)fixture("outbound", new Class<?>[]{String.class,int.class}, RAW,id); }
    static HttpResponseReceived in(int id) { return (HttpResponseReceived)fixture("inbound", new Class<?>[]{String.class,int.class},RES,id); }
    static <T> T proxy(Class<T> type, InvocationHandler handler) { return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},handler)); }
    static Object delegate(Object target, Method m, Object[] args) throws Throwable {
        try { return m.invoke(target,args); } catch (InvocationTargetException e) { throw e.getCause(); }
    }
    static void delay(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); } }
    public static void main(String[] args) throws Exception {
        Object original = RegressionTests.installTestMontoyaFactory();
        try { tempFailure(); orphan(); staleModel(); storageDuration(); cancelledMacro(); }
        finally { RegressionTests.restoreTestMontoyaFactory(original); }
    }
    static void tempFailure() {
        try (ScanSessionStore s = store()) {
            AtomicInteger errors = new AtomicInteger();
            Logging log = proxy(Logging.class,(p,m,a)->{ if(m.getName().equals("logToError")) errors.incrementAndGet(); return null; });
            HttpRequestToBeSent original = out(90001);
            HttpRequestToBeSent broken = proxy(HttpRequestToBeSent.class,(p,m,a)->{
                if(m.getName().equals("copyToTempFile")) throw new IllegalStateException("injected disk error");
                return delegate(original,m,a);
            });
            ScannerTrafficHandler.PreparedRequest decision = new ScannerTrafficHandler(s,log).prepareRequest(broken);
            Object response = s.recordResponse(in(90001));
            System.out.println("TEMP_FAILURE: allowed="+!decision.dropped()+", rows="+s.totalEntries()+", pairedResponse="+(response!=null)+", errors="+errors);
        }
    }
    static void orphan() {
        try (ScanSessionStore s = store()) {
            HttpResponseReceived original = in(90002);
            HttpResponseReceived response = proxy(HttpResponseReceived.class,(p,m,a)->m.getName().equals("initiatingRequest")?req():delegate(original,m,a));
            Object captured=s.recordResponse(response);
            System.out.println("ORPHAN_RESPONSE: captured="+(captured!=null)+", rows="+s.totalEntries()+", initiatingRequestAvailable=true");
        }
    }
    static void staleModel() {
        ScanRequestEntry e = new ScanRequestEntry(1,null,System.currentTimeMillis(),req(),"GET /review",PayloadInfo.BASELINE);
        ScanRequestEntry.Snapshot old=e.snapshot(); e.complete(res(),1,null);
        ScanRequestTableModel model=new ScanRequestTableModel(); model.add(old); model.update(e.snapshot()); model.update(old);
        System.out.println("REORDERED_EVENTS: storeResponse="+(e.snapshot().response()!=null)+", tableResponse="+(model.entryAt(0).response()!=null)+", tableRevision="+model.entryAt(0).revision());
    }
    static void storageDuration() {
        try (ScanSessionStore s=store()) {
            s.recordRequest(out(90003));
            HttpResponseReceived original=in(90003);
            HttpResponseReceived delayed=proxy(HttpResponseReceived.class,(p,m,a)->{ if(m.getName().equals("copyToTempFile")) delay(250); return delegate(original,m,a); });
            ScanRequestEntry.Snapshot row=s.recordResponse(delayed);
            System.out.println("STORAGE_DURATION: noNetwork=true, injectedResponseStorageMs=250, recordedDurationMs="+row.durationMillis());
        }
    }
    static void cancelledMacro() throws Exception {
        Persistence memory=memory();
        try (ScanSessionStore s=new ScanSessionStore(memory,CaptureReview::object)) {
            LoginMacroStore profile=new LoginMacroStore(memory,CaptureReview::object);
            profile.addSteps(List.of(req())); profile.setEnabled(true);
            long session=s.queueSession(req(),null);
            CountDownLatch started=new CountDownLatch(1), release=new CountDownLatch(1), returned=new CountDownLatch(1);
            LoginMacroRunner.Cancellation cancellation=new LoginMacroRunner.Cancellation();
            try(LoginMacroRunner runner=new LoginMacroRunner(profile,s,request->{
                started.countDown();
                while(release.getCount()>0) { try { release.await(); } catch(InterruptedException ignored) { } }
                HttpRequestResponse result=(HttpRequestResponse)fixture("exchange",new Class<?>[]{HttpRequest.class,HttpResponse.class},request,res());
                returned.countDown(); return result;
            })) {
                CompletableFuture<?> future=CompletableFuture.runAsync(()->runner.prepare(session,req(),ignored->{},cancellation));
                if(!started.await(2,TimeUnit.SECONDS)) throw new AssertionError("transport not started");
                cancellation.cancel();
                try { future.get(2,TimeUnit.SECONDS); } catch(ExecutionException expected) { }
                release.countDown(); if(!returned.await(2,TimeUnit.SECONDS)) throw new AssertionError("transport did not return");
                delay(100);
                ScanRequestEntry.Snapshot row=s.sessionEntries(session).get(0);
                System.out.println("LATE_MACRO_RESPONSE: transportReturned200=true, capturedResponse="+(row.response()!=null)+", state="+row.received()+", error="+row.error());
            } finally { release.countDown(); }
        }
    }
}
