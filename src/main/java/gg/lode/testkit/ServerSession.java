package gg.lode.testkit;

import gg.lode.testkit.bot.BotDriver;
import gg.lode.testkit.bot.BotLoader;
import gg.lode.testkit.bot.FakePlayer;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * One test server for the duration of a run: takes the panel lock, stops it, wipes plugins and worlds,
 * uploads the plugins under test, starts it and watches the boot.
 *
 * <p>Failure rules: anything wrong with the infrastructure (panel or Wings unreachable, server suspended,
 * container never prints a line, server busy with another run) is an {@link InfraUnavailableException}, which
 * skips the tests with a warning. Anything the server itself does wrong once it is booting (crash, JVM fatal
 * error, boot hang, plugin failing to enable) fails the tests.
 */
final class ServerSession implements TestServer, AutoCloseable {

    private static final Pattern PREFIX = Pattern.compile("^\\[\\d{2}:\\d{2}:\\d{2}(?: [A-Z]+)?]:? ?");
    private static final Pattern LEVEL = Pattern.compile("^\\[\\d{2}:\\d{2}:\\d{2} ([A-Z]+)]");
    private static final Pattern STACK = Pattern.compile("^(\\s+at |\\s*\\.\\.\\. \\d+ more|Caused by: |[a-zA-Z_$][\\w$]*(\\.[\\w$]+)+(Exception|Error)\\b)");
    private static final String LOCK_FILE = ".testkit-lock";
    private static final Duration LOCK_STALE = Duration.ofMinutes(30);
    private static final String OWNER = owner();
    private static final AtomicInteger MARKERS = new AtomicInteger();

    private final TestKitConfig config;
    private final PanelClient panel;
    private final ServerSpec spec;
    private final SessionKey key;
    private final List<FakePlayer> players = new CopyOnWriteArrayList<>();
    private PanelConsole console;
    private int bootMark;
    private boolean locked;

    ServerSession(TestKitConfig config, PanelClient panel, ServerSpec spec, SessionKey key) {
        this.config = config;
        this.panel = panel;
        this.spec = spec;
        this.key = key;
    }

    SessionKey key() {
        return key;
    }

    void start() {
        phase("check", () -> panel.checkUsable(spec.uuid()));
        phase("lock", this::acquireLock);
        phase("console", () -> {
            console = new PanelConsole(panel, spec.uuid());
            console.open();
        });
        phase("stop", this::stopServer);
        phase("reset", this::resetFiles);
        phase("upload", this::uploadPlugins);
        phase("hooks", this::runHooks);
        phase("boot", () -> {
            bootMark = console.mark();
            panel.power(spec.uuid(), "start");
            awaitBoot();
        });
    }

    /** Times each setup step so a slow CI run shows where the minutes went. */
    private void phase(String name, Runnable step) {
        long started = System.nanoTime();
        try {
            step.run();
        } finally {
            System.err.printf("[TestKit] %s %s %.1fs%n", spec.id(), name, (System.nanoTime() - started) / 1e9);
        }
    }

    // ---------------------------------------------------------------- lifecycle

    private void acquireLock() {
        long waitMs = Long.parseLong(System.getenv().getOrDefault("LODESTONE_TESTKIT_LOCK_WAIT_SECONDS", "900")) * 1000;
        long deadline = System.currentTimeMillis() + waitMs;
        while (true) {
            String held = panel.read(spec.uuid(), "/" + LOCK_FILE);
            if (held == null || held.isBlank() || held.startsWith(OWNER + "|") || isStale(held)) break;
            if (System.currentTimeMillis() > deadline) {
                throw new InfraUnavailableException("Test server " + spec.id() + " is busy with another run ("
                        + held.split("\\|")[0] + ")");
            }
            sleep(15_000);
        }
        panel.write(spec.uuid(), "/" + LOCK_FILE, OWNER + "|" + System.currentTimeMillis());
        locked = true;
    }

    private static boolean isStale(String lock) {
        String[] parts = lock.trim().split("\\|");
        try {
            return parts.length < 2 || System.currentTimeMillis() - Long.parseLong(parts[1]) > LOCK_STALE.toMillis();
        } catch (NumberFormatException e) {
            return true;
        }
    }

