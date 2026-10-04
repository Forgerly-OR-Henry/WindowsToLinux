package gold.debug.windowstolinux.mod.deployment.service.standard;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.*;
import java.lang.reflect.Proxy;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import com.sun.net.httpserver.HttpServer;
import gold.debug.windowstolinux.app.ai.model.AiCapabilityType;
import gold.debug.windowstolinux.app.ai.model.AiPurposeAssignment;
import gold.debug.windowstolinux.app.ai.model.AiPurposeType;
import gold.debug.windowstolinux.app.db.ai.StoredAiModelProfile;
import gold.debug.windowstolinux.app.db.server.StoredServerProfile;
import gold.debug.windowstolinux.app.main.start.PlatformRuntime;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.appapi.ai.AiService;
import gold.debug.windowstolinux.appapi.linux.LinuxService;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.appapi.task.run.TaskHandle;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.file.*;
import gold.debug.windowstolinux.mod.deployment.service.core.DeploymentRefFlow;
import gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigSnapshot;
import gold.debug.windowstolinux.mod.deployment.service.core.model.*;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.DeploymentStandardAnalyzer;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.component.DiscoveredProjectPart;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.DeploymentStandardPlanner;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.contract.*;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.database.DeploymentPreparedDatabase;
import org.junit.jupiter.api.Test;

/** Preserves local static boundaries through actual CLASS/H2 tasks without the retired facade. / 经真实 CLASS/H2 任务保留本地静态边界，不恢复旧门面。 */
class DeploymentRetainedModeContractsTest extends DeploymentRuntimeFixture {
    private final AtomicInteger selections = new AtomicInteger();

    @Override
    protected Path selectedSourceDirectory() {
        selections.incrementAndGet();
        return root.resolve("input");
    }

