package gg.lode.testkit;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * The slice of the Pterodactyl client API the TestKit needs. Every transport failure or 5xx becomes an
 * {@link InfraUnavailableException}; a 4xx is a config mistake and is reported as one.
 */
final class PanelClient {

    private final String base;
    private final String key;
    private final String host;
    private final String wingsUrl;
    private final String origin;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    PanelClient(String panelUrl, String clientKey, String panelHost, String wingsUrl) {
        this.base = panelUrl + "/api/client/servers/";
        this.key = clientKey;
        this.host = panelHost;
        this.wingsUrl = wingsUrl;
        // Wings only accepts websocket connections whose Origin is the panel's public URL.
        this.origin = panelHost != null ? "https://" + panelHost : panelUrl;
    }

    String origin() {
        return origin;
    }

    /** Points a Wings URL handed out by the panel at {@code wingsUrl}, keeping path and query. */
    String toWings(String url) {
        if (wingsUrl == null) return url;
        URI u = URI.create(url);
        String scheme = u.getScheme().startsWith("ws") ? wingsUrl.replaceFirst("^http", "ws") : wingsUrl;
        return scheme + u.getRawPath() + (u.getRawQuery() != null ? "?" + u.getRawQuery() : "");
    }

    HttpClient http() {
        return http;
    }

    /** {@code offline}, {@code starting}, {@code running} or {@code stopping}. */
    String state(String server) {
        JsonObject attrs = get(server + "/resources").getAsJsonObject("attributes");
        if (attrs.has("is_suspended") && attrs.get("is_suspended").getAsBoolean()) {
            throw new InfraUnavailableException("Server " + server + " is suspended in the panel");
        }
        return attrs.get("current_state").getAsString();
    }

    void checkUsable(String server) {
        JsonObject attrs = get(server).getAsJsonObject("attributes");
        if (attrs.has("is_installing") && attrs.get("is_installing").getAsBoolean()) {
            throw new InfraUnavailableException("Server " + server + " is still installing");
        }
        if (attrs.has("status") && !attrs.get("status").isJsonNull()) {
            throw new InfraUnavailableException("Server " + server + " is " + attrs.get("status").getAsString());
        }
    }

    void power(String server, String signal) {
        send(server + "/power", "POST", "{\"signal\":\"" + signal + "\"}");
    }

    void command(String server, String command) {
        JsonObject body = new JsonObject();
        body.addProperty("command", command);
        send(server + "/command", "POST", body.toString());
    }

    /** Websocket URL and token for the live console. */
    JsonObject websocket(String server) {
        JsonObject data = get(server + "/websocket").getAsJsonObject("data");
        data.addProperty("socket", toWings(data.get("socket").getAsString()));
        return data;
    }

    List<String> list(String server, String dir) {
        JsonObject res = get(server + "/files/list?directory=" + enc(dir));
        return res.getAsJsonArray("data").asList().stream()
                .map(e -> e.getAsJsonObject().getAsJsonObject("attributes").get("name").getAsString())
                .toList();
    }

    void delete(String server, String root, List<String> files) {
        if (files.isEmpty()) return;
        JsonObject body = new JsonObject();
        body.addProperty("root", root);
        JsonArray arr = new JsonArray();
        files.forEach(arr::add);
        body.add("files", arr);
        send(server + "/files/delete", "POST", body.toString());
    }

    void createFolder(String server, String root, String name) {
        JsonObject body = new JsonObject();
        body.addProperty("root", root);
        body.addProperty("name", name);
        try {
            send(server + "/files/create-folder", "POST", body.toString());
        } catch (IllegalStateException alreadyThere) {
            // Wings answers 4xx when the folder exists, which is the state we wanted anyway.
        }
    }

    String read(String server, String file) {
        HttpResponse<String> res = request(HttpRequest.newBuilder(URI.create(base + server + "/files/contents?file=" + enc(file))).GET());
        if (res.statusCode() == 404) return null;
        // The panel turns Wings' "file not found" into a 500, so look before calling it an outage.
        if (res.statusCode() == 500 && !exists(server, file)) return null;
        check(res);
        return res.body();
    }

    private boolean exists(String server, String file) {
        int slash = file.lastIndexOf('/');
        String dir = slash <= 0 ? "/" : file.substring(0, slash);
        try {
            return list(server, dir).contains(file.substring(slash + 1));
        } catch (IllegalStateException missingDir) {
            return false;
        }
    }

    void write(String server, String file, String content) {
        HttpResponse<String> res = request(HttpRequest.newBuilder(URI.create(base + server + "/files/write?file=" + enc(file)))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(content)));
        check(res);
    }

    /** Uploads one local file into {@code directory} through Wings' signed upload URL. */
    void upload(String server, Path file, String directory) {
        String url = toWings(get(server + "/files/upload").getAsJsonObject("attributes").get("url").getAsString());
        String boundary = "testkit" + UUID.randomUUID().toString().replace("-", "");
        byte[] head = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"files\"; filename=\""
                + file.getFileName() + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        try {
            byte[] data = Files.readAllBytes(file);
            byte[] body = new byte[head.length + data.length + tail.length];
            System.arraycopy(head, 0, body, 0, head.length);
            System.arraycopy(data, 0, body, head.length, data.length);
            System.arraycopy(tail, 0, body, head.length + data.length, tail.length);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url + "&directory=" + enc(directory)))
                    .timeout(Duration.ofMinutes(2))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            check(res);
        } catch (IOException e) {
            throw new InfraUnavailableException("Upload of " + file.getFileName() + " to Wings failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InfraUnavailableException("Interrupted uploading " + file.getFileName(), e);
        }
    }

    private JsonObject get(String path) {
        HttpResponse<String> res = request(HttpRequest.newBuilder(URI.create(base + path)).GET());
        check(res);
        return JsonParser.parseString(res.body()).getAsJsonObject();
    }

    private void send(String path, String method, String json) {
        HttpResponse<String> res = request(HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(json)));
        check(res);
    }

    private HttpResponse<String> request(HttpRequest.Builder builder) {
        if (host != null) builder.header("Host", host);
        HttpRequest req = builder.timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + key)
                .header("Accept", "application/json")
                .header("User-Agent", "Lodestone-TestKit")
                .build();
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new InfraUnavailableException("Panel unreachable (" + req.uri().getHost() + "): " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InfraUnavailableException("Interrupted talking to the panel", e);
        }
    }

    private static void check(HttpResponse<String> res) {
        int code = res.statusCode();
        if (code < 300) return;
        String detail = res.request().method() + " " + res.request().uri().getPath() + " -> " + code;
        if (code >= 500 || code == 409 || code == 429) {
            throw new InfraUnavailableException("Panel/Wings error: " + detail);
        }
        throw new IllegalStateException("Panel rejected the request (check the TestKit config): " + detail
                + " " + truncate(res.body()));
    }

    private static String truncate(String s) {
        return s == null ? "" : s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
