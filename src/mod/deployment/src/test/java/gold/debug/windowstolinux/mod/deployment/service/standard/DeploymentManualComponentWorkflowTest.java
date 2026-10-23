package gold.debug.windowstolinux.mod.deployment.service.standard;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import gold.debug.windowstolinux.app.db.server.StoredServerProfile;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.appapi.file.FileService;
import gold.debug.windowstolinux.appapi.file.snapshot.FileSnapshot;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.mod.deployment.db.publish.DeploymentGraphStore;
import gold.debug.windowstolinux.mod.deployment.file.DeploymentFileServiceFixture;
import gold.debug.windowstolinux.mod.deployment.file.git.GitRuntimeTestBridge;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.kind.DeploymentProjectKind;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.contract.DeploymentStandardInputs;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.source.DeploymentStandardSourceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Verifies manual boundaries in the real frozen-source graph and publication flow. / 验证真实冻结来源整图及发布流程的手工边界。 */
class DeploymentManualComponentWorkflowTest extends DeploymentPublishAcceptanceFixture {
    private enum InvalidBoundary {OMIT_ARTIFACT, OMIT_PORT, OVERLAP_ROOT, DEPENDENCY_CYCLE}

    /** Publishes manually named roots and persists their actual dependency order. / 发布手工命名根并持久化其真实依赖顺序。
     * @throws Exception if actual source, host or publication fails / 实际来源、宿主或发布失败时 */
    @Test
    void manualRootsRequiredArtifactsPortsAndDependenciesCompleteOriginalGraphPublication() throws Exception {
        source(2);
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            runtime.servers().saveServerProfile(ServerProfile.fromStored(
                new StoredServerProfile("server", "127.0.0.1", 22, "root", "fixture-password")), "fixture-password".toCharArray());
            var original = runtime.modContexts().get(OWNER).context();
            var peer = new DeploymentAssistedBuildPeer(false);
            peer.publicationAttempts = true;
            var running = new HashSet<String>();
            var confirmations = new AtomicInteger();
            var workflow = GitRuntimeTestBridge.staticWorkflow(runtime,
                DeploymentPublishedApplicationStopTest.bind(original, peer, running));
            var task = workflow.submit(new ServerId("server"), new DeploymentStandardInputs.Draft("manual-source", 65536, 100, 65536),
                Optional.empty(), manualReviewer(Optional.empty(), confirmations));
            var result = task.completion().toCompletableFuture().get(60, TimeUnit.SECONDS);
            assertEquals(TaskResult.SUCCEEDED, result.outcome(), result.problem().toString());
            assertEquals(TaskState.ENDED, task.snapshot().state());
            assertEquals(1, confirmations.get());
            assertEquals(Set.of("manual-app-api", "manual-app-worker"), running);
            assertEquals(running, peer.current.keySet());
            assertEquals(peer.sessionsOpened, peer.sessionsClosed);
            assertTrue(peer.present.isEmpty());
            DeploymentPublishedApplicationStopTest.business(runtime, original.db().identity(), 2);
            var read = original.tasks().submit(new TaskRequest("manual-graph-read", "Read original graph", List.of(), true),
                scope -> success(new DeploymentGraphStore(original.db()).find(scope, "manual-app").orElseThrow()));
            var graph = read.completion().toCompletableFuture().get(15, TimeUnit.SECONDS).value().orElseThrow();
            assertEquals("api", graph.healthComponentId());
            assertEquals(Map.of("api", List.of("worker"), "worker", List.of()), graph.components().stream()
                .collect(java.util.stream.Collectors.toMap(part -> part.componentId(), part -> part.dependencies())));
            assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
        }
    }

    @Test
    void preselectedProjectSaveAndCompletePublicationShareTheSameOriginalFreeze() throws Exception {
        source(2);
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            runtime.servers().saveServerProfile(ServerProfile.fromStored(
                new StoredServerProfile("server", "127.0.0.1", 22, "root", "fixture-password")), "fixture-password".toCharArray());
            var original = runtime.modContexts().get(OWNER).context();
            var snapshots = new AtomicInteger();
            var archiveSnapshots = new AtomicInteger();
            var selects = new AtomicInteger();
            var sourceDigest = new AtomicReference<String>();
            var sourceTask = new AtomicReference<UUID>();
            var rootSelection = new AtomicReference<UUID>();
            var file = DeploymentFileServiceFixture.create(FileService.class.getClassLoader(), (proxy, method, arguments) -> {
                if (method.getName().equals("selectDirectory")) selects.incrementAndGet();
                Object result;
                try {
                    result = DeploymentFileServiceFixture.invoke(method, original.file(), arguments);
                } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                }
                if (method.getName().equals("selectDirectory") && arguments[0] instanceof java.nio.file.Path)
                    assertTrue(rootSelection.compareAndSet(null, ((gold.debug.windowstolinux.appapi.file.source.FileSelection) result).id()),
                        "Only the original Root task reauthorizes the selected source");
                if (method.getName().equals("snapshot")) {
                    var selection = (gold.debug.windowstolinux.appapi.file.source.FileSelection) arguments[0];
                    var task = (gold.debug.windowstolinux.appapi.task.run.TaskContext) arguments[arguments.length - 1];
                    var frozen = (FileSnapshot) result;
                    if (selection.id().equals(rootSelection.get())) {
                        snapshots.incrementAndGet();
                        assertTrue(sourceDigest.compareAndSet(null, frozen.sha256()), "The original source is frozen exactly once");
                        sourceTask.set(task.id());
                    } else {
                        var request = (gold.debug.windowstolinux.appapi.file.snapshot.SnapshotRequest) arguments[1];
                        assertEquals(List.of("source.tar.gz"), request.relativePaths());
                        assertEquals(sourceTask.get(), task.id());
                        archiveSnapshots.incrementAndGet();
                        try (var compressed = new java.util.zip.GZIPInputStream(Files.newInputStream(frozen.directory().resolve("source.tar.gz")))) {
                            var bytes = compressed.readNBytes(65537);
                            assertTrue(bytes.length <= 65536);
                            var tar = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                            assertTrue(tar.contains("package main\nfunc main() {}\n"));
                            assertFalse(tar.contains("invalid live content after freezing"));
                        }
                    }
                }
                return result;
            });
            var local = (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(), new Class<?>[]{ModContext.class}, (proxy, method, args) -> {
                if (method.getName().equals("file")) return file;
                try {
                    return method.invoke(original, args);
                } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                }
            });
            var picked = new DeploymentStandardSourceService(local).select("Choose original source", Optional.empty());
            var metadata = picked.completion().toCompletableFuture().get(20, TimeUnit.SECONDS).value().orElseThrow();
            assertEquals(0, snapshots.get());
            assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
            var peer = new DeploymentAssistedBuildPeer(false);
            peer.publicationAttempts = true;
            var running = new HashSet<String>();
            var confirmed = new AtomicInteger();
            var delegate = manualReviewer(Optional.empty(), confirmed);
            var reviewCalls = new java.util.concurrent.CopyOnWriteArrayList<String>();
            var reviewFailure = new AtomicReference<Throwable>();
            var reviewer = (DeploymentStandardInputs.Reviewer) Proxy.newProxyInstance(DeploymentStandardInputs.Reviewer.class.getClassLoader(),
                new Class<?>[]{DeploymentStandardInputs.Reviewer.class}, (proxy, method, arguments) -> {
                    reviewCalls.add(method.getName());
                    try {
                        int digestIndex = switch (method.getName()) {
                            case "select", "review", "confirm", "reviewBuildCorrection" -> 1;
                            case "databaseInputs", "confirmSqliteInitialization" -> 2;
                            default -> -1;
                        };
                        if (digestIndex >= 0) {
                            assertNotNull(sourceDigest.get(), "A real snapshot must precede source review");
                            assertEquals(sourceDigest.get(), arguments[digestIndex], method.getName());
                        }
                        if (method.getName().equals("select"))
                            Files.writeString(selectedSourceDirectory().resolve("component0/main.go"), "invalid live content after freezing");
                        try {
                            return method.invoke(delegate, arguments);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    } catch (Throwable failure) {
                        reviewFailure.compareAndSet(null, failure);
                        throw failure;
                    }
                });
            var project = UUID.randomUUID();
            var options = new DeploymentStandardInputs.SourceOptions(Optional.of(metadata.location()), Optional.of(
                new DeploymentStandardInputs.ProjectEdit(project, "saved original source", Optional.empty(), Instant.parse("2026-10-05T00:00:00Z"))), Map.of());
            var workflow = GitRuntimeTestBridge.staticWorkflow(runtime, DeploymentPublishedApplicationStopTest.bind(local, peer, running));
            var handle = workflow.submit(new ServerId("server"), new DeploymentStandardInputs.Draft("manual-source", 65536, 100, 65536, options),
                Optional.empty(), reviewer);
            gold.debug.windowstolinux.appapi.task.result.OperationResult<Void> result;
            try {
                result = handle.completion().toCompletableFuture().get(60, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException timeout) {
                System.err.println("Original frozen publication: " + handle.snapshot() + "; reviewCalls=" + reviewCalls
                    + "; reviewerFailure=" + reviewFailure.get());
                throw timeout;
            }
            assertEquals(TaskResult.SUCCEEDED, result.outcome(), result.problem().toString());
            assertEquals(handle.id(), sourceTask.get());
            assertNotEquals(picked.id(), handle.id());
            assertEquals(1, snapshots.get());
            assertEquals(2, archiveSnapshots.get());
            assertEquals(2, selects.get());
            var rows = new DeploymentStandardSourceService(original).projects().completion().toCompletableFuture().get(20, TimeUnit.SECONDS).value().orElseThrow();
            assertEquals(1, rows.size());
            assertEquals(project, rows.getFirst().id());
            assertEquals(sourceDigest.get(), rows.getFirst().source().sha256());
            assertEquals(metadata.location(), rows.getFirst().source().location());
            DeploymentPublishedApplicationStopTest.business(runtime, original.db().identity(), 2);
            assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
            var retained = new gold.debug.windowstolinux.mod.deployment.service.feedback.DeploymentDiagnostics(original).read(handle.id());
            assertTrue(retained.problem().isEmpty(), retained.problem().toString());
            assertFalse(retained.records().isEmpty());
            assertTrue(retained.records().stream().allMatch(record -> record.event().taskId().equals(Optional.of(handle.id()))
                && record.event().operationId().equals(Optional.of(handle.id()))));
            var stages = retained.records().reversed().stream().filter(record -> record.event().eventCode().equals("DEPLOYMENT_STAGE"))
                .map(record -> record.event().message()).toList();
            var stageMessages = java.util.ResourceBundle.getBundle(
                "gold.debug.windowstolinux.mod.deployment.service.feedback.workflow-messages",
                original.ui().environment().locale(), getClass().getClassLoader(),
                java.util.ResourceBundle.Control.getNoFallbackControl(java.util.ResourceBundle.Control.FORMAT_PROPERTIES));
            String published = stageMessages.getString("stage.publishGraphComponent");
            String health = stageMessages.getString("stage.graphHealth");
            String closing = stageMessages.getString("stage.closePreparation");
            assertEquals(running.size(), stages.stream().filter(published::equals).count(),
                "Persisted publication stages must correspond to the actual two published units");
            assertEquals(1, stages.stream().filter(health::equals).count(),
                "The original whole-health stage belongs to the completed committed graph");
            assertEquals(1, stages.stream().filter(closing::equals).count(),
                "Original preparation cleanup must be persisted on the same task");
            assertTrue(stages.indexOf(closing) < stages.indexOf(published));
            assertTrue(stages.lastIndexOf(published) < stages.indexOf(health));
            String retainedText = retained.records().toString();
            assertFalse(retainedText.contains("invalid live content after freezing"));
            assertFalse(retainedText.contains("fixture-password"));
        }
    }

    /** Rejects omitted actual resources, overlapping roots and cycles before archive consent or remote mutation. / 归档同意及远端修改前拒绝遗漏实际资源、重叠根和环。
     * @param invalid actual changed manual boundary / 实际改变的手工边界
     * @throws Exception if original task evidence cannot be read / 原任务依据无法读取时 */
    @ParameterizedTest
    @EnumSource(InvalidBoundary.class)
    void invalidManualBoundariesCannotHideActualRuntimeResourcesOrPublish(InvalidBoundary invalid) throws Exception {
        source(2);
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            runtime.servers().saveServerProfile(ServerProfile.fromStored(
                new StoredServerProfile("server", "127.0.0.1", 22, "root", "fixture-password")), "fixture-password".toCharArray());
            var original = runtime.modContexts().get(OWNER).context();
            var peer = new DeploymentAssistedBuildPeer(false);
            peer.publicationAttempts = true;
            var running = new HashSet<String>();
            var confirmations = new AtomicInteger();
            var workflow = GitRuntimeTestBridge.staticWorkflow(runtime,
                DeploymentPublishedApplicationStopTest.bind(original, peer, running));
            var task = workflow.submit(new ServerId("server"), new DeploymentStandardInputs.Draft("manual-source", 65536, 100, 65536),
                Optional.empty(), manualReviewer(Optional.of(invalid), confirmations));
            var result = task.completion().toCompletableFuture().get(30, TimeUnit.SECONDS);
            assertEquals(TaskResult.FAILED, result.outcome(), result.problem().toString());
            assertEquals(invalid == InvalidBoundary.DEPENDENCY_CYCLE ? "DEPLOYMENT_STATIC_SELECTION_INVALID"
                : invalid == InvalidBoundary.OVERLAP_ROOT ? "DEPLOYMENT_STATIC_ANALYSIS_INCOMPLETE"
                : "DEPLOYMENT_STATIC_REVIEW_INVALID", result.problem().orElseThrow().eventCode());
            assertEquals(TaskState.ENDED, task.snapshot().state());
            assertEquals(0, confirmations.get());
            assertTrue(peer.commands.isEmpty());
            assertTrue(running.isEmpty());
            assertTrue(peer.current.isEmpty());
            assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
        }
    }

    private DeploymentStandardInputs.Reviewer manualReviewer(Optional<InvalidBoundary> invalid, AtomicInteger confirmations) {
        var delegate = reviewer(2);
        return (DeploymentStandardInputs.Reviewer) Proxy.newProxyInstance(DeploymentStandardInputs.Reviewer.class.getClassLoader(),
            new Class<?>[]{DeploymentStandardInputs.Reviewer.class}, (proxy, method, args) -> {
                if (method.getName().equals("select")) {
                    var choices = new LinkedHashMap<String, DeploymentStandardInputs.Choice>();
                    var artifacts = invalid.filter(InvalidBoundary.OMIT_ARTIFACT::equals).isPresent()
                        ? List.<String>of() : List.of("component0/api");
                    var ports = invalid.filter(InvalidBoundary.OMIT_PORT::equals).isPresent() ? Set.<Integer>of() : Set.of(8080);
                    choices.put("api", new DeploymentStandardInputs.Choice(DeploymentProjectKind.GO_SERVICE, Set.of("worker"),
                        Optional.of(new DeploymentStandardInputs.ComponentInputs("component0", true, Optional.of(artifacts), Optional.of(ports)))));
                    choices.put("worker", new DeploymentStandardInputs.Choice(DeploymentProjectKind.GO_SERVICE,
                        invalid.filter(InvalidBoundary.DEPENDENCY_CYCLE::equals).isPresent() ? Set.of("api") : Set.of(),
                        Optional.of(new DeploymentStandardInputs.ComponentInputs(
                            invalid.filter(InvalidBoundary.OVERLAP_ROOT::equals).isPresent() ? "component0" : "component1", false,
                            Optional.of(List.of(invalid.filter(InvalidBoundary.OVERLAP_ROOT::equals).isPresent()
                                ? "component0/worker" : "component1/worker")), Optional.of(Set.of(8081))))));
                    return success(new DeploymentStandardInputs.Selection("manual-app", choices, Optional.of("api")));
                }
                if (method.getName().equals("confirm")) confirmations.incrementAndGet();
                try {
                    return method.invoke(delegate, args);
                } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                }
            });
    }
}
