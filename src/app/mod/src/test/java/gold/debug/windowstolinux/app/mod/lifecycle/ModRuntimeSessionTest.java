package gold.debug.windowstolinux.app.mod.lifecycle;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;

import javax.tools.ToolProvider;

import gold.debug.windowstolinux.app.mod.loader.ModClassLoader;
import gold.debug.windowstolinux.app.mod.manifest.ManifestReader;
import gold.debug.windowstolinux.app.mod.version.SemanticVersion;
import gold.debug.windowstolinux.appapi.mod.*;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.appapi.ui.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModRuntimeSessionTest {
    @TempDir
    Path root;

    static final String ENTRY = """
            package example.fixture;
            import gold.debug.windowstolinux.appapi.mod.*;
            import gold.debug.windowstolinux.appapi.ui.*;
            public final class Mod implements ModEntrypoint {
              public static int instances, starts, stops;
              public static boolean failStart, failStop;
              public static StopReason stoppedBecause;
              private PageRegistration registration;
              public Mod(){instances++;}
              public void start(ModContext context) throws Exception {
                if(javax.swing.SwingUtilities.isEventDispatchThread())throw new AssertionError("EDT lifecycle");
                starts++;registration=context.ui().register(new PageSpec("main","title","Fixture",false),()->{throw new AssertionError("must stay lazy");});
                if(failStart)throw new IllegalStateException("fixture start failed");
              }
              public void stop(StopContext context) throws Exception {
                if(javax.swing.SwingUtilities.isEventDispatchThread())throw new AssertionError("EDT lifecycle");
                stops++;stoppedBecause=context.reason();if(failStop)throw new IllegalStateException("fixture stop failed");
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

            public void open(String id) {
                throw new AssertionError("Lifecycle must not open business pages");
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
