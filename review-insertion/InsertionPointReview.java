package com.scanviewer;

import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.persistence.Persistence;
import burp.api.montoya.persistence.PersistedObject;
import burp.api.montoya.scanner.audit.Audit;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Isolated review reproductions. All HTTP and persistence are test doubles. */
public class InsertionPointReview {
    static Object call(Class<?> type, Object receiver, String name, Class<?>[] signature, Object... args) {
        try { Method method=type.getDeclaredMethod(name, signature); method.setAccessible(true); return method.invoke(receiver,args); }
        catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
    }
    static Object construct(String nested) {
        try { Constructor<?> c=Class.forName("com.scanviewer.RegressionTests$"+nested).getDeclaredConstructor(); c.setAccessible(true); return c.newInstance(); }
        catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
    }
    static HttpRequest request(String raw) { return (HttpRequest)call(RegressionTests.class,null,"request",new Class[]{String.class},raw); }
    static HttpResponse response(String raw) { return (HttpResponse)call(RegressionTests.class,null,"response",new Class[]{String.class},raw); }
    static HttpRequestResponse exchange(HttpRequest req,HttpResponse resp) { return (HttpRequestResponse)call(RegressionTests.class,null,"exchange",new Class[]{HttpRequest.class,HttpResponse.class},req,resp); }
    static PersistedObject object() { Object memory=construct("MemoryObject"); return (PersistedObject)call(memory.getClass(),memory,"proxy",new Class[]{}); }
    static Persistence persistence() { Object memory=construct("MemoryPersistence"); return (Persistence)call(memory.getClass(),memory,"persistence",new Class[]{}); }

    public static void main(String[] args) {
        Object previous=call(RegressionTests.class,null,"installTestMontoyaFactory",new Class[]{});
        try {
            String raw="GET /target HTTP/1.1\r\nHost: example.test\r\nAuthorization: Bearer old\r\nX-Backup: old\r\n\r\n";
            String effective=raw.replace("Bearer old","Bearer new");
            int originalOffset=raw.indexOf("old");
            var mapped=CustomInsertionPoints.remap(request(raw),request(effective),List.of(new CustomInsertionPoints.Position(originalOffset,originalOffset+3))).get(0);
            if(mapped.start()!=effective.lastIndexOf("old")) throw new AssertionError("Unexpected mapping");
            System.out.println("REPRO wrong-field remap: Authorization@"+originalOffset+" -> X-Backup@"+mapped.start());

            Persistence memory=persistence();
            ScanSessionStore store=new ScanSessionStore(memory,InsertionPointReview::object);
            LoginMacroStore profile=new LoginMacroStore(memory,InsertionPointReview::object);
            profile.addSteps(List.of(request("GET /login HTTP/1.1\r\nHost: example.test\r\n\r\n")));
            profile.saveBinding(new LoginMacroStore.Binding(0,LoginMacroStore.AUDIT_REQUEST,LoginMacroStore.TargetLocation.HEADER,
                "Authorization",LoginMacroStore.SourceKind.RESPONSE_HEADER,profile.snapshot().steps().get(0).id(),"X-Token",""));
            profile.setMacroAll(true);
            try(LoginMacroRunner runner=new LoginMacroRunner(profile,store,sent->exchange(sent,response("HTTP/1.1 200 OK\r\nX-Token: fresh-token\r\n\r\n")))) {
                long id=store.queueSession(request(raw),null);
                store.setCustomInsertionPoints(id,List.of(new CustomInsertionPoints.Position(originalOffset,originalOffset+3)));
                HttpRequest probe=request(raw.replace("Bearer old","Bearer SCANNER-PAYLOAD"));
                HttpRequest wire=runner.scannerMacro().prepare(probe,id,true);
                if(!"Bearer fresh-token".equals(wire.headerValue("Authorization"))) throw new AssertionError("Unexpected auth");
                System.out.println("REPRO macro overwrites selected probe: "+probe.headerValue("Authorization")+" -> "+wire.headerValue("Authorization"));
            } finally { store.close(); }

            Persistence damaged=persistence();
            long damagedId;
            try(ScanSessionStore saved=new ScanSessionStore(damaged,InsertionPointReview::object)) {
                damagedId=saved.queueSession(request(raw),null);
                saved.setCustomInsertionPoints(damagedId,List.of(new CustomInsertionPoints.Position(originalOffset,originalOffset+3)));
            }
            damaged.extensionData().getChildObject("active-scan-timeline-v2").getChildObject("sessions")
                .getChildObject(Long.toString(damagedId)).deleteString("custom_insertion_points");
            try(ScanSessionStore reloaded=new ScanSessionStore(damaged,InsertionPointReview::object)) {
                if(!reloaded.customInsertionPoints(damagedId).isEmpty() || reloaded.recoveryRequired()) throw new AssertionError("Unexpected recovery");
                System.out.println("REPRO deleted custom config reloads as empty/default; recoveryRequired=false");
            }

            ScanSessionStore clearedStore=new ScanSessionStore(persistence(),InsertionPointReview::object);
            AtomicInteger overload=new AtomicInteger();
            Audit audit=(Audit)Proxy.newProxyInstance(Audit.class.getClassLoader(),new Class[]{Audit.class},(self,method,values)-> {
                if(method.getName().equals("addRequest")) { overload.set(values.length); return null; }
                if(method.getName().equals("statusMessage")) return "running";
                if(method.getName().equals("issues")) return List.of();
                if(method.getReturnType()==int.class) return 0;
                return null;
            });
            try(ScanQueue queue=new ScanQueue(clearedStore,()->audit,(message,error)->{},false)) {
                long id=clearedStore.queueSession(request(raw),null);
                clearedStore.setCustomInsertionPoints(id,List.of(new CustomInsertionPoints.Position(originalOffset,originalOffset+3)));
                queue.setCustomInsertionPoints(id,List.of()).join();
                queue.resume(false).join();
                if(overload.get()!=1) throw new AssertionError("Unexpected Scanner overload "+overload.get());
                System.out.println("REPRO Clear custom points dispatches addRequest(request), using default positions");
            } finally { clearedStore.close(); }
        } finally { call(RegressionTests.class,null,"restoreTestMontoyaFactory",new Class[]{Object.class},previous); }
    }
}
