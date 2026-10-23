package gold.debug.windowstolinux.app.mod.run;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;

import javax.tools.ToolProvider;

import gold.debug.windowstolinux.app.mod.config.ManifestReader;
import gold.debug.windowstolinux.app.mod.load.ModClassLoader;
import gold.debug.windowstolinux.app.mod.run.ModRuntimeSession;
import gold.debug.windowstolinux.app.mod.version.SemanticVersion;
import gold.debug.windowstolinux.appapi.mod.*;
import gold.debug.windowstolinux.appapi.mod.lifecycle.StopContext;
import gold.debug.windowstolinux.appapi.mod.lifecycle.StopReason;
import gold.debug.windowstolinux.appapi.mod.model.ModIdentity;
import gold.debug.windowstolinux.appapi.mod.lifecycle.ModLaunchMode;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.ui.*;
import gold.debug.windowstolinux.appapi.ui.page.PageFactory;
import gold.debug.windowstolinux.appapi.ui.page.PageRegistration;
import gold.debug.windowstolinux.appapi.ui.page.PageSpec;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModRuntimeSessionTest {
    @TempDir
    Path root;

    static final String ENTRY = """
        package example.fixture;
        import gold.debug.windowstolinux.appapi.mod.ModContext; import gold.debug.windowstolinux.appapi.mod.ModEntrypoint; import gold.debug.windowstolinux.appapi.mod.lifecycle.StopContext; import gold.debug.windowstolinux.appapi.mod.lifecycle.StopReason;
        import gold.debug.windowstolinux.appapi.ui.page.PageRegistration; import gold.debug.windowstolinux.appapi.ui.page.PageSpec;
        public final class Mod implements ModEntrypoint {
          public static int instances, starts, stops;
          public static boolean failStart, failStop;
          public static Error startError, stopError, constructorError;
          public static StopReason stoppedBecause;
          private PageRegistration registration;
          public Mod(){instances++;if(constructorError!=null)throw constructorError;}
          public void start(ModContext context) throws Exception {
            if(javax.swing.SwingUtilities.isEventDispatchThread())throw new AssertionError("EDT lifecycle");
            starts++;registration=context.ui().register(new PageSpec("main","title","Fixture",false),()->{throw new AssertionError("must stay lazy");});
            if(startError!=null)throw startError;
            if(failStart)throw new IllegalStateException("fixture start failed");
          }
          public void stop(StopContext context) throws Exception {
            if(javax.swing.SwingUtilities.isEventDispatchThread())throw new AssertionError("EDT lifecycle");
            stops++;stoppedBecause=context.reason();if(stopError!=null)throw stopError;if(failStop)throw new IllegalStateException("fixture stop failed");
            if(registration!=null)registration.close();
          }
        }
        """;

    ModClassLoader loader() throws Exception {
        var classes = root.resolve("classes");
        Files.createDirectories(classes);
        var source = root.resolve("Mod.java");
        Files.writeString(source, ENTRY);
        var api = Path.of(ModContext.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "21", "-proc:none",
            "-encoding", "UTF-8", "-classpath", api.toString(), "-d", classes.toString(), source.toString()));
        var manifest = ManifestReader
            .read("""
                {"formatVersion":1,"id":"example.stable","name":"fixture","version":"1.0.0","platformApiRange":"1.0.0-dev.1","minJavaVersion":21,"mainJar":"main/fixture.jar","entryClass":"example.fixture.Mod"}
                """
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new ModClassLoader(manifest, List.of(classes.toUri().toURL()), Map.of());
    }

    static class Lease implements ModRuntimeSession.ContextLease {
        final Set<String> staged = new HashSet<>();

        boolean published;

        boolean sealed;

        boolean mismatched;

        int closes;

        int closeFailures;

        List<String> blockers = List.of();

        final UiService ui = new UiService() {
            public PageRegistration register(PageSpec spec, PageFactory factory) {
                staged.add(spec.id());
                return new PageRegistration() {
                    public String id() {
                        return spec.id();
                    }

                    public void close() {
                        staged.remove(spec.id());
                    }
                };
            }

            public PageRegistration registerPreview(PageSpec spec, PageFactory factory) {
                return register(spec, factory);
            }

            public void open(String id) {
                throw new AssertionError("Lifecycle must not open business pages");
            }

            public void open(String id, gold.debug.windowstolinux.appapi.ui.page.UiNavigationRequest<?> request) {
                throw new AssertionError("Lifecycle must not open contextual pages");
            }

            public gold.debug.windowstolinux.appapi.ui.extension.UiExtensionService extensions() {
                throw new AssertionError("This lifecycle fixture does not use extensions");
            }

            public void openPlatform(gold.debug.windowstolinux.appapi.ui.page.PlatformPageKind target) {
                throw new AssertionError("Lifecycle must not open platform pages");
            }

            public UiEnvironment environment() {
                return new UiEnvironment(Locale.ENGLISH, "light", false);
            }
        };

        public ModContext context() {
            return (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(),
                new Class<?>[]{ModContext.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "identity" ->
                        new ModIdentity(mismatched ? "example.other" : "example.stable", "1.0.0", Optional.empty());
                    case "launchMode" -> ModLaunchMode.NORMAL;
                    case "ui" -> ui;
                    default -> throw new AssertionError("Unexpected capability: " + method.getName());
                });
        }

        public void commit() {
            assertFalse(sealed);
            published = true;
        }

        public void seal() {
            sealed = true;
            published = false;
        }

        public List<String> blockers() {
            assertTrue(sealed);
            return blockers;
        }

        public void close() throws Exception {
            closes++;
            if (closeFailures-- > 0)
                throw new java.io.IOException("fixture context cleanup failed");
            staged.clear();
        }
    }

    ModRuntimeSession session(ModClassLoader loader, Lease lease) {
        return new ModRuntimeSession(loader, lease, SemanticVersion.parse("1.0.0-dev.1"), (state, failure, closed) -> {
        });
    }

    int count(Class<?> entry, String name) throws Exception {
        return entry.getField(name).getInt(null);
    }

    @Test
    void journalCoversCompatibilityFailureBeforeAnyAuthorConstruction() throws Exception {
        var loader = loader();
        var entry = loader.loadClass("example.fixture.Mod");
        assertEquals(0, count(entry, "instances"));
        var lease = new Lease();
        var states = new ArrayList<ModRuntimeSession.SessionState>();
        var failureEvidence = new ArrayList<Problem>();
        var session = new ModRuntimeSession(loader, lease, SemanticVersion.parse("2.0.0"), (state, failure, closed) -> {
            states.add(state);
            if (closed)
                failureEvidence.add(failure.orElseThrow());
        });
        assertThrows(IllegalStateException.class, session::start);
        assertEquals(List.of(ModRuntimeSession.SessionState.STARTING, ModRuntimeSession.SessionState.STOPPING,
            ModRuntimeSession.SessionState.FAILED), states);
        assertEquals(0, count(entry, "instances"));
        assertEquals(1, failureEvidence.size());
        assertEquals("MOD_START_FAILED", failureEvidence.getFirst().eventCode());
        assertTrue(session.finalized());
    }

    @Test
    void failedStartupAndFinalAcknowledgementKeepOriginalProblemAndSkipCompletedCleanup() throws Exception {
        var loader = loader();
        var entry = loader.loadClass("example.fixture.Mod");
        entry.getField("failStart").setBoolean(null, true);
        var lease = new Lease();
        var finalEvidence = new ArrayList<Problem>();
        var session = new ModRuntimeSession(loader, lease, SemanticVersion.parse("1.0.0-dev.1"),
            (state, failure, closed) -> {
                if (closed) {
                    finalEvidence.add(failure.orElseThrow());
                    if (finalEvidence.size() == 1)
                        throw new java.io.IOException("Final acknowledgement unavailable");
                }
            });
        var original = assertThrows(IllegalStateException.class, session::start);
        assertEquals(1, original.getSuppressed().length);
        assertTrue(session.resourcesClosed());
        assertFalse(session.finalized());
        session.stop(StopReason.APPLICATION_EXIT);
        assertEquals(2, finalEvidence.size());
        assertEquals(finalEvidence.getFirst(), finalEvidence.getLast());
        assertEquals(1, count(entry, "stops"));
        assertEquals(1, lease.closes);
        assertEquals(ModRuntimeSession.SessionState.FAILED, session.state());
        assertTrue(session.finalized());
    }

    @Test
    void serviceConfigurationStartupErrorDrainsOriginalContributions() throws Exception {
        var loader = loader();
        var entry = loader.loadClass("example.fixture.Mod");
        var failure = new ServiceConfigurationError("author start provider unavailable");
        entry.getField("startError").set(null, failure);
        var lease = new Lease();
        var session = session(loader, lease);
        assertSame(failure, assertThrows(ServiceConfigurationError.class, session::start));
        assertEquals(ModRuntimeSession.SessionState.FAILED, session.state());
        assertEquals(1, count(entry, "starts"));
        assertEquals(1, count(entry, "stops"));
        assertEquals(1, lease.closes);
        assertTrue(lease.staged.isEmpty());
        assertTrue(session.finalized());
    }

    @Test
    void customStopErrorRetainsOriginalContextUntilSuccessfulRetry() throws Exception {
        var loader = loader();
        var entry = loader.loadClass("example.fixture.Mod");
        var failure = new Error("author stop failed");
        var lease = new Lease();
        var session = session(loader, lease);
        session.start();
        entry.getField("stopError").set(null, failure);
        assertSame(failure, assertThrows(Error.class, () -> session.stop(StopReason.APPLICATION_EXIT)));
        assertEquals(ModRuntimeSession.SessionState.FAILED, session.state());
        assertEquals(0, lease.closes);
        assertFalse(session.resourcesClosed());
        entry.getField("stopError").set(null, null);
        session.stop(StopReason.APPLICATION_EXIT);
        assertEquals(2, count(entry, "stops"));
        assertEquals(1, lease.closes);
        assertTrue(session.finalized());
    }

    @Test
    void fatalLifecycleErrorsPropagateWithRetryableOriginalOwnership() throws Exception {
        for (var fatal : List.of(new InternalError("synthetic start failure"), new ThreadDeath())) {
            var loader = loader();
            var entry = loader.loadClass("example.fixture.Mod");
            var lease = new Lease();
            var session = session(loader, lease);
            entry.getField("startError").set(null, fatal);
            assertSame(fatal, assertThrows(Error.class, session::start));
            assertEquals(ModRuntimeSession.SessionState.FAILED, session.state());
            assertFalse(session.resourcesClosed());
            assertEquals(0, lease.closes);
            assertThrows(IllegalStateException.class, session::start);
            entry.getField("startError").set(null, null);
            entry.getField("stopError").set(null, fatal);
            assertSame(fatal, assertThrows(Error.class, () -> session.stop(StopReason.APPLICATION_EXIT)));
            assertEquals(0, lease.closes);
            entry.getField("stopError").set(null, null);
            session.stop(StopReason.APPLICATION_EXIT);
            assertTrue(session.finalized());
            assertEquals(1, count(entry, "starts"));
        }
    }

    @Test
    void reflectiveFatalConstructorAndFatalCleanupAreNeverConvertedOrSuppressed() throws Exception {
        var loader = loader();
        var entry = loader.loadClass("example.fixture.Mod");
        var fatal = new InternalError("synthetic constructor failure");
        entry.getField("constructorError").set(null, fatal);
        var lease = new Lease();
        var session = session(loader, lease);
        assertSame(fatal, assertThrows(InternalError.class, session::start));
        assertEquals(ModRuntimeSession.SessionState.FAILED, session.state());
        assertEquals(0, lease.closes);
        session.stop(StopReason.APPLICATION_EXIT);
        assertTrue(session.finalized());

        loader = loader();
        entry = loader.loadClass("example.fixture.Mod");
        entry.getField("startError").set(null, new ServiceConfigurationError("startup unavailable"));
        entry.getField("stopError").set(null, fatal);
        lease = new Lease();
        session = session(loader, lease);
        var failing = session;
        assertSame(fatal, assertThrows(InternalError.class, failing::start));
        assertFalse(session.finalized());
        assertEquals(0, lease.closes);
        entry.getField("stopError").set(null, null);
        session.stop(StopReason.APPLICATION_EXIT);
        assertTrue(session.finalized());
    }

    @Test
    void successfulStartPublishesOnceAndNormalStopClosesInOrder() throws Exception {
        var loader = loader();
        var entry = loader.loadClass("example.fixture.Mod");
        var lease = new Lease();
        var session = session(loader, lease);
        session.start();
        assertEquals(ModRuntimeSession.SessionState.RUNNING, session.state());
        assertTrue(lease.published);
        assertEquals(Set.of("main"), lease.staged);
        assertEquals(1, count(entry, "starts"));
        assertThrows(IllegalStateException.class, session::start);
        session.stop(StopReason.APPLICATION_RESTART);
        session.stop(StopReason.APPLICATION_RESTART);
        assertEquals(1, count(entry, "stops"));
        assertEquals(StopReason.APPLICATION_RESTART, entry.getField("stoppedBecause").get(null));
        assertEquals(1, lease.closes);
        assertTrue(lease.staged.isEmpty());
        assertTrue(loader.isClosed());
        assertTrue(session.resourcesClosed());
        assertEquals(ModRuntimeSession.SessionState.STOPPED, session.state());
    }

    @Test
    void partialStartFailureStopsTheEntryAndRevokesUnpublishedContributions() throws Exception {
        var loader = loader();
        var entry = loader.loadClass("example.fixture.Mod");
        entry.getField("failStart").setBoolean(null, true);
        var lease = new Lease();
        var session = session(loader, lease);
        assertThrows(IllegalStateException.class, session::start);
        assertEquals(ModRuntimeSession.SessionState.FAILED, session.state());
        assertFalse(lease.published);
        assertTrue(lease.staged.isEmpty());
        assertEquals(1, count(entry, "stops"));
        assertEquals(StopReason.START_FAILURE, entry.getField("stoppedBecause").get(null));
        assertTrue(session.resourcesClosed());
        assertTrue(loader.isClosed());
    }

    @Test
    void failedAuthorStopRetainsTheContextAndLoaderForAnExplicitRetry() throws Exception {
        var loader = loader();
        var entry = loader.loadClass("example.fixture.Mod");
        var lease = new Lease();
        var session = session(loader, lease);
        session.start();
        entry.getField("failStop").setBoolean(null, true);
        assertThrows(IllegalStateException.class, () -> session.stop(StopReason.APPLICATION_EXIT));
        assertFalse(loader.isClosed());
        assertEquals(0, lease.closes);
        assertFalse(session.resourcesClosed());
        entry.getField("failStop").setBoolean(null, false);
        session.stop(StopReason.APPLICATION_EXIT);
        assertEquals(2, count(entry, "stops"));
        assertTrue(session.resourcesClosed());
    }

    @Test
    void failedContextCloseDoesNotRepeatAnAlreadySuccessfulEntryStop() throws Exception {
        var loader = loader();
        var entry = loader.loadClass("example.fixture.Mod");
        var lease = new Lease();
        lease.closeFailures = 1;
        var session = session(loader, lease);
        session.start();
        assertThrows(java.io.IOException.class, () -> session.stop(StopReason.APPLICATION_EXIT));
        assertFalse(loader.isClosed());
        assertEquals(1, count(entry, "stops"));
        session.stop(StopReason.APPLICATION_EXIT);
        assertEquals(1, count(entry, "stops"));
        assertEquals(2, lease.closes);
        assertTrue(loader.isClosed());
    }

    @Test
    void outstandingWorkSealsAdmissionButPreventsAuthorStopAndUnloading() throws Exception {
        var loader = loader();
        var entry = loader.loadClass("example.fixture.Mod");
        var lease = new Lease();
        var session = session(loader, lease);
        session.start();
        lease.blockers = List.of("task still owns a connection");
        var failure = assertThrows(ApiException.class, () -> session.stop(StopReason.APPLICATION_RESTART));
        assertEquals(ProblemCode.BUSY, failure.problem().code());
        assertTrue(lease.sealed);
        assertEquals(ModRuntimeSession.SessionState.STOPPING, session.state());
        assertEquals(0, count(entry, "stops"));
        assertFalse(loader.isClosed());
        lease.blockers = List.of();
        session.stop(StopReason.APPLICATION_RESTART);
        assertTrue(loader.isClosed());
    }

    @Test
    void wrongHostIdentityAndEdtLifecycleAreRejectedBeforeAuthorConstruction() throws Exception {
        var loader = loader();
        var entry = loader.loadClass("example.fixture.Mod");
        assertEquals(0, count(entry, "instances"));
        assertEquals(0, count(entry, "starts"));
        assertEquals(0, count(entry, "stops"));
        var lease = new Lease();
        lease.mismatched = true;
        var session = session(loader, lease);
        javax.swing.SwingUtilities.invokeAndWait(() -> assertThrows(IllegalStateException.class, session::start));
        assertEquals(ModRuntimeSession.SessionState.PREPARED, session.state());
        assertThrows(IllegalStateException.class, session::start);
        assertEquals(0, count(entry, "instances"));
        assertEquals(0, count(entry, "starts"));
        assertEquals(0, count(entry, "stops"));
        assertTrue(session.resourcesClosed());
    }
}
