package gold.debug.windowstolinux.mod.deployment.service.standard;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JComponent;
import javax.tools.ToolProvider;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.app.db.server.StoredServerProfile;
import gold.debug.windowstolinux.app.db.task.TaskLedger;
import gold.debug.windowstolinux.app.db.task.TaskRecoveryGraph;
import gold.debug.windowstolinux.app.main.start.PlatformRuntime;
import gold.debug.windowstolinux.app.mod.dev.DevelopmentInfo;
import gold.debug.windowstolinux.app.service.mod.PlatformModCatalog;
import gold.debug.windowstolinux.app.service.mod.PlatformModIntents;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.ui.mod.ModPageRegistry;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.mod.service.AvailabilityState;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.appapi.ui.page.PageSpec;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import gold.debug.windowstolinux.mod.deployment.file.git.GitRuntimeTestBridge;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.contract.DeploymentStandardInputs;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Runs complete local publication with each unavailable optional Git activation. / 在每种可选 Git 不可用启用状态下运行完整本地发布。 */
class DeploymentOptionalGitLocalTest extends DeploymentPublishAcceptanceFixture {
    private static final String GIT = "gold.debug.windowstolinux.mod.git";

    private Path isolatedWorkspace;

    @Override
    protected Path workspace() {
        return Objects.requireNonNull(isolatedWorkspace);
    }

    @ParameterizedTest
    @EnumSource(value = AvailabilityState.class, names = {"MISSING", "DISABLED", "INCOMPATIBLE", "START_FAILED"})
    void unavailableOptionalGitStillCompletesLocalSourceBuildPublicationAndBusinessCommit(AvailabilityState state)
        throws Exception {
        prepareWorkspace(state);
        source(1);
        enableFixture(state);
        try (var runtime = open()) {
            var report = boot(runtime);
            assertEquals(state == AvailabilityState.INCOMPATIBLE ? Set.of(OWNER, GIT) : Set.of(OWNER), report.running());
            if (state == AvailabilityState.START_FAILED)
                assertEquals(1, report.problems().size());
            else
                assertTrue(report.problems().isEmpty(), report.problems().toString());
            var original = runtime.modContexts().get(OWNER).context();
            assertEquals(state, original.services().availability(GIT).state());
            if (state == AvailabilityState.MISSING)
                assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM mod_registry WHERE mod_id='" + GIT + "'"));
            runtime.servers().saveServerProfile(ServerProfile.fromStored(
                    new StoredServerProfile("server", "127.0.0.1", 22, "root", "fixture-password")),
                "fixture-password".toCharArray());
            var peer = new DeploymentAssistedBuildPeer(false);
            peer.publicationAttempts = true;
            var running = new HashSet<String>();
            var bound = DeploymentPublishedApplicationStopTest.bind(original, peer, running);
            var graph = new AtomicReference<TaskRecoveryGraph>();
            var reviewer = observingReviewer(runtime, graph);
            var workflow = GitRuntimeTestBridge.staticWorkflow(runtime, bound);
            var handle = workflow.submit(new ServerId("server"),
                new DeploymentStandardInputs.Draft("", 65536, 100, 65536), Optional.empty(), reviewer);
            var result = handle.completion().toCompletableFuture().get(60, TimeUnit.SECONDS);
            assertEquals(TaskResult.SUCCEEDED, result.outcome(), result.problem().toString());
            assertEquals(TaskState.ENDED, handle.snapshot().state());
            assertNotNull(graph.get());
            assertEquals(Set.of(OWNER), graph.get().releases().stream().map(TaskRecoveryGraph.Release::owner)
                .collect(java.util.stream.Collectors.toSet()));
            assertTrue(graph.get().edges().isEmpty());
            assertEquals(1, running.size());
            assertEquals(running, peer.current.keySet());
            assertTrue(peer.present.isEmpty());
            assertEquals(peer.sessionsOpened, peer.sessionsClosed);
            assertTrue(peer.builds.values().stream().anyMatch(count -> count > 0));
            assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
            assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task"));
            var rows = DeploymentPublishedApplicationStopTest.business(runtime, original.db().identity(), 1);
            assertEquals(7, rows.size());
            assertEquals(state, original.services().availability(GIT).state());
        }
    }

