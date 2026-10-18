package gg.lode.testkit;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Folia and Velocity are opt-in per class; this covers both against the real CI servers. */
@EnabledIfEnvironmentVariable(named = "LODESTONE_TESTKIT_LIVE", matches = ".+")
@LodestoneServers(servers = {"folia-1.21.11", "velocity-3.5"}, plugins = "modrinth:viaversion")
class LivePlatformsTest {

    @ServerTest
    void bootsWithPlugin(TestServer server) {
        server.assertEnabled("ViaVersion");
        server.assertNoErrorsFrom("ViaVersion");
    }
}
