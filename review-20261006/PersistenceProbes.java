package com.scanviewer;

import burp.api.montoya.http.HttpService;
import burp.api.montoya.internal.MontoyaObjectFactory;
import burp.api.montoya.internal.ObjectFactoryLocator;
import burp.api.montoya.persistence.Persistence;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Simulates a persistence interleaving followed by an import commit failure. */
public class PersistenceProbes {
    static void factory() {
        ObjectFactoryLocator.FACTORY = (MontoyaObjectFactory) Proxy.newProxyInstance(PersistenceProbes.class.getClassLoader(), new Class[]{MontoyaObjectFactory.class}, (p,m,a) -> {
            String name = m.getName().toLowerCase(Locale.ROOT);
            if (name.equals("bytearray")) return ReviewProbes.fixture("byteArray", a[0] instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : a[0].toString());
            if (name.equals("httpservice")) return ReviewProbes.dummy(HttpService.class);
            if (name.equals("httprequest")) return ReviewProbes.request(a == null || a.length == 0 ? "GET / HTTP/1.1\r\n\r\n" : a[a.length - 1].toString());
            if (name.equals("httpresponse")) return ReviewProbes.response(a == null || a.length == 0 ? "HTTP/1.1 200 OK\r\n\r\n" : a[0].toString());
            throw new UnsupportedOperationException(m.toString());
        });
    }
    static void zip(ZipOutputStream stream, String path, String value) throws Exception {
        stream.putNextEntry(new ZipEntry(path)); stream.write(value.getBytes(StandardCharsets.UTF_8)); stream.closeEntry();
    }
    static String b64(String text) { return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)); }
    public static void main(String[] args) throws Exception {
        factory();
        Path archive = Path.of("target/review-20261006/commit-failure.ascan");
        try (ZipOutputStream stream = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip(stream, "manifest.properties", "format=active-scan-timeline-archive-1\nrequest_count=1\nresponse_count=0\nbase.host=example.test\nbase.port=443\nbase.secure=true\n");
            zip(stream, "base-request.bin", "GET / HTTP/1.1\r\nHost: example.test\r\n\r\n");
            zip(stream, "requests/0.bin", "GET / HTTP/1.1\r\nHost: example.test\r\n\r\n");
            zip(stream, "entries.tsv", String.join("\t", "0", "1", b64("GET /"), "", "", b64("-"), b64("Waiting"), "", b64("No"), "", "-1", "false", "", b64("example.test"), "443", "true", "") + "\n");
        }
        Persistence memory = ReviewProbes.persistence();
        try (ScanSessionStore store = new ScanSessionStore(memory, ReviewProbes::memoryObject)) {
            String failure = "NONE";
            try {
                store.importSession(archive, new ScanSessionStore.ArchiveProgress() {
                    public boolean cancelled() { return false; }
                    public void update(int completed, int expected, String message) {
                        if (!message.equals("Adding imported records")) return;
                        try { ReviewProbes.call(store, "persistNow"); }
                        catch (Exception e) { throw new RuntimeException(e); }
                        throw new IllegalStateException("injected commit failure after a scheduled save interleaving");
                    }
                });
            } catch (java.io.IOException e) { failure = e.getMessage(); }
            ReviewProbes.expect(failure.contains("no history was added") && store.totalEntries() == 0, "expected memory rollback");
            try (ScanSessionStore reloaded = new ScanSessionStore(memory, ReviewProbes::memoryObject)) {
                System.out.println("IMPORT_ROLLBACK: message='" + failure + "', current rows=" + store.totalEntries() + ", after reload rows=" + reloaded.totalEntries() + ", sessions=" + reloaded.sessions().size());
                ReviewProbes.expect(reloaded.totalEntries() == 1, "expected persisted import to survive rollback");
            }
        }
    }
}