    @Test
    void staticReviewIgnoresConfiguredModelInventoryAndMakesNoHttpOrSshCall() throws Exception {
        source();
        enable();
        var httpCalls = new AtomicInteger();
        var http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newSingleThreadExecutor();
        http.setExecutor(executor);
        http.createContext("/chat", exchange -> {
            httpCalls.incrementAndGet();
            exchange.getRequestBody().close();
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        http.start();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            model(runtime, "configured", "http://127.0.0.1:" + http.getAddress().getPort() + "/chat");
            runtime.persistence().aiProfiles().purposes().save(AiPurposeType.DEPLOYMENT,
                List.of(new AiPurposeAssignment("configured", true)));
            assertEquals(List.of("configured"), runtime.persistence().aiProfiles().listConfigured().stream()
                .map(value -> value.profile().id()).toList());
            var aiCalls = new AtomicInteger();
            var connections = new AtomicInteger();
            var actual = runtime.modContexts().get(OWNER).context();
            var ai = counted(AiService.class, actual.ai(), aiCalls);
            var linux = counted(LinuxService.class, actual.linux(), connections);
            var bound = (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(),
                new Class<?>[]{ModContext.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("ai"))
                        return ai;
                    if (method.getName().equals("linux"))
                        return linux;
                    return invoke(actual, method, arguments);
                });
            var reviews = new AtomicInteger();
            var workflow = new DeploymentStaticWorkflow(bound, new DeploymentRefFlow(bound));
            var handle = workflow.submit(new ServerId("server"), draft(), refusalReviewer(reviews));
            var result = handle.completion().toCompletableFuture().get(30, TimeUnit.SECONDS);
            assertEquals(TaskResult.CANCELLED, result.outcome(), result.toString());
            assertEquals(TaskState.ENDED, handle.snapshot().state());
            assertEquals(1, reviews.get());
            assertEquals(1, selections.get());
            assertEquals(0, aiCalls.get(), "Static review must not capture a model route or call a model");
            assertEquals(0, httpCalls.get());
            assertEquals(0, connections.get());
            assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
        } finally {
            http.stop(0);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void originalUnknownRootCheckpointBlocksAnotherStaticTaskOnTheExactSameServerUntilRecovery() throws Exception {
        source();
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            var context = runtime.modContexts().get(OWNER).context();
            try (var registration = context.tasks().registerRecovery("legacy-occupied", (previous, task) -> {
                assertEquals(previous.id(), task.id());
                var checkpoint = context.tasks().findCheckpoint(task, "occupancy").orElseThrow();
                assertEquals(new TaskCheckpoint.Snapshot(0, "{\"original\":true}", false), checkpoint.snapshot());
                checkpoint.close();
                return new OperationResult<Void>(TaskResult.FAILED, Optional.empty(),
                    Optional.of(new Problem(ProblemCode.IO_FAILURE, "TEST_ORIGINAL_ABANDONED",
                        "Original local work explicitly abandoned", Map.of(), Optional.empty())));
            })) {
                var original = context.tasks().submit(
                    new TaskRequest("legacy-occupied", "Original retained work", List.of("server"), true), task -> {
                        context.tasks().beginCheckpoint(task, "occupancy", "{\"original\":true}");
                        return new OperationResult<Void>(TaskResult.UNKNOWN, Optional.empty(), Optional.empty());
                    });
                awaitUnknown(original);
                var retained = leases(runtime, original.id());
                assertFalse(retained.isEmpty());
                var workflow = new DeploymentStaticWorkflow(context, new DeploymentRefFlow(context));
                var waiting = workflow.submit(new ServerId("server"), draft(), refusalReviewer(new AtomicInteger()));
                try {
                    assertEquals(TaskState.QUEUED, waiting.snapshot().state());
                    assertFalse(waiting.completion().toCompletableFuture().isDone());
                    assertEquals(TaskState.UNKNOWN, original.snapshot().state());
                    assertTrue(original.snapshot().outcome().isEmpty());
                    assertEquals(retained, leases(runtime, original.id()),
                        "Queued work cannot replace the original UUIDs or checkpoint facts");
                    assertEquals(0, selections.get(),
                        "Occupied original server prevents the queued callback from selecting source");
                } finally {
                    var settled = reconcileReady(runtime, original);
                    assertEquals(original.id(), settled.id());
                    assertEquals(TaskState.ENDED, settled.state());
                    assertEquals(Optional.of(TaskResult.FAILED), settled.outcome());
                    assertEquals(TaskResult.CANCELLED,
                        waiting.completion().toCompletableFuture().get(30, TimeUnit.SECONDS).outcome());
                }
                assertEquals(TaskState.ENDED, waiting.snapshot().state());
                assertEquals(1, selections.get());
                assertEquals(0,
                    scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
            }
        }
    }

    @Test
    void realFrozenSourceRequestRejectsRootBuildBeforePlanningOrRemoteEffects() throws Exception {
        source();
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            var context = runtime.modContexts().get(OWNER).context();
            var failure = new AtomicReference<Throwable>();
            var handle = context.tasks().submit(
                new TaskRequest("legacy-root-request", "Review restricted build", List.of("server"), true),
                task -> {
                    try {
                        var selected = context.file().sources().selectDirectory("Actual source", task);
                        try (var material = new DeploymentSourceSetup(context.file()).prepare(selected, 65536,
                            100, task);
                             var archive = new DeploymentSourceArchiver(context.file()).prepare(material,
                                 65536, task)) {
                            var run = new DeploymentRuntimeDefinition.GoService("1.24", "demo", "main.go",
                                new DeploymentHealthCheck.Tcp(8080, 10, 2));
                            var analyzed = new DeploymentStandardAnalyzer().analyze(material, "demo",
                                DeploymentProjectKind.GO_SERVICE, Optional.empty(), false, task);
                            assertEquals(TaskResult.SUCCEEDED, analyzed.outcome(), analyzed.toString());
                            var facts = analyzed.value().orElseThrow().assessment().facts().orElseThrow();
                            var config = DeploymentConfigSnapshot.create(facts.applicationId(), 1, "v1",
                                Instant.now(), List.of());
                            var consent = new DeploymentReviewConsent(facts.applicationId(), "server",
                                material.snapshot().sha256(), archive.sha256(), Instant.now());
                            var limits = DeploymentBuildLimits.defaultNonRoot();
                            var resources = new DeploymentResourceBindings(List.of(), Optional.of(List.of()));
                            var safe = new ReviewedDeploymentRequest(new ServerId("server"), facts, material,
                                archive, config, List.of(), resources, run, Optional.empty(), limits, consent,
                                false, true);
                            assertEquals(TaskResult.SUCCEEDED, new DeploymentStandardPlanner()
                                .plan(material, analyzed.value().orElseThrow(), safe, task).outcome());
                            var unsafe = new DeploymentBuildLimits(limits.timeoutSeconds(), limits.maxProcesses(),
                                limits.maxMemoryMiB(), limits.maxOutputBytes(), limits.maxWorkspaceBytes(),
                                true);
                            var denied = assertThrows(IllegalArgumentException.class,
                                () -> new ReviewedDeploymentRequest(safe.server(), facts, material, archive,
                                    config, List.of(), resources, run, Optional.empty(), unsafe, consent,
                                    false, true));
                            assertEquals("New deployment requires a restricted build identity",
                                denied.getMessage());
                            assertEquals(limits, safe.limits());
                            assertFalse(safe.limits().runAsRoot());
                        }
                        return new OperationResult<Void>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
                    } catch (Exception | AssertionError error) {
                        failure.set(error);
                        throw error;
                    }
                });
            var result = handle.completion().toCompletableFuture().get(30, TimeUnit.SECONDS);
            if (failure.get() != null)
                throw new AssertionError("Original reviewed request failed", failure.get());
            assertEquals(TaskResult.SUCCEEDED, result.outcome(), result.toString());
            try (var access = scope(runtime, true);
                 var connection = access.openConnection();
                 var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT COUNT(*) FROM deployment_execution")) {
                assertTrue(rows.next());
                assertEquals(0, rows.getLong(1));
            }
            assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
        }
    }

    private void source() throws Exception {
        Path directory = root.resolve("input");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("go.mod"), "module example.test/demo\n\ngo 1.24\n");
        Files.writeString(directory.resolve("go.sum"), "example.test/dependency v1 h1:fixture\n");
        Files.writeString(directory.resolve("main.go"), "package main\nfunc main() {}\n");
    }

