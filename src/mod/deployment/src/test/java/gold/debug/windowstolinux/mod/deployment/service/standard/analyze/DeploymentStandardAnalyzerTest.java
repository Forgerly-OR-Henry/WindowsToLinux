package gold.debug.windowstolinux.mod.deployment.service.standard.analyze;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.service.core.model.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.analysis.DeploymentAdmissionStatus;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.component.ComponentIsolationSpecification;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.component.ComponentAnalysisRequest;
import gold.debug.windowstolinux.mod.deployment.source.DeploymentSourcePreparer;
import org.junit.jupiter.api.Test;

class DeploymentStandardAnalyzerTest extends DeploymentRuntimeFixture {
    private Path file(String name, String content) throws Exception {
        Path path = root.resolve("input").resolve(name);
        Files.createDirectories(path.getParent());
        return Files.writeString(path, content);
    }

    @Override
    protected Path selectedSourceDirectory() {
        return root.resolve("input");
    }

    @Test
    void realClassTaskAnalyzesOneFrozenMaterialForPreviewAndFullComponentGraph() throws Exception {
        file("api/go.mod", "module example.test/api\n\ngo 1.24\n");
        file("api/go.sum", "example.test/dependency v1.0.0 h1:fixture\n");
        file("api/main.go", "package main\nfunc main() {}\n");
        file("database/go.mod", "module example.test/database\n\ngo 1.24\n");
        file("database/go.sum", "example.test/dependency v1.0.0 h1:fixture\n");
        file("database/main.go", "package main\nfunc main() {}\n");
        file("package.json", "{\"name\":\"files\",\"scripts\":{\"build\":\"echo build\",\"start\":\"node app.js\"}}");
        file(".env", "excluded-secret");
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            var callbackFailure = new AtomicReference<Throwable>();
            var handle = context.tasks()
                    .submit(new TaskRequest("analysis-integration", "Analyze source", List.of(), true), task -> {
                        try {
                            var selection = context.source().selectDirectory("Source", task);
                            var material = new DeploymentSourcePreparer(context.source()).prepare(selection, 4096, 100,
                                    task);
                            Path frozen = material.snapshot().directory();
                            try (material) {
                                assertFalse(Files.exists(frozen.resolve(".env")));
                                file("api/main.go", "modified after freeze");
                                var analyzer = new DeploymentStandardAnalyzer();
                                assertEquals("input", material.displayName());
                                assertThrows(IllegalArgumentException.class, () -> analyzer.analyze(material, " ",
                                        DeploymentProjectKind.RECOGNITION_PREVIEW, Optional.empty(), false, task));
                                var preview = analyzer.analyze(material, "Reviewed Service",
                                        DeploymentProjectKind.RECOGNITION_PREVIEW, Optional.empty(), false, task);
                                assertEquals(Outcome.SUCCEEDED, preview.outcome());
                                assertEquals(material.snapshot().id(), preview.value().orElseThrow().snapshotId());
                                assertEquals(material.snapshot().sha256(),
                                        preview.value().orElseThrow().snapshotSha256());
                                assertEquals(DeploymentAdmissionStatus.RECOGNITION_PREVIEW,
                                        preview.value().orElseThrow().assessment().admission());
                                assertEquals("reviewed-service", preview.value().orElseThrow().assessment().facts()
                                        .orElseThrow().applicationId());
                                var namedFromMetadata = analyzer.analyze(material, "Reviewed Service",
                                        DeploymentProjectKind.NODE_SERVICE, Optional.empty(), false, task);
                                assertEquals(Outcome.SUCCEEDED, namedFromMetadata.outcome());
                                assertEquals("files", namedFromMetadata.value().orElseThrow().assessment().facts()
                                        .orElseThrow().applicationId());
                                var requests = List.of(component("api", 8080, Set.of("database")),
                                        component("database", 5432, Set.of()));
                                var graph = analyzer.analyzeComponents(material, "Reviewed Service", "shop", requests,
                                        Map.of(), false, task);
                                assertEquals(Outcome.SUCCEEDED, graph.outcome());
                                assertEquals(DeploymentAdmissionStatus.READY_FOR_PLANNING,
                                        graph.value().orElseThrow().assessment().admission());
                                assertEquals(List.of("api", "database"), graph.value().orElseThrow().assessment()
                                        .components().stream().map(component -> component.componentId()).toList());
                                assertEquals(frozen, graph.value().orElseThrow().assessment().applicationRoot());
                                assertEquals(1, scalar(runtime,
                                        "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind='FILE' AND released_at IS NULL"));
                            }
                            assertFalse(Files.exists(frozen));
                            assertEquals(0, scalar(runtime,
                                    "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind='FILE' AND released_at IS NULL"));
                            return new OperationResult<Void>(Outcome.SUCCEEDED, Optional.empty(), Optional.empty());
                        } catch (Exception | AssertionError failure) {
                            callbackFailure.set(failure);
                            throw failure;
                        }
                    });
            var result = handle.completion().toCompletableFuture().get(30, TimeUnit.SECONDS);
            if (callbackFailure.get() != null)
                throw new AssertionError("Analysis callback failed", callbackFailure.get());
            assertEquals(Outcome.SUCCEEDED, result.outcome(), result.toString());
        }
    }

    @Test
    void originalTaskCancellationBeforeTraversalDoesNotPublishAnAssessment() throws Exception {
        file("main.py", "print('source')\n");
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            var ready = new CountDownLatch(1);
            var proceed = new CountDownLatch(1);
            var callbackFinished = new CountDownLatch(1);
            var callbackFailure = new AtomicReference<Throwable>();
            var handle = context.tasks()
                    .submit(new TaskRequest("analysis-cancellation", "Cancel analysis", List.of(), true), task -> {
                        try {
                            var selection = context.source().selectDirectory("Source", task);
                            try (var material = new DeploymentSourcePreparer(context.source()).prepare(selection, 4096,
                                    100, task)) {
                                ready.countDown();
                                assertTrue(proceed.await(8, TimeUnit.SECONDS));
                                var result = new DeploymentStandardAnalyzer().analyze(material, "Reviewed Service",
                                        DeploymentProjectKind.RECOGNITION_PREVIEW, Optional.empty(), false, task);
                                assertEquals(Outcome.CANCELLED, result.outcome());
                                assertTrue(result.value().isEmpty());
                                assertEquals(ProblemCode.CANCELLED, result.problem().orElseThrow().code());
                            }
                            return new OperationResult<Void>(Outcome.CANCELLED, Optional.empty(), Optional.empty());
                        } catch (Exception | AssertionError failure) {
                            callbackFailure.set(failure);
                            throw failure;
                        } finally {
                            callbackFinished.countDown();
                        }
                    });
            try {
                assertTrue(ready.await(8, TimeUnit.SECONDS));
                handle.requestCancel();
            } finally {
                proceed.countDown();
            }
            assertTrue(callbackFinished.await(8, TimeUnit.SECONDS), "Original callback must finish after release");
            if (callbackFailure.get() != null)
                throw new AssertionError("Cancellation callback failed", callbackFailure.get());
            var result = handle.completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
            assertEquals(Outcome.CANCELLED, result.outcome(), result.toString());
            assertEquals(0, scalar(runtime,
                    "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind='FILE' AND released_at IS NULL"));
        }
    }

    private static ComponentAnalysisRequest component(String id, int port, Set<String> dependencies) {
        return new ComponentAnalysisRequest(id, id, DeploymentProjectKind.GO_SERVICE,
                Optional.of(new DeploymentRuntimeDefinition.GoService("1.24", id, "main.go",
                        new DeploymentHealthCheck.Tcp(port, 10, 2))),
                List.of(id + "/.w2l/bin/" + id), Set.of(port), List.of("PORT"), List.of(), List.of(), dependencies,
                true, ComponentIsolationSpecification.managed());
    }
}
