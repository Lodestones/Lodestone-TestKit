package gg.lode.testkit;

import gg.lode.testkit.bot.BotLoader;
import gg.lode.testkit.bot.FakePlayer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Joins a bot to a running offline-mode server. Only runs when {@code BOT_SMOKE_HOST} (host:port) is set,
 * since it needs a live server.
 */
@EnabledIfEnvironmentVariable(named = "BOT_SMOKE_HOST", matches = ".+")
class BotSmokeTest {

    @Test
    void joinsChatsAndRunsCommands() {
        String[] target = System.getenv("BOT_SMOKE_HOST").split(":");
        assertEquals("26.3", BotLoader.driver().minecraftVersion());
        try (FakePlayer bot = BotLoader.driver().connect(target[0], Integer.parseInt(target[1]), "TestKitBot", Duration.ofSeconds(60))) {
            assertTrue(bot.isConnected());
            bot.command("plugins");
            String reply = bot.awaitMessage(m -> m.contains("Plugins"), Duration.ofSeconds(10));
            System.out.println("[BotSmokeTest] /plugins -> " + reply);
            bot.chat("hello from the TestKit");
            bot.awaitMessage(m -> m.contains("hello from the TestKit"), Duration.ofSeconds(10));
            System.out.println("[BotSmokeTest] messages: " + bot.messages());
        }
    }
}