    private static void server(PlatformRuntime runtime) throws Exception {
        runtime.servers().saveServerProfile(
            ServerProfile
                .fromStored(new StoredServerProfile("server", "127.0.0.1", 22, "root", "fixture-password")),
            "fixture-password".toCharArray());
    }

    private static void model(PlatformRuntime runtime, String id, String endpoint) throws Exception {
        String credential = "ai/legacy/" + id;
        try (var operation = runtime.security().enterOperation(); var store = runtime.security().openCredentials()) {
            char[] key = "fixture-ai-key".toCharArray();
            try {
                store.save(credential, key);
            } finally {
                Arrays.fill(key, '\0');
            }
        }
        runtime.persistence().aiProfiles().saveVerified(
            new StoredAiModelProfile(id, endpoint, "fixture/" + id, credential), id, Instant.now(),
            AiCapabilityType.TEXT);
        runtime.persistence().aiProfiles().saveOutputLimit(id, 1600);
    }

    private static DeploymentStaticWorkflow.Draft draft() {
        return new DeploymentStaticWorkflow.Draft("demo", 65536, 100, 65536);
    }

    private static DeploymentStaticWorkflow.Reviewer refusalReviewer(AtomicInteger reviews) {
        return new DeploymentStaticWorkflow.Reviewer() {
            public OperationResult<DeploymentStaticWorkflow.Selection> select(ServerId server, String sha,
                                                                              List<DiscoveredProjectPart> discovered, TaskContext task) {
                assertEquals(1, discovered.size());
                return success(new DeploymentStaticWorkflow.Selection("demo",
                    Map.of(discovered.getFirst().id(),
                        new DeploymentStaticWorkflow.Choice(DeploymentProjectKind.GO_SERVICE, Set.of())),
                    Optional.empty()));
            }

            public OperationResult<Map<String, DeploymentStaticWorkflow.Answers>> review(ServerId server, String sha,
                                                                                         List<DeploymentStaticWorkflow.Prompt> prompts, TaskContext task) {
                assertEquals(1, prompts.size());
                reviews.incrementAndGet();
                var prompt = prompts.getFirst();
                var values = new HashMap<>(prompt.defaults());
                values.put("primary", prompt.applicationId());
                values.put("secondary", "main.go");
                values.put("version", "1.24");
                values.put("port", "8080");
                values.put("healthMode", "TCP");
                values.put("exposure", "INTERNAL");
                return success(Map.of(prompt.componentId(), new DeploymentStaticWorkflow.Answers(values, List.of(),
                    DeploymentBuildLimits.defaultNonRoot(), false, true)));
            }

            public OperationResult<Boolean> confirm(ServerId server, String sha, Map<String, String> archives,
                                                    Map<String, DeploymentStaticWorkflow.Answers> answers,
                                                    Map<String, DeploymentPreparedDatabase> databases, Optional<DeploymentStaticWorkflow.GitReview> git,
                                                    TaskContext task) {
                assertEquals(1, archives.size());
                assertEquals(archives.keySet(), answers.keySet());
                assertEquals(archives.keySet(), databases.keySet());
                assertTrue(databases.values().stream()
                    .allMatch(value -> value.bindings().isEmpty() && value.schemaReview().isEmpty()));
                return new OperationResult<>(TaskResult.CANCELLED, Optional.empty(), Optional.empty());
            }
        };
    }

    private static <T> T counted(Class<T> type, T actual, AtomicInteger calls) {
        return type.cast(
            Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, arguments) -> {
                if (type == AiService.class || method.getName().equals("connect"))
                    calls.incrementAndGet();
                return invoke(actual, method, arguments);
            }));
    }

    private static Object invoke(Object actual, Method method, Object[] arguments) throws Throwable {
        try {
            return method.invoke(actual, arguments);
        } catch (InvocationTargetException wrapped) {
            throw wrapped.getCause();
        }
    }

    private static void awaitUnknown(TaskHandle<?> task) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (task.snapshot().state() != TaskState.UNKNOWN && System.nanoTime() < deadline)
            Thread.sleep(25);
        assertEquals(TaskState.UNKNOWN, task.snapshot().state(), task.snapshot().toString());
    }

    private static List<List<String>> leases(PlatformRuntime runtime, UUID task) throws Exception {
        try (var connection = runtime.persistence().database().openConnection();
             var statement = connection.prepareStatement(
                 "SELECT lease_id,resource_kind,resource_id,owner_id,access_mode,recovery_json FROM platform_task_resource WHERE task_id=? AND released_at IS NULL ORDER BY lease_id")) {
            statement.setObject(1, task);
            try (var rows = statement.executeQuery()) {
                var result = new ArrayList<List<String>>();
                while (rows.next()) {
                    var fields = new ArrayList<String>();
                    for (int column = 1; column <= 6; column++)
                        fields.add(rows.getString(column));
                    result.add(List.copyOf(fields));
                }
                return List.copyOf(result);
            }
        }
    }

    private static <T> OperationResult<T> success(T value) {
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(value), Optional.empty());
    }
}
