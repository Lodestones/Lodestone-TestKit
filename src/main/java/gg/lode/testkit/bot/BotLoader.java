package gg.lode.testkit.bot;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Loads the bot from the jar nested at {@code /testkit-bot.jar}. MCProtocolLib brings its own Adventure, Netty
 * and Gson, which would clash with paper-api or MockBukkit on a plugin's test classpath, so it runs child-first
 * in its own classloader. Only {@code gg.lode.testkit.bot} (this API) and the JDK are shared.
 */
public final class BotLoader {

    private static final String SHARED = "gg.lode.testkit.bot.";
    private static final String IMPL = "gg.lode.testkit.botimpl.McplBotDriver";
    private static BotDriver driver;

    private BotLoader() {
    }

    public static synchronized BotDriver driver() {
        if (driver != null) return driver;
        try (InputStream in = BotLoader.class.getResourceAsStream("/testkit-bot.jar")) {
            if (in == null) throw new IllegalStateException("testkit-bot.jar is missing from the TestKit jar");
            Path jar = Files.createTempFile("testkit-bot", ".jar");
            jar.toFile().deleteOnExit();
            Files.copy(in, jar, StandardCopyOption.REPLACE_EXISTING);
            ClassLoader loader = new ChildFirst(new URL[]{jar.toUri().toURL()}, BotLoader.class.getClassLoader());
            driver = (BotDriver) loader.loadClass(IMPL).getDeclaredConstructor().newInstance();
            return driver;
        } catch (IOException | ReflectiveOperationException e) {
            throw new IllegalStateException("Could not load the TestKit bot: " + e, e);
        }
    }

    private static final class ChildFirst extends URLClassLoader {
        ChildFirst(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                if (name.startsWith("java.") || (name.startsWith(SHARED) && !name.startsWith("gg.lode.testkit.botimpl."))) {
                    return super.loadClass(name, resolve);
                }
                Class<?> c = findLoadedClass(name);
                if (c == null) {
                    try {
                        c = findClass(name);
                    } catch (ClassNotFoundException e) {
                        c = super.loadClass(name, false);
                    }
                }
                if (resolve) resolveClass(c);
                return c;
            }
        }
    }
}
