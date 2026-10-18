package gg.lode.testkit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a static method taking a {@link ServerPrep}, called after the plugins are uploaded and before the
 * server starts. Use it to drop configs or a licence file in place.
 *
 * <pre>{@code
 * @BeforeServerStart
 * static void licence(ServerPrep prep) {
 *     prep.writeFile("plugins/Observer/.license", System.getenv("OBSERVER_TEST_LICENSE"));
 * }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface BeforeServerStart {
}
