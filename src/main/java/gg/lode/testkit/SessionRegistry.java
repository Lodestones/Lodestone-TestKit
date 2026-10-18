package gg.lode.testkit;

import org.junit.jupiter.api.extension.ExtensionContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Booted servers for the whole test run, one per server id. Boots run in parallel: the first test of a class
 * kicks off every server that class needs, and each test then waits only for its own.
 */
// CloseableResource stays for JUnit < 5.13, which does not auto-close AutoCloseable store values.
@SuppressWarnings("deprecation")
final class SessionRegistry implements ExtensionContext.Store.CloseableResource, AutoCloseable {

    private final TestKitConfig config;
    private final PanelClient panel;
    private final Map<String, Entry> sessions = new HashMap<>();
    private final ExecutorService boots = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "testkit-boot");
        t.setDaemon(true);
        return t;
    });

    private record Entry(ServerSession session, CompletableFuture<ServerSession> ready) {
    }

    SessionRegistry(TestKitConfig config) {
        this.config = config;
        this.panel = new PanelClient(config.panelUrl(), config.clientKey());
    }

    TestKitConfig config() {
        return config;
    }

    /** Starts (or reuses) every server in {@code specs} with this setup, without waiting. */
    synchronized void prepare(List<ServerSpec> specs, SessionKey key) {
        for (ServerSpec spec : specs) future(spec, key);
    }

    synchronized CompletableFuture<ServerSession> future(ServerSpec spec, SessionKey key) {
        Entry existing = sessions.get(spec.id());
        if (existing != null && existing.session().key().equals(key)) return existing.ready();
        if (existing != null) {
            // Another class set this server up differently: finish with it first.
            CompletableFuture<Void> previous = existing.ready().handle((s, t) -> null);
            ServerSession old = existing.session();
            ServerSession next = new ServerSession(config, panel, spec, key);
            CompletableFuture<ServerSession> ready = previous.thenApplyAsync(v -> {
                old.close();
                next.start();
                return next;
            }, boots);
            sessions.put(spec.id(), new Entry(next, ready));
            return ready;
        }
        ServerSession session = new ServerSession(config, panel, spec, key);
        CompletableFuture<ServerSession> ready = CompletableFuture.supplyAsync(() -> {
            session.start();
            return session;
        }, boots);
        sessions.put(spec.id(), new Entry(session, ready));
        return ready;
    }

    @Override
    public synchronized void close() {
        sessions.values().parallelStream().forEach(e -> {
            e.ready().handle((s, t) -> null).join();
            e.session().close();
        });
        sessions.clear();
        boots.shutdownNow();
    }
}
