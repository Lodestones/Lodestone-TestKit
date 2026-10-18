package gg.lode.testkit;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Everything that decides how a server is set up. Test classes with equal keys share one booted server;
 * a class with a different key gets the server restarted for it.
 */
record SessionKey(List<String> plugins, boolean fakePlayers, int startTimeoutSeconds, List<Method> hooks) {

    static SessionKey of(Class<?> testClass) {
        LodestoneServers cfg = testClass.getAnnotation(LodestoneServers.class);
        if (cfg == null) {
            throw new IllegalStateException(testClass.getName() + " has @ServerTest methods but no @LodestoneServers");
        }
        List<Method> hooks = new ArrayList<>();
        for (Class<?> c = testClass; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.isAnnotationPresent(BeforeServerStart.class)) continue;
                if (!Modifier.isStatic(m.getModifiers()) || m.getParameterCount() != 1
                        || m.getParameterTypes()[0] != ServerPrep.class) {
                    throw new IllegalStateException("@BeforeServerStart " + m + " must be static and take one ServerPrep");
                }
                hooks.add(m);
            }
        }
        return new SessionKey(List.of(cfg.plugins()), cfg.fakePlayers(), cfg.startTimeoutSeconds(), List.copyOf(hooks));
    }
}
