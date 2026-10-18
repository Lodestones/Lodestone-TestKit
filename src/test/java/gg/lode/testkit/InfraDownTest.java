package gg.lode.testkit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Events;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/** A panel that can't be reached must skip every server invocation with a warning, never fail the build. */
@DisabledIfEnvironmentVariable(named = "LODESTONE_TESTKIT_LIVE", matches = ".+")
class InfraDownTest {

    @Test
    void unreachablePanelSkipsEveryServer() throws IOException {
        Events tests = EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(Fixture.class))
                .execute()
                .testEvents();
        tests.assertStatistics(s -> s.started(2).aborted(2).failed(0).succeeded(0));

        Path warnings = Path.of(System.getenv("LODESTONE_TESTKIT_WARNINGS"));
        assertTrue(Files.readString(warnings).contains("unavailable"), "expected a CI warning in " + warnings);
    }

    @Tag("fixture")
    @LodestoneServers(plugins = {})
    static class Fixture {
        @ServerTest
        void boots(TestServer server) {
            throw new AssertionError("should have been skipped before reaching the test body");
        }
    }
}
