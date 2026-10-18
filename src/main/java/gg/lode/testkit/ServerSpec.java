package gg.lode.testkit;

/**
 * One CI test server from the TestKit config.
 *
 * @param id       what tests refer to it by, e.g. {@code 1.21.6}, {@code folia}, {@code velocity}
 * @param uuid     the panel's server identifier (the short id in panel URLs works too)
 * @param host     address players and the bot connect to
 * @param port     game port
 * @param platform {@code paper}, {@code folia} or {@code velocity}
 * @param mc       Minecraft version the server runs
 */
public record ServerSpec(String id, String uuid, String host, int port, String platform, String mc) {

    public boolean isProxy() {
        return "velocity".equalsIgnoreCase(platform);
    }
}
