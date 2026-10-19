package gg.lode.testkit;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * A Lodestone cloud loader re-keyed for a test run: the loader jar with its embedded AES key, Ed25519 public key and
 * loader token swapped for throwaway ones, plus the impl jar sealed into a blob with those keys. lode.gg rejects the
 * throwaway token, so the loader falls back to the blob in the server's {@code .cache} folder and boots the impl under
 * test. The real release keys are never needed.
 *
 * @param loaderJar the re-keyed loader
 * @param blob      the sealed impl, for the server's {@code .cache} folder
 * @param name      the loader's plugin name, which is also its data folder
 */
record LoaderBundle(Path loaderJar, Path blob, String name) {

    private static final String AES_KEY = "cloud/aes.key";
    private static final String ED_PUB = "cloud/ed25519-public.key";
    private static final String TOKEN = "cloud/loader-token.key";
    private static final Set<String> KEYS = Set.of(AES_KEY, ED_PUB, TOKEN);
    private static final Pattern NAME = Pattern.compile("(?m)^name:\\s*['\"]?([^'\"\\s]+)");
    private static final Map<String, LoaderBundle> BUILT = new ConcurrentHashMap<>();

    /** Builds once per loader/impl/magic for the whole test run. */
    static LoaderBundle of(Path loader, Path impl, String magic) {
        String key = loader.toAbsolutePath() + "|" + impl.toAbsolutePath() + "|" + magic;
        return BUILT.computeIfAbsent(key, k -> build(loader, impl, magic));
    }

    private static LoaderBundle build(Path loader, Path impl, String magic) {
        try {
            SecureRandom random = new SecureRandom();
            KeyGenerator aes = KeyGenerator.getInstance("AES");
            aes.init(256, random);
            byte[] aesKey = aes.generateKey().getEncoded();
            KeyPair ed = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            byte[] token = new byte[32];
            random.nextBytes(token);

            Path dir = Files.createTempDirectory("testkit-loader");
            dir.toFile().deleteOnExit();
            Path jar = dir.resolve(loader.getFileName());
            String name = rekey(loader, jar, Map.of(
                    AES_KEY, aesKey,
                    ED_PUB, ed.getPublic().getEncoded(),
                    TOKEN, HexFormat.of().formatHex(token).getBytes(StandardCharsets.UTF_8)));
            Path blob = dir.resolve("testkit-impl.bin");
            Files.write(blob, seal(Files.readAllBytes(impl), aesKey, ed, magic, random));
            return new LoaderBundle(jar, blob, name);
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("Could not prepare loader " + loader.getFileName() + " for " + impl.getFileName(), e);
        }
    }

    /** Copies the jar with the key resources replaced, and returns the plugin name from its descriptor. */
    private static String rekey(Path from, Path to, Map<String, byte[]> keys) throws IOException {
        String name = null;
        int replaced = 0;
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(from));
             ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(to))) {
            for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
                byte[] data = in.readAllBytes();
                if (KEYS.contains(entry.getName())) {
                    data = keys.get(entry.getName());
                    replaced++;
                }
                if (entry.getName().equals("plugin.yml")) {
                    Matcher m = NAME.matcher(new String(data, StandardCharsets.UTF_8));
                    if (m.find()) name = m.group(1);
                }
                out.putNextEntry(new ZipEntry(entry.getName()));
                out.write(data);
                out.closeEntry();
            }
        }
        if (replaced != KEYS.size()) {
            throw new IllegalStateException(from.getFileName() + " is not a Lodestone cloud loader (no " + KEYS + ")");
        }
        if (name == null) throw new IllegalStateException(from.getFileName() + " has no plugin.yml name");
        return name;
    }

    /**
     * The loader blob envelope, big-endian: magic(8) | format(4) | nonce(12) | ctLen(4) | AES-GCM ciphertext |
     * sigLen(4) | Ed25519 signature over everything before sigLen.
     */
    private static byte[] seal(byte[] jar, byte[] aesKey, KeyPair ed, String magic, SecureRandom random)
            throws GeneralSecurityException {
        byte[] head = new byte[8];
        byte[] tag = magic.getBytes(StandardCharsets.UTF_8);
        if (tag.length > head.length) throw new IllegalArgumentException("Blob magic longer than 8 bytes: " + magic);
        System.arraycopy(tag, 0, head, 0, tag.length);
        byte[] nonce = new byte[12];
        random.nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new GCMParameterSpec(128, nonce));
        byte[] ciphertext = cipher.doFinal(jar);
        ByteBuffer signed = ByteBuffer.allocate(head.length + 4 + nonce.length + 4 + ciphertext.length).order(ByteOrder.BIG_ENDIAN);
        signed.put(head).putInt(1).put(nonce).putInt(ciphertext.length).put(ciphertext);
        Signature sig = Signature.getInstance("Ed25519");
        sig.initSign(ed.getPrivate());
        sig.update(signed.array());
        byte[] signature = sig.sign();
        return ByteBuffer.allocate(signed.capacity() + 4 + signature.length).order(ByteOrder.BIG_ENDIAN)
                .put(signed.array()).putInt(signature.length).put(signature).array();
    }
}
