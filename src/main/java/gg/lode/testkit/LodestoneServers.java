package gg.lode.testkit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Configures which CI test servers a test class runs against and what gets installed on them.
 * Every {@link ServerTest} method in the class runs once per listed server.
 *
 * <pre>{@code
 * @LodestoneServers(servers = {"1.21.6", "1.21.11", "26.3"}, plugins = {"self", "lode:bookshelf"})
 * class ObserverServerTest {
 *     @ServerTest
 *     void enables(TestServer server) {
 *         server.assertEnabled("Observer");
 *     }
 * }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface LodestoneServers {

    /**
     * Server ids from the TestKit config ({@code 1.21.6}, {@code 26.3}, {@code folia}, {@code velocity}, ...).
     * Empty means every Paper server in the config.
     */
    String[] servers() default {};

    /**
     * Plugins to install, in order. See {@link PluginSource} for the accepted forms:
     * {@code self}, {@code self:<key>}, {@code file:<path>}, {@code url:<https url>}, {@code modrinth:<slug>},
     * {@code lode:<plugin id>}. {@code self} is the {@code testkit.plugin} jar, or {@code testkit.plugin.velocity} on a
     * proxy when that is set.
     */
    String[] plugins() default {"self"};

    /**
     * Set when the class uses {@link TestServer#join(String)}. Servers older than the bot's protocol then get
     * ViaVersion installed so the bot can join them.
     */
    boolean fakePlayers() default false;

    /** Seconds to wait for a server to finish starting. */
    int startTimeoutSeconds() default 240;
}
