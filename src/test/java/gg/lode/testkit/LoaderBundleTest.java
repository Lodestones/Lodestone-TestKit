package gg.lode.testkit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoaderBundleTest {

    @TempDir
    Path dir;

    @Test
    void blobOpensWithTheRekeyedLoadersKeys() throws Exception {
        Path loader = jar("Shelf-Loader.jar", Map.of(
                "plugin.yml", "name: Shelf\nmain: x.Loader\n",
                "cloud/aes.key", "real-aes",
                "cloud/ed25519-public.key", "real-pub",
                "cloud/loader-token.key", "real-token",
                "x/Loader.class", "bytecode"));
        Path impl = jar("Shelf-Paper.jar", Map.of("x/Impl.class", "impl"));

        LoaderBundle bundle = LoaderBundle.of(loader, impl, "BSBLOB");
        assertEquals("Shelf", bundle.name());

        Map<String, byte[]> entries = read(bundle.loaderJar());
        assertArrayEquals("bytecode".getBytes(StandardCharsets.UTF_8), entries.get("x/Loader.class"));
        assertNotEquals("real-token", new String(entries.get("cloud/loader-token.key"), StandardCharsets.UTF_8));

        // Same steps as the loaders' BlobDecryptor.
        byte[] blob = Files.readAllBytes(bundle.blob());
        ByteBuffer buf = ByteBuffer.wrap(blob);
        byte[] magic = new byte[8];
        buf.get(magic);
        assertArrayEquals("BSBLOB\0\0".getBytes(StandardCharsets.UTF_8), magic);
        assertEquals(1, buf.getInt());
        byte[] nonce = new byte[12];
        buf.get(nonce);
        byte[] ciphertext = new byte[buf.getInt()];
        buf.get(ciphertext);
        int sigLen = buf.getInt();
        assertEquals(64, sigLen);
        assertEquals(sigLen, buf.remaining());
        byte[] signature = new byte[sigLen];
        buf.get(signature);

        Signature verify = Signature.getInstance("Ed25519");
        verify.initVerify(KeyFactory.getInstance("Ed25519")
                .generatePublic(new X509EncodedKeySpec(entries.get("cloud/ed25519-public.key"))));
        verify.update(Arrays.copyOfRange(blob, 0, blob.length - 4 - sigLen));
        assertTrue(verify.verify(signature));

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(entries.get("cloud/aes.key"), "AES"), new GCMParameterSpec(128, nonce));
        assertArrayEquals(Files.readAllBytes(impl), cipher.doFinal(ciphertext));
    }

    @Test
    void refusesAJarWithoutLoaderKeys() throws Exception {
        Path plain = jar("Plain.jar", Map.of("plugin.yml", "name: Plain\n"));
        Path impl = jar("Impl.jar", Map.of("a", "b"));
        assertThrows(IllegalStateException.class, () -> LoaderBundle.of(plain, impl, "BSBLOB"));
    }

    private Path jar(String name, Map<String, String> entries) throws Exception {
        Path jar = dir.resolve(name);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(e.getKey()));
                out.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return jar;
    }

    private static Map<String, byte[]> read(Path jar) throws Exception {
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(jar))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) entries.put(e.getName(), in.readAllBytes());
        }
        return entries;
    }
}
