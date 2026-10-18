package gg.lode.testkit;

import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A test that runs once per server listed in the class's {@link LodestoneServers}, receiving a
 * {@link TestServer} parameter for that server.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@TestTemplate
@ExtendWith(LodestoneServerExtension.class)
public @interface ServerTest {
}
