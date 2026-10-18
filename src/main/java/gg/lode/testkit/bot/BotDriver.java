package gg.lode.testkit.bot;

import java.time.Duration;

/** Connects fake players. Implemented inside the isolated bot classloader. */
public interface BotDriver {

    /** Protocol version the bot speaks, as a Minecraft version (e.g. {@code 26.3}). */
    String minecraftVersion();

    /**
     * Logs a player in and waits until it has spawned.
     *
     * @throws AssertionError if the server refuses it or it doesn't spawn in time
     */
    FakePlayer connect(String host, int port, String name, Duration timeout);
}
