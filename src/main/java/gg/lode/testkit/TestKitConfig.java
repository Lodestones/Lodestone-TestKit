package gg.lode.testkit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Where the test servers are and how to reach the panel. Read from {@code LODESTONE_TESTKIT_CONFIG}, else
 * {@code ~/.lodestone-testkit/config.json}. The file lives on the runner (and on any dev machine that should
 * run server tests), never in a repo, since it holds the panel client key.
 *
 * <pre>{@code
 * {
 *   "panelUrl": "https://panel.lode.gg",
 *   "clientKey": "ptlc_...",
 *   "panelHost": "panel.lode.gg",       // optional: Host header when panelUrl is a local address
 *   "wingsUrl": "http://127.0.0.1:8080", // optional: reach Wings directly instead of its public URL
 *   "servers": [
 *     { "id": "1.21.6", "uuid": "1a2b3c4d", "host": "15.204.154.132", "port": 25901, "platform": "paper", "mc": "1.21.6" }
 *   ]
 * }
 * }</pre>
 *
 * {@code LODESTONE_TESTKIT_SERVERS} (comma-separated ids) narrows any run to a subset, handy locally.
 */
public final class TestKitConfig {

    private final String panelUrl;
    private final String clientKey;
    private final String panelHost;
    private final String wingsUrl;
    private final Map<String, ServerSpec> servers;
    private final Path cacheDir;

    private TestKitConfig(String panelUrl, String clientKey, String panelHost, String wingsUrl,
                          Map<String, ServerSpec> servers, Path cacheDir) {
        this.panelUrl = panelUrl;
        this.clientKey = clientKey;
        this.panelHost = panelHost;
        this.wingsUrl = wingsUrl;
        this.servers = servers;
        this.cacheDir = cacheDir;
    }

    public static Path home() {
        return Path.of(System.getProperty("user.home"), ".lodestone-testkit");
    }

    /** @throws InfraUnavailableException when there is no usable config, so the tests skip with a warning */
    public static TestKitConfig load() {
        String override = System.getenv("LODESTONE_TESTKIT_CONFIG");
        Path file = override != null && !override.isBlank() ? Path.of(override) : home().resolve("config.json");
        if (!Files.isRegularFile(file)) {
            throw new InfraUnavailableException("No TestKit config at " + file + " (set LODESTONE_TESTKIT_CONFIG)");
        }
        JsonObject root;
        try {
            root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            throw new InfraUnavailableException("Unreadable TestKit config " + file + ": " + e.getMessage(), e);
        }
        String panelUrl = string(root, "panelUrl");
        String clientKey = System.getenv().getOrDefault("LODESTONE_TESTKIT_PANEL_KEY", string(root, "clientKey"));
        if (panelUrl == null || clientKey == null || clientKey.isBlank()) {
            throw new InfraUnavailableException("TestKit config " + file + " needs panelUrl and clientKey");
        }
        Map<String, ServerSpec> servers = new LinkedHashMap<>();
        JsonArray list = root.has("servers") ? root.getAsJsonArray("servers") : new JsonArray();
        for (JsonElement el : list) {
            JsonObject o = el.getAsJsonObject();
            ServerSpec spec = new ServerSpec(string(o, "id"), string(o, "uuid"), string(o, "host"),
                    o.get("port").getAsInt(), string(o, "platform"), string(o, "mc"));
            servers.put(spec.id(), spec);
        }
        String panelHost = string(root, "panelHost");
        if (panelHost != null) {
            // Host is a restricted header in java.net.http; it has to be unlocked before the first client is built.
            System.setProperty("jdk.httpclient.allowRestrictedHeaders", "host");
        }
        String wingsUrl = string(root, "wingsUrl");
        String cache = string(root, "cacheDir");
        return new TestKitConfig(stripSlash(panelUrl), clientKey, panelHost, wingsUrl != null ? stripSlash(wingsUrl) : null, servers,
                cache != null ? Path.of(cache) : home().resolve("cache"));
    }

    public String panelUrl() {
        return panelUrl;
    }

    String clientKey() {
        return clientKey;
    }

    /** Host header for panel requests, or null to use the one in {@link #panelUrl()}. */
    String panelHost() {
        return panelHost;
    }

    /** Base URL that replaces the public Wings address in socket and upload URLs, or null. */
    String wingsUrl() {
        return wingsUrl;
    }

    public Path cacheDir() {
        return cacheDir;
    }

    /** Resolves the ids a test asked for, honouring {@code LODESTONE_TESTKIT_SERVERS}. */
    public List<ServerSpec> select(String[] requested) {
        List<ServerSpec> picked = new ArrayList<>();
        if (requested.length == 0) {
            for (ServerSpec s : servers.values()) if ("paper".equalsIgnoreCase(s.platform())) picked.add(s);
        } else {
            for (String id : requested) {
                ServerSpec s = servers.get(id);
                if (s == null) {
                    CiWarnings.warn("TestKit", "No test server '" + id + "' in the TestKit config; skipping it");
                    continue;
                }
                picked.add(s);
            }
        }
        String only = System.getenv("LODESTONE_TESTKIT_SERVERS");
        if (only != null && !only.isBlank()) {
            Set<String> keep = Arrays.stream(only.split(",")).map(String::trim).collect(Collectors.toSet());
            picked.removeIf(s -> !keep.contains(s.id()));
        }
        return picked;
    }

    private static String string(JsonObject o, String key) {
        JsonElement el = o.get(key);
        return el == null || el.isJsonNull() ? null : el.getAsString();
    }

    private static String stripSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
