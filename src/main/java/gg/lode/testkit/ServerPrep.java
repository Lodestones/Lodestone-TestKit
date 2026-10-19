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

    /**
     * Installs a Lodestone cloud loader that boots {@code impl} instead of fetching from lode.gg. The loader jar gets
     * throwaway keys and the impl is sealed with them into the server's {@code .cache} folder. lode.gg rejects the
     * throwaway token, which also keeps the loader from updating itself. Works for loaders that fall back to their
     * cache when lode.gg refuses them (Bookshelf, Amplifier, Chain, Frame, Name-Tag, Sign), not for Loader-Core
     * loaders, which treat a refusal as a licence verdict. Both arguments take the {@link LodestoneServers#plugins()} forms, so
     * {@code installLoader("lode:bookshelf", "self", "BSBLOB")} tests the published loader against the build.
     *
     * @param blobMagic the loader's blob envelope magic, such as {@code BSBLOB}
     */
    void installLoader(String loader, String impl, String blobMagic);
}
