package gg.lode.testkit;

import java.nio.file.Path;

/** File access to a test server while it is stopped, handed to {@link BeforeServerStart} methods. */
public interface ServerPrep {

    /** The server's config entry. */
    ServerSpec spec();

    /** Writes a text file, path relative to the server root (folders are created as needed). */
    void writeFile(String path, String content);

    /** Uploads a local file into a folder relative to the server root. */
    void upload(Path file, String folder);
}
