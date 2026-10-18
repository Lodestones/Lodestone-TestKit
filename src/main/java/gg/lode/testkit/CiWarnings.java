package gg.lode.testkit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Set;

/**
 * Surfaces "skipped because the test servers are down" as a GitHub Actions warning: an annotation on the
 * run, a line in the job summary, and a line in {@code LODESTONE_TESTKIT_WARNINGS} for workflows that
 * re-emit them after the build (Gradle hides test stdout by default).
 */
public final class CiWarnings {

    private static final Set<String> EMITTED = new HashSet<>();

    private CiWarnings() {
    }

    public static synchronized void warn(String title, String message) {
        String line = title + ": " + message;
        if (!EMITTED.add(line)) return;
        String oneLine = message.replace("%", "%25").replace("\r", "").replace("\n", "%0A");
        System.out.println("::warning title=" + title.replace(",", " ").replace(":", " ") + "::" + oneLine);
        System.err.println("[TestKit] WARNING " + line);
        append(System.getenv("GITHUB_STEP_SUMMARY"), "> [!WARNING]\n> **" + title + "**: " + message + "\n\n");
        append(System.getenv("LODESTONE_TESTKIT_WARNINGS"), title + "\t" + message.replace("\n", " ") + "\n");
    }

    private static void append(String file, String text) {
        if (file == null || file.isBlank()) return;
        try {
            Path path = Path.of(file);
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            Files.writeString(path, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // Best effort: the stdout annotation already carries it.
        }
    }
}
