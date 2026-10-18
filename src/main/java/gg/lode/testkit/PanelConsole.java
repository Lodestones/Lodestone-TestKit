package gg.lode.testkit;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Live console of one server over the Wings websocket. Keeps every line seen since {@link #open()}, with
 * colour codes and the {@code [hh:mm:ss LEVEL]:} prefix left intact so callers can read the level.
 */
final class PanelConsole implements AutoCloseable {

    private static final Pattern ANSI = Pattern.compile("\u001B\\[[;\\d]*[ -/]*[@-~]");

    private final PanelClient panel;
    private final String server;
    private final List<String> lines = new ArrayList<>();
    private volatile String state = "unknown";
    private volatile WebSocket socket;
    private volatile boolean closed;

    PanelConsole(PanelClient panel, String server) {
        this.panel = panel;
        this.server = server;
    }

    void open() {
        JsonObject info = panel.websocket(server);
        try {
            socket = panel.http().newWebSocketBuilder()
                    .header("Origin", panel.origin())
                    .connectTimeout(Duration.ofSeconds(15))
                    .buildAsync(URI.create(info.get("socket").getAsString()), new Listener())
                    .get(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new InfraUnavailableException("Could not open the console websocket for " + server + ": " + e.getMessage(), e);
        }
        sendEvent("auth", info.get("token").getAsString());
    }

    /** Sends a console command through the websocket (no panel rate limit, unlike the REST endpoint). */
    void send(String command) {
        sendEvent("send command", command);
    }

    String state() {
        return state;
    }

    /** Index the next line will get, for reading only what a command printed. */
    int mark() {
        synchronized (lines) {
            return lines.size();
        }
    }

    List<String> since(int mark) {
        synchronized (lines) {
            return new ArrayList<>(lines.subList(Math.min(mark, lines.size()), lines.size()));
        }
    }

    List<String> all() {
        return since(0);
    }

    /** Waits for a line after {@code mark} matching the predicate; null on timeout. */
    String await(int mark, Predicate<String> match, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        int seen = mark;
        synchronized (lines) {
            while (true) {
                for (; seen < lines.size(); seen++) {
                    if (match.test(lines.get(seen))) return lines.get(seen);
                }
                long left = deadline - System.nanoTime();
                if (left <= 0) return null;
                try {
                    lines.wait(Math.max(1, TimeUnit.NANOSECONDS.toMillis(left)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
    }

    /** Waits for the panel to report the given power state. */
    boolean awaitState(String wanted, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (wanted.equals(state)) return true;
            synchronized (lines) {
                try {
                    lines.wait(250);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return wanted.equals(state);
    }

    @Override
    public void close() {
        closed = true;
        WebSocket ws = socket;
        if (ws != null) ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").exceptionally(t -> null);
    }

    static String clean(String raw) {
        return ANSI.matcher(raw).replaceAll("").replace("\r", "");
    }

    private void sendEvent(String event, String arg) {
        JsonObject msg = new JsonObject();
        msg.addProperty("event", event);
        JsonArray args = new JsonArray();
        args.add(arg);
        msg.add("args", args);
        WebSocket ws = socket;
        if (ws == null) throw new InfraUnavailableException("Console websocket for " + server + " is not open");
        ws.sendText(msg.toString(), true).join();
    }

    private void reauth() {
        try {
            sendEvent("auth", panel.websocket(server).get("token").getAsString());
        } catch (RuntimeException ignored) {
            // The next "token expired" reconnects instead.
        }
    }

    private void addLine(String raw) {
        synchronized (lines) {
            for (String part : clean(raw).split("\n")) lines.add(part);
            lines.notifyAll();
        }
    }

    private final class Listener implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                handle(buffer.toString());
                buffer.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (!closed) reconnect();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            if (!closed) reconnect();
        }

        private void handle(String text) {
            JsonObject msg = JsonParser.parseString(text).getAsJsonObject();
            String event = msg.get("event").getAsString();
            JsonArray args = msg.has("args") && msg.get("args").isJsonArray() ? msg.getAsJsonArray("args") : new JsonArray();
            switch (event) {
                case "console output", "install output", "daemon message" -> {
                    if (!args.isEmpty()) addLine(args.get(0).getAsString());
                }
                case "status" -> {
                    if (!args.isEmpty()) {
                        state = args.get(0).getAsString();
                        synchronized (lines) {
                            lines.notifyAll();
                        }
                    }
                }
                case "token expiring" -> CompletableFuture.runAsync(PanelConsole.this::reauth);
                case "token expired" -> CompletableFuture.runAsync(PanelConsole.this::reconnect);
                default -> {
                }
            }
        }
    }

    private void reconnect() {
        if (closed) return;
        try {
            open();
        } catch (InfraUnavailableException ignored) {
            // Callers notice through timeouts; a dead console during a run is reported there.
        }
    }
}
