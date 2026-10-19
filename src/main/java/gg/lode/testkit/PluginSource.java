package gg.lode.testkit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Turns a plugin spec from {@link LodestoneServers#plugins()} into local jar files.
 *
 * <ul>
 *   <li>{@code self}: the jar(s) under test, from the {@code testkit.plugin} system property (comma-separated
 *       paths), which the build sets to its packaged artifact</li>
 *   <li>{@code file:<path>} or a bare path ending in {@code .jar}</li>
 *   <li>{@code url:<https url>}</li>
 *   <li>{@code modrinth:<slug>}: newest Paper build of a Modrinth project that lists the server's version,
 *       falling back to the newest build overall</li>
 *   <li>{@code lode:<plugin id>}: the public loader jar from lode.gg (free plugins)</li>
 * </ul>
 *
 * Downloads are cached for a day under the TestKit cache dir.
 */
final class PluginSource {

    private static final Duration CACHE_TTL = Duration.ofHours(24);
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private PluginSource() {
    }

    /** The one plugin jar in a build output folder, skipping shade's original-*, slim, sources and javadoc jars. */
    private static Path builtJar(Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> jars = files.filter(f -> {
                String n = f.getFileName().toString();
                return n.endsWith(".jar") && !n.startsWith("original-") && !n.endsWith("-slim.jar")
                        && !n.endsWith("-sources.jar") && !n.endsWith("-javadoc.jar");
            }).toList();
            if (jars.size() != 1) {
                throw new IllegalStateException("Expected one plugin jar in " + dir + ", found " + jars + " (build it first)");
            }
            return jars.getFirst();
        } catch (IOException e) {
            throw new IllegalStateException("Could not list " + dir, e);
        }
    }

    static List<Path> resolve(String spec, ServerSpec server, Path cache) {
        if (spec.equals("self") || spec.startsWith("self:")) {
            // self:<key> reads testkit.plugin.<key>; plain self on a proxy prefers testkit.plugin.velocity, so one
            // test class can cover a plugin that ships a Paper jar and a Velocity jar.
            String key = spec.startsWith("self:") ? "testkit.plugin." + spec.substring(5)
                    : server.isProxy() && System.getProperty("testkit.plugin.velocity") != null ? "testkit.plugin.velocity"
                    : "testkit.plugin";
            String prop = System.getProperty(key);
            if (prop == null || prop.isBlank()) {
                throw new IllegalStateException("'" + spec + "' needs the " + key + " system property pointing at the built jar");
            }
            List<Path> jars = new ArrayList<>();
            for (String p : prop.split(",")) {
                Path path = Path.of(p.trim());
                jars.add(Files.isDirectory(path) ? builtJar(path) : path);
                if (!Files.isRegularFile(jars.getLast())) throw new IllegalStateException(key + " jar missing: " + path);
            }
            return jars;
        }
        if (spec.startsWith("file:") || (spec.endsWith(".jar") && !spec.contains(":"))) {
            Path path = Path.of(spec.startsWith("file:") ? spec.substring(5) : spec);
            if (!Files.isRegularFile(path)) throw new IllegalStateException("Plugin jar missing: " + path);
            return List.of(path);
        }
        if (spec.startsWith("url:")) {
            String url = spec.substring(4);
            return List.of(download(url, cache.resolve("url").resolve(fileName(url))));
        }
        if (spec.startsWith("lode:")) {
            String id = spec.substring(5);
            return List.of(download("https://lode.gg/api/plugins/" + id + "/loader",
                    cache.resolve("lode").resolve(id + "-loader.jar")));
        }
        if (spec.startsWith("modrinth:")) {
            return List.of(modrinth(spec.substring(9), server, cache));
        }
        throw new IllegalArgumentException("Unknown plugin spec '" + spec + "'");
    }

    private static Path modrinth(String slug, ServerSpec server, Path cache) {
        String loaders = server.isProxy() ? "[\"velocity\"]" : "[\"paper\"]";
        String url = "https://api.modrinth.com/v2/project/" + slug + "/version?loaders="
                + URLEncoder.encode(loaders, StandardCharsets.UTF_8);
        JsonArray versions = JsonParser.parseString(fetchString(url)).getAsJsonArray();
        if (versions.isEmpty()) throw new InfraUnavailableException("Modrinth has no builds for " + slug);
        // Modrinth lists newest first: take the first build that names this server's version.
        JsonObject pick = versions.get(0).getAsJsonObject();
        for (JsonElement v : versions) {
            if (listsVersion(v.getAsJsonObject(), server.mc())) {
                pick = v.getAsJsonObject();
                break;
            }
        }
        JsonObject file = pick.getAsJsonArray("files").get(0).getAsJsonObject();
        for (JsonElement f : pick.getAsJsonArray("files")) {
            if (f.getAsJsonObject().get("primary").getAsBoolean()) file = f.getAsJsonObject();
        }
        String name = file.get("filename").getAsString();
        return download(file.get("url").getAsString(), cache.resolve("modrinth").resolve(slug).resolve(name));
    }

    private static boolean listsVersion(JsonObject version, String mc) {
        for (JsonElement g : version.getAsJsonArray("game_versions")) {
            if (g.getAsString().equals(mc)) return true;
        }
        return false;
    }

    private static Path download(String url, Path target) {
        try {
            if (Files.isRegularFile(target)
                    && Files.getLastModifiedTime(target).toInstant().plus(CACHE_TTL).isAfter(Instant.now())) {
                return target;
            }
            Files.createDirectories(target.getParent());
            Path tmp = Files.createTempFile(target.getParent(), "dl", ".part");
            HttpResponse<Path> res = HTTP.send(request(url), HttpResponse.BodyHandlers.ofFile(tmp));
            if (res.statusCode() != 200) {
                Files.deleteIfExists(tmp);
                if (Files.isRegularFile(target)) return target;
                throw new InfraUnavailableException("Download " + url + " -> HTTP " + res.statusCode());
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (IOException e) {
            if (Files.isRegularFile(target)) return target;
            throw new InfraUnavailableException("Download " + url + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InfraUnavailableException("Interrupted downloading " + url, e);
        }
    }

    private static String fetchString(String url) {
        try {
            HttpResponse<String> res = HTTP.send(request(url), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) throw new InfraUnavailableException(url + " -> HTTP " + res.statusCode());
            return res.body();
        } catch (IOException e) {
            throw new InfraUnavailableException(url + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InfraUnavailableException("Interrupted fetching " + url, e);
        }
    }

    private static HttpRequest request(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(2))
                .header("User-Agent", "Lodestones/Lodestone-TestKit")
                .GET()
                .build();
    }

    private static String fileName(String url) {
        String path = URI.create(url).getPath();
        String name = path.substring(path.lastIndexOf('/') + 1);
        return name.isBlank() ? "plugin.jar" : name;
    }
}
