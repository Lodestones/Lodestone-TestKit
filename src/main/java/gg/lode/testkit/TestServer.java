package gg.lode.testkit;

import gg.lode.testkit.bot.FakePlayer;

import java.time.Duration;
import java.util.List;

/** A running CI test server, handed to {@link ServerTest} methods. */
public interface TestServer {

    ServerSpec spec();

    /** Minecraft version the server runs. */
    default String mc() {
        return spec().mc();
    }

    /** Runs a console command and returns what it printed. */
    CommandResult run(String command);

    /** Every console line since the server was started for this run (prefixes kept, colours stripped). */
    List<String> log();

    /** True if the plugin logged "Enabling" and didn't log a failure to enable. */
    boolean isEnabled(String plugin);

    /** Fails unless the plugin enabled cleanly. */
    void assertEnabled(String plugin);

    /** ERROR lines and stack traces attributable to the plugin since the server started. */
    List<String> errorsFrom(String plugin);

    /** Fails if {@link #errorsFrom(String)} is not empty. */
    void assertNoErrorsFrom(String plugin);

    /**
     * Joins a fake player (requires {@link LodestoneServers#fakePlayers()}). The player is kicked and closed
     * when the test ends if the test didn't close it.
     */
    FakePlayer join(String name);

    /** Joins a fake player and makes it a server operator. */
    FakePlayer joinAsOp(String name);

    /** Waits for a console line containing the text, printed from now on. */
    String awaitLog(String contains, Duration timeout);
}
