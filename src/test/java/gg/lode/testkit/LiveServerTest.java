package gg.lode.testkit;

import gg.lode.testkit.bot.FakePlayer;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;

/**
 * End-to-end run against the real CI servers: upload, boot, console commands and a bot join. Only runs when
 * {@code LODESTONE_TESTKIT_LIVE} is set, with a config that points at the panel.
 */
@EnabledIfEnvironmentVariable(named = "LODESTONE_TESTKIT_LIVE", matches = ".+")
@LodestoneServers(plugins = "modrinth:viaversion", fakePlayers = true)
class LiveServerTest {

    @ServerTest
    void bootsAndAnswers(TestServer server) {
        server.assertEnabled("ViaVersion");
        server.assertNoErrorsFrom("ViaVersion");
        server.run("viaversion version").assertOk();
    }

    @ServerTest
    void botJoins(TestServer server) {
        FakePlayer bot = server.join("TestKitBot");
        bot.command("plugins");
        bot.awaitMessage(m -> m.contains("ViaVersion"), Duration.ofSeconds(15));
    }
}
