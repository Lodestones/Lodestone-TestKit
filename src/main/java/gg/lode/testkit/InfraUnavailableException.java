package gg.lode.testkit;

/**
 * The test infrastructure (panel, Wings, a server's container) could not be used. Tests that hit this are
 * skipped with a CI warning instead of failing, so a downed test server never blocks a build.
 */
public class InfraUnavailableException extends RuntimeException {

    public InfraUnavailableException(String message) {
        super(message);
    }

    public InfraUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