    private DeploymentStandardInputs.Reviewer observingReviewer(PlatformRuntime runtime,
                                                                AtomicReference<TaskRecoveryGraph> observed) {
        var delegate = reviewer(1);
        return (DeploymentStandardInputs.Reviewer) Proxy.newProxyInstance(
            DeploymentStandardInputs.Reviewer.class.getClassLoader(),
            new Class<?>[]{DeploymentStandardInputs.Reviewer.class}, (proxy, method, args) -> {
                if (args != null)
                    for (Object argument : args)
                        if (argument instanceof TaskContext task) {
                            var actual = new TaskLedger(runtime.persistence().database()).queries()
                                .recoveryGraph(task.id()).orElseThrow();
                            assertEquals(Set.of(OWNER), actual.releases().stream().map(TaskRecoveryGraph.Release::owner)
                                .collect(java.util.stream.Collectors.toSet()));
                            assertTrue(actual.edges().isEmpty());
                            observed.set(actual);
                        }
                try {
                    return method.invoke(delegate, args);
                } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                }
            });
    }

    private void enableFixture(AvailabilityState state) throws Exception {
        try (var runtime = open()) {
            assertTrue(new PlatformModCatalog(runtime.persistence().database()).refresh(workspace()).rejections().isEmpty());
            var report = boot(runtime);
            assertTrue(report.running().isEmpty());
            assertTrue(report.problems().isEmpty(), report.problems().toString());
            var intents = runtime.modIntents();
            intents.apply(intents.review(OWNER, new PlatformModIntents.Choice(true, Optional.of(VERSION)),
                Optional.empty()), false);
            if (state == AvailabilityState.INCOMPATIBLE || state == AvailabilityState.START_FAILED)
                intents.apply(intents.review(GIT, new PlatformModIntents.Choice(true,
                    Optional.of(state == AvailabilityState.INCOMPATIBLE ? "2.0.0" : "1.0.0")), Optional.empty()), false);
        }
    }

    private gold.debug.windowstolinux.app.main.mod.PlatformModFlow.Report boot(PlatformRuntime runtime)
        throws Exception {
        var pages = new ModPageRegistry(new ModPageRegistry.PageHost() {
            public void show(ModPageRegistry.PageKey key, PageSpec spec, JComponent component) {
                throw new AssertionError("No page should be mounted by a publication fixture");
            }

            public void focus(ModPageRegistry.PageKey key) {
            }

            public void remove(ModPageRegistry.PageKey key) {
            }

            public void environment(UiEnvironment environment) {
            }
        }, new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false));
        return runtime.startDevelopmentMods(workspace(), pages);
    }

    private void prepareWorkspace(AvailabilityState state) throws Exception {
        Path actual = super.workspace();
        isolatedWorkspace = Files.createDirectories(root.resolve("workspace")).toRealPath();
        for (String anchor : List.of("pom.xml", "src/app/pom.xml", "src/mod/pom.xml"))
            copy(actual.resolve(anchor), isolatedWorkspace.resolve(anchor));
        module(actual, "deployment", state);
        if (state != AvailabilityState.MISSING)
            module(actual, "git", state);
    }

    private void module(Path actual, String name, AvailabilityState state) throws Exception {
        Path before = actual.resolve("src/mod/" + name);
        var descriptor = DevelopmentInfo.read(actual, before.resolve("target/wtlmod-development.json"));
        Path module = isolatedWorkspace.resolve("src/mod/" + name);
        Path classes = module.resolve("target/classes");
        copyTree(descriptor.mainOutput(), classes);
        copy(before.resolve("mod.json"), module.resolve("mod.json"));
        var inputs = new ArrayList<Path>();
        for (var original : descriptor.buildInputs()) {
            Path target = isolatedWorkspace.resolve(actual.relativize(original));
            copy(original, target);
            inputs.add(target);
        }
        var libraries = new ArrayList<Path>();
        for (var original : descriptor.libraries()) {
            Path target = module.resolve("target/private-libraries").resolve(original.getFileName());
            copy(original, target);
            libraries.add(target);
        }
        var outputs = new ArrayList<Path>();
        for (int index = 0; index < descriptor.authorOutputs().size(); index++) {
            Path target = module.resolve("target/author-output-" + index);
            copyTree(descriptor.authorOutputs().get(index), target);
            outputs.add(target);
        }
        if (name.equals("git")) {
            var json = new ObjectMapper();
            var manifest = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(Files.readString(module.resolve("mod.json")));
            if (state == AvailabilityState.INCOMPATIBLE)
                manifest.put("version", "2.0.0");
            if (state == AvailabilityState.START_FAILED) {
                Path source = module.resolve("Mod.java");
                Files.writeString(source, """
                    package gold.debug.windowstolinux.mod.git;
                    import gold.debug.windowstolinux.appapi.mod.*;
                    import gold.debug.windowstolinux.appapi.mod.lifecycle.StopContext;
                    public final class Mod implements ModEntrypoint {
                        public void start(ModContext context) { throw new IllegalStateException("Actual fixture startup failed"); }
                        public void stop(StopContext context) { }
                    }
                    """);
                String api = Path.of(ModContext.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
                assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "21", "-proc:none",
                    "-encoding", "UTF-8", "-classpath", api, "-d", classes.toString(), source.toString()));
            }
            Files.writeString(module.resolve("mod.json"), manifest.toString());
        }
        DevelopmentInfo.generate(isolatedWorkspace, module.resolve("mod.json"), classes, outputs, inputs, libraries);
    }

    private static void copy(Path from, Path to) throws Exception {
        Files.createDirectories(to.getParent());
        Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void copyTree(Path from, Path to) throws Exception {
        try (var files = Files.walk(from)) {
            for (var source : files.toList()) {
                Path target = to.resolve(from.relativize(source));
                if (Files.isDirectory(source))
                    Files.createDirectories(target);
                else
                    copy(source, target);
            }
        }
    }
}