    private void stopServer() {
        String state = panel.state(spec.uuid());
        if (state.equals("offline")) return;
        panel.power(spec.uuid(), "stop");
        if (waitOffline(Duration.ofSeconds(90))) return;
        panel.power(spec.uuid(), "kill");
        if (!waitOffline(Duration.ofSeconds(30))) {
            throw new InfraUnavailableException("Test server " + spec.id() + " would not stop");
        }
    }

    private boolean waitOffline(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (panel.state(spec.uuid()).equals("offline")) return true;
            sleep(1_000);
        }
        return false;
    }

    private void resetFiles() {
        List<String> root = panel.list(spec.uuid(), "/");
        // Player lists go too, so an op or ban from one run never leaks into the next.
        List<String> wipe = new ArrayList<>(List.of("plugins", "logs", "crash-reports",
                "ops.json", "whitelist.json", "banned-players.json", "banned-ips.json", "usercache.json"));
        if (!spec.isProxy()) wipe.addAll(List.of("world", "world_nether", "world_the_end"));
        wipe.retainAll(root);
        panel.delete(spec.uuid(), "/", wipe);
        panel.createFolder(spec.uuid(), "/", "plugins");
        if (!spec.isProxy()) {
            panel.write(spec.uuid(), "/eula.txt", "eula=true\n");
            panel.write(spec.uuid(), "/server.properties", patchProperties(panel.read(spec.uuid(), "/server.properties")));
        }
    }

    /** Offline mode (the bot logs in without Mojang), a small flat world, no structures or mobs to slow boot. */
    static String patchProperties(String current) {
        Map<String, String> props = new LinkedHashMap<>();
        if (current != null) {
            for (String line : current.split("\n")) {
                int eq = line.indexOf('=');
                if (line.startsWith("#") || eq < 0) continue;
                props.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        }
        props.put("online-mode", "false");
        // 26.x ships with the whitelist on by default, which turns the bot away.
        props.put("white-list", "false");
        props.put("enforce-whitelist", "false");
        props.put("enforce-secure-profile", "false");
        props.put("level-type", "minecraft\\:flat");
        props.put("generate-structures", "false");
        props.put("spawn-monsters", "false");
        props.put("spawn-protection", "0");
        props.put("view-distance", "4");
        props.put("simulation-distance", "4");
        props.put("allow-nether", "false");
        props.put("enable-rcon", "false");
        props.put("motd", "Lodestone TestKit");
        StringBuilder out = new StringBuilder("# Managed by Lodestone-TestKit; rewritten every run\n");
        props.forEach((k, v) -> out.append(k).append('=').append(v).append('\n'));
        return out.toString();
    }

    private void uploadPlugins() {
        Path cache = config.cacheDir();
        List<String> specs = new ArrayList<>(key.plugins());
        if (key.fakePlayers() && !spec.isProxy() && !spec.mc().equals(BotLoader.driver().minecraftVersion())) {
            specs.add("modrinth:viaversion");
        }
        for (String pluginSpec : specs) {
            for (Path jar : PluginSource.resolve(pluginSpec, spec, cache)) {
                panel.upload(spec.uuid(), jar, "/plugins");
            }
        }
    }

    private void runHooks() {
        if (key.hooks().isEmpty()) return;
        ServerPrep prep = new ServerPrep() {
            @Override
            public ServerSpec spec() {
                return spec;
            }

            @Override
            public void writeFile(String path, String content) {
                String clean = path.startsWith("/") ? path : "/" + path;
                int slash = clean.lastIndexOf('/');
                if (slash > 0) createFolders(clean.substring(0, slash));
                panel.write(spec.uuid(), clean, content);
            }

            @Override
            public void upload(Path file, String folder) {
                String clean = folder.startsWith("/") ? folder : "/" + folder;
                createFolders(clean);
                panel.upload(spec.uuid(), file, clean);
            }
        };
        for (Method hook : key.hooks()) {
            try {
                hook.setAccessible(true);
                hook.invoke(null, prep);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException re) throw re;
                throw new IllegalStateException("@BeforeServerStart " + hook.getName() + " failed", cause);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("@BeforeServerStart " + hook.getName() + " is not accessible", e);
            }
        }
    }

    private void createFolders(String path) {
        StringBuilder current = new StringBuilder();
        for (String part : path.split("/")) {
            if (part.isEmpty()) continue;
            panel.createFolder(spec.uuid(), current.isEmpty() ? "/" : current.toString(), part);
            current.append('/').append(part);
        }
    }

    private void awaitBoot() {
        int timeout = key.startTimeoutSeconds();
        long deadline = System.nanoTime() + Duration.ofSeconds(timeout).toNanos();
        boolean sawStart = false;
        while (System.nanoTime() < deadline) {
            List<String> lines = console.since(bootMark);
            for (String line : lines) {
                if (line.contains("A fatal error has been detected by the Java Runtime Environment")) {
                    sleep(2_000);
                    throw new AssertionError("The JVM crashed while " + spec.id() + " was booting:\n" + tail(40));
                }
                if (PREFIX.matcher(line).find() && line.contains("Done (")) return;
            }
            String state = console.state();
            if (state.equals("starting") || state.equals("running")) sawStart = true;
            if (sawStart && state.equals("offline")) {
                throw new AssertionError("Test server " + spec.id() + " stopped while booting:\n" + tail(60));
            }
            sleep(500);
        }
        if (console.since(bootMark).stream().noneMatch(l -> PREFIX.matcher(l).find())) {
            throw new InfraUnavailableException("Test server " + spec.id() + " printed nothing within " + timeout
                    + "s of being started (container not starting?)");
        }
        throw new AssertionError("Test server " + spec.id() + " did not finish booting within " + timeout + "s:\n" + tail(60));
    }

    void closePlayers() {
        for (FakePlayer p : players) {
            try {
                p.close();
            } catch (RuntimeException ignored) {
                // Already gone.
            }
        }
        players.clear();
    }

    @Override
    public void close() {
        closePlayers();
        try {
            if (console != null && !panel.state(spec.uuid()).equals("offline")) {
                panel.power(spec.uuid(), "stop");
                if (!waitOffline(Duration.ofSeconds(90))) panel.power(spec.uuid(), "kill");
            }
        } catch (RuntimeException ignored) {
            // Leaving it running is harmless; the next run stops it first.
        }
        try {
            if (locked) panel.delete(spec.uuid(), "/", List.of(LOCK_FILE));
        } catch (RuntimeException ignored) {
            // A stale lock expires on its own.
        }
        if (console != null) console.close();
    }

    // ---------------------------------------------------------------- TestServer

    @Override
    public ServerSpec spec() {
        return spec;
    }

    @Override
    public CommandResult run(String command) {
        String cmd = command.startsWith("/") ? command.substring(1) : command;
        int mark = console.mark();
        console.send(cmd);
        List<String> out;
        if (spec.isProxy()) {
            awaitQuiet(Duration.ofMillis(1500), Duration.ofSeconds(10));
            out = console.since(mark);
        } else {
            // An unknown command echoes itself back, so a unique one marks exactly where the output ends.
            String marker = "testkit-end-" + MARKERS.incrementAndGet();
            console.send(marker);
            if (console.await(mark, l -> l.contains(marker), Duration.ofSeconds(30)) == null) {
                throw new AssertionError("No response from " + spec.id() + " console after '" + cmd + "'");
            }
            out = console.since(mark);
            int end = indexOfLast(out, marker);
            out = new ArrayList<>(out.subList(0, end));
            if (!out.isEmpty() && out.getLast().contains("Unknown or incomplete command")) out.removeLast();
        }
        return new CommandResult(cmd, out.stream().map(ServerSession::strip).filter(l -> !l.isBlank()).toList());
    }

    private void awaitQuiet(Duration quiet, Duration max) {
        long end = System.nanoTime() + max.toNanos();
        int last = console.mark();
        long lastChange = System.nanoTime();
        while (System.nanoTime() < end) {
            sleep(100);
            int now = console.mark();
            if (now != last) {
                last = now;
                lastChange = System.nanoTime();
            } else if (System.nanoTime() - lastChange > quiet.toNanos()) {
                return;
            }
        }
    }

    @Override
    public List<String> log() {
        return console.since(bootMark);
    }

    @Override
    public boolean isEnabled(String plugin) {
        List<String> log = log();
        String lower = plugin.toLowerCase(Locale.ROOT);
        boolean enabling = log.stream().anyMatch(l -> spec.isProxy()
                ? l.toLowerCase(Locale.ROOT).contains("loaded plugin " + lower)
                : l.contains("Enabling " + plugin + " v"));
        boolean failed = log.stream().anyMatch(l -> l.contains("Error occurred while enabling " + plugin)
                || (l.contains("Could not load") && l.contains(plugin))
                // Tests run while the server is up, so a plugin disabling now has disabled itself.
                || l.contains("Disabling " + plugin + " v"));
        return enabling && !failed;
    }

    @Override
    public void assertEnabled(String plugin) {
        if (!isEnabled(plugin)) {
            throw new AssertionError(plugin + " did not enable cleanly on " + spec.id() + ":\n" + linesAbout(plugin));
        }
    }

    @Override
    public List<String> errorsFrom(String plugin) {
        List<String> out = new ArrayList<>();
        boolean inTrace = false;
        for (String line : log()) {
            if (inTrace && STACK.matcher(line).find()) {
                out.add(line);
                continue;
            }
            inTrace = false;
            var level = LEVEL.matcher(line);
            boolean error = level.find() && (level.group(1).equals("ERROR") || level.group(1).equals("SEVERE"));
            boolean about = line.contains("[" + plugin + "]") || line.contains(" " + plugin + " ")
                    || line.contains("enabling " + plugin) || line.contains("to " + plugin + " v");
            if (error && about) {
                out.add(line);
                inTrace = true;
            }
        }
        return out;
    }

    @Override
    public void assertNoErrorsFrom(String plugin) {
        List<String> errors = errorsFrom(plugin);
        if (!errors.isEmpty()) {
            throw new AssertionError(plugin + " logged errors on " + spec.id() + ":\n" + String.join("\n", errors));
        }
    }

    @Override
    public FakePlayer join(String name) {
        if (!key.fakePlayers()) {
            throw new IllegalStateException("Set fakePlayers = true on @LodestoneServers to use join()");
        }
        if (spec.isProxy()) throw new IllegalStateException("Fake players can't join a proxy without a backend");
        BotDriver driver = BotLoader.driver();
        FakePlayer player = driver.connect(spec.host(), spec.port(), name, Duration.ofSeconds(45));
        players.add(player);
        return player;
    }

    @Override
    public FakePlayer joinAsOp(String name) {
        FakePlayer player = join(name);
        run("op " + name);
        return player;
    }

    @Override
    public String awaitLog(String contains, Duration timeout) {
        String line = console.await(console.mark(), l -> l.contains(contains), timeout);
        if (line == null) throw new AssertionError("No console line containing '" + contains + "' on " + spec.id());
        return line;
    }

    // ---------------------------------------------------------------- helpers

    private String tail(int n) {
        List<String> lines = console.since(bootMark);
        return String.join("\n", lines.subList(Math.max(0, lines.size() - n), lines.size()));
    }

    private String linesAbout(String plugin) {
        List<String> about = log().stream().filter(l -> l.contains(plugin)).toList();
        return about.isEmpty() ? "(no lines mention " + plugin + ")" : String.join("\n", about);
    }

    static String strip(String line) {
        return PREFIX.matcher(line).replaceFirst("");
    }

    private static int indexOfLast(List<String> lines, String needle) {
        for (int i = lines.size() - 1; i >= 0; i--) if (lines.get(i).contains(needle)) return i;
        return lines.size();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InfraUnavailableException("Interrupted", e);
        }
    }

    private static String owner() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "unknown";
        }
        String run = System.getenv("GITHUB_RUN_ID");
        String repo = System.getenv("GITHUB_REPOSITORY");
        String who = repo != null ? repo + "#" + run : host;
        return who + "@" + ProcessHandle.current().pid() + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
