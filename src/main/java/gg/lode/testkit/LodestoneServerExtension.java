package gg.lode.testkit;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.Extension;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler;
import org.junit.jupiter.api.extension.TestTemplateInvocationContext;
import org.junit.jupiter.api.extension.TestTemplateInvocationContextProvider;
import org.opentest4j.TestAbortedException;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;

/**
 * Expands each {@link ServerTest} into one invocation per configured server and injects the booted
 * {@link TestServer}. Infra problems abort (skip) the invocation with a CI warning; everything else fails it.
 */
final class LodestoneServerExtension implements TestTemplateInvocationContextProvider {

    private static final ExtensionContext.Namespace NS = ExtensionContext.Namespace.create(LodestoneServerExtension.class);

    @Override
    public boolean supportsTestTemplate(ExtensionContext context) {
        return context.getRequiredTestMethod().isAnnotationPresent(ServerTest.class);
    }

    @Override
    public Stream<TestTemplateInvocationContext> provideTestTemplateInvocationContexts(ExtensionContext context) {
        Class<?> testClass = context.getRequiredTestClass();
        SessionKey key = SessionKey.of(testClass);
        LodestoneServers cfg = testClass.getAnnotation(LodestoneServers.class);
        SessionRegistry registry;
        try {
            registry = registry(context);
        } catch (InfraUnavailableException e) {
            return Stream.of(unavailable("all", e.getMessage()));
        }
        List<ServerSpec> specs = registry.config().select(cfg.servers());
        if (specs.isEmpty()) return Stream.of(unavailable("none", "No configured test servers match " + String.join(",", cfg.servers())));
        registry.prepare(specs, key);
        return specs.stream().map(spec -> invocation(registry, spec, key));
    }

    private static SessionRegistry registry(ExtensionContext context) {
        ExtensionContext.Store store = context.getRoot().getStore(NS);
        Object existing = store.get(SessionRegistry.class);
        if (existing instanceof SessionRegistry r) return r;
        if (existing instanceof InfraUnavailableException e) throw e;
        try {
            SessionRegistry created = new SessionRegistry(TestKitConfig.load());
            store.put(SessionRegistry.class, created);
            return created;
        } catch (InfraUnavailableException e) {
            store.put(SessionRegistry.class, e);
            throw e;
        }
    }

    private static TestTemplateInvocationContext invocation(SessionRegistry registry, ServerSpec spec, SessionKey key) {
        return new TestTemplateInvocationContext() {
            @Override
            public String getDisplayName(int invocationIndex) {
                return "[" + spec.id() + "]";
            }

            @Override
            public List<Extension> getAdditionalExtensions() {
                // Await the boot in beforeEach: an abort thrown from a ParameterResolver gets wrapped and fails.
                return List.of((BeforeEachCallback) ctx -> await(registry, spec, key), new Resolver(registry, spec, key),
                        new InfraToSkip(spec), (AfterEachCallback) ctx -> {
                    // getNow throws on a failed boot, which would mask the skip.
                    CompletableFuture<ServerSession> boot = registry.future(spec, key);
                    if (boot.isDone() && !boot.isCompletedExceptionally()) boot.join().closePlayers();
                });
            }
        };
    }

    private static TestTemplateInvocationContext unavailable(String id, String reason) {
        return new TestTemplateInvocationContext() {
            @Override
            public String getDisplayName(int invocationIndex) {
                return "[" + id + "]";
            }

            @Override
            public List<Extension> getAdditionalExtensions() {
                return List.of((BeforeEachCallback) ctx -> {
                    throw skip("Test servers unavailable", reason);
                }, new ParameterResolver() {
                    @Override
                    public boolean supportsParameter(ParameterContext p, ExtensionContext e) {
                        return p.getParameter().getType() == TestServer.class;
                    }

                    @Override
                    public Object resolveParameter(ParameterContext p, ExtensionContext e) {
                        throw new IllegalStateException("unreachable: beforeEach skips first");
                    }
                });
            }
        };
    }

    private record Resolver(SessionRegistry registry, ServerSpec spec, SessionKey key) implements ParameterResolver {
        @Override
        public boolean supportsParameter(ParameterContext p, ExtensionContext e) {
            return p.getParameter().getType() == TestServer.class;
        }

        @Override
        public Object resolveParameter(ParameterContext p, ExtensionContext e) {
            return await(registry, spec, key);
        }
    }

    private static ServerSession await(SessionRegistry registry, ServerSpec spec, SessionKey key) {
        try {
            return registry.future(spec, key).get();
        } catch (ExecutionException | CompletionException ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            if (cause instanceof InfraUnavailableException infra) {
                throw skip("Test server " + spec.id() + " unavailable", infra.getMessage());
            }
            if (cause instanceof AssertionError ae) throw ae;
            throw new AssertionError("Setting up test server " + spec.id() + " failed: " + cause, cause);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw skip("Interrupted", "waiting for test server " + spec.id());
        }
    }

    private record InfraToSkip(ServerSpec spec) implements TestExecutionExceptionHandler {
        @Override
        public void handleTestExecutionException(ExtensionContext context, Throwable throwable) throws Throwable {
            if (throwable instanceof InfraUnavailableException infra) {
                throw skip("Test server " + spec.id() + " unavailable", infra.getMessage());
            }
            throw throwable;
        }
    }

    private static TestAbortedException skip(String title, String reason) {
        CiWarnings.warn(title, reason + " (tests skipped, build not failed)");
        return new TestAbortedException(title + ": " + reason);
    }
}
