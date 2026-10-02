package gold.debug.windowstolinux.mod.deployment.service.standard.deploy;

import gold.debug.windowstolinux.appapi.task.OperationResult;
import gold.debug.windowstolinux.appapi.task.Outcome;
import gold.debug.windowstolinux.appapi.task.TaskRequest;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import gold.debug.windowstolinux.appapi.server.ServerRef;

import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigurationSnapshot;
import gold.debug.windowstolinux.mod.deployment.service.core.model.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.component.ComponentIsolationSpecification;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.DeploymentStandardAnalyzer;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.component.ComponentAnalysisRequest;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.contract.*;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.plan.ReviewedPlanIdentity;
import gold.debug.windowstolinux.mod.deployment.file.*;
import org.junit.jupiter.api.Test;

class DeploymentStandardPlannerTest extends DeploymentRuntimeFixture {
    @Override
    protected Path selectedSourceDirectory() {
        return root.resolve("input");
    }

    @Test
    void realMixedAssessmentKeepsDependencyWavesAndOriginalArchiveForEachComponent() throws Exception {
        for (String id : List.of("api", "database")) {
            Path component = selectedSourceDirectory().resolve(id);
            Files.createDirectories(component);
            Files.writeString(component.resolve("go.mod"), "module example.test/" + id + "\n\ngo 1.24\n");
            Files.writeString(component.resolve("go.sum"), "example.test/dependency v1.0.0 h1:fixture\n");
            Files.writeString(component.resolve("main.go"), "package main\nfunc main() {}\n");
        }
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            var failure = new AtomicReference<Throwable>();
            var handle = context.tasks().submit(new TaskRequest("mixed-planning", "Plan components", List.of(), true),
                task -> {
                    try {
                        var selection = context.file().selectDirectory("Source", task);
                        try (var material = new DeploymentSourcePreparer(context.file()).prepare(selection, 65536,
                            100, task);
                             var archive = new DeploymentSourceArchiver(context.file()).prepare(material,
                                 65536, task)) {
                            var analyzer = new DeploymentStandardAnalyzer();
                            var graph = analyzer.analyzeComponents(material, "Reviewed Shop", "shop",
                                List.of(component("api", 8080, Set.of("database")),
                                    component("database", 5432, Set.of())),
                                Map.of(), false, task);
                            assertEquals(Outcome.SUCCEEDED, graph.outcome());
                            var assessment = graph.value().orElseThrow();
                            var server = new ServerRef("Server Exact 1");
                            var requests = new TreeMap<String, ReviewedDeploymentRequest>();
                            for (var component : assessment.assessment().components())
                                requests.put(component.componentId(), request(server, component.facts(), material,
                                    archive, component.runtime().orElseThrow()));
                            var planner = new DeploymentStandardPlanner();
                            var planned = planner.planComponents(material, assessment, requests, task);
                            assertEquals(Outcome.SUCCEEDED, planned.outcome());
                            var plans = planned.value().orElseThrow();
                            assertEquals(List.of(List.of("database"), List.of("api")),
                                plans.ordering().buildWaves());
                            assertEquals(List.of("api", "database"), plans.ordering().rollbackOrder());
                            for (var plan : plans.components().values()) {
                                var rendered = planner.render(plan, task);
                                assertEquals(Outcome.SUCCEEDED, rendered.outcome());
                                assertTrue(rendered.value().orElseThrow().buildScript().contains(archive.sha256()));
                            }
                            assertThrows(IllegalArgumentException.class, () -> planner.planComponents(material,
                                assessment, Map.of("api", requests.get("api")), task));
                            assertThrows(IllegalArgumentException.class,
                                () -> planner.planComponents(material,
                                    new DeploymentStandardAnalyzer.SnapshotAssessment<>(UUID.randomUUID(),
                                        assessment.snapshotSha256(), assessment.assessment()),
                                    requests, task));
                        }
                        return new OperationResult<Void>(Outcome.SUCCEEDED, Optional.empty(), Optional.empty());
                    } catch (Exception | AssertionError caught) {
                        failure.set(caught);
                        throw caught;
                    }
                });
            OperationResult<Void> result;
            try {
                result = handle.completion().toCompletableFuture().get(15, TimeUnit.SECONDS);
            } catch (TimeoutException timeout) {
                if (failure.get() != null)
                    throw new AssertionError("Mixed planning callback failed", failure.get());
                throw timeout;
            }
            if (failure.get() != null)
                throw new AssertionError("Mixed planning callback failed", failure.get());
            assertEquals(Outcome.SUCCEEDED, result.outcome(), result.toString());
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

    @Test
    void cancelledOriginalTaskCannotRenderAfterItsSourceHandlesClose() throws Exception {
        Path input = selectedSourceDirectory();
        Files.createDirectories(input);
        Files.writeString(input.resolve("go.mod"), "module example.test/service\n\ngo 1.24\n");
        Files.writeString(input.resolve("go.sum"), "example.test/dependency v1.0.0 h1:fixture\n");
        Files.writeString(input.resolve("main.go"), "package main\nfunc main() {}\n");
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            var ready = new CountDownLatch(1);
            var proceed = new CountDownLatch(1);
            var finished = new CountDownLatch(1);
            var failure = new AtomicReference<Throwable>();
            var handle = context.tasks().submit(new TaskRequest("standard-plan-cancel", "Cancel plan", List.of(), true),
                task -> {
                    try {
                        var selection = context.file().selectDirectory("Source", task);
                        ReviewedDeploymentPlan plan;
                        try (var material = new DeploymentSourcePreparer(context.file()).prepare(selection, 65536,
                            100, task);
                             var archive = new DeploymentSourceArchiver(context.file()).prepare(material,
                                 65536, task)) {
                            var analysis = new DeploymentStandardAnalyzer().analyze(material, "Reviewed Go Service",
                                DeploymentProjectKind.GO_SERVICE, Optional.empty(), false, task);
                            var facts = analysis.value().orElseThrow().assessment().facts().orElseThrow();
                            plan = new DeploymentStandardPlanner()
                                .plan(material, analysis.value().orElseThrow(),
                                    request(new ServerRef("server-1"), facts, material, archive), task)
                                .value().orElseThrow();
                            ready.countDown();
                            assertTrue(proceed.await(8, TimeUnit.SECONDS));
                        }
                        var rendered = new DeploymentStandardPlanner().render(plan, task);
                        assertEquals(Outcome.CANCELLED, rendered.outcome());
                        assertTrue(rendered.value().isEmpty());
                        return new OperationResult<Void>(Outcome.CANCELLED, Optional.empty(), Optional.empty());
                    } catch (Exception | AssertionError caught) {
                        failure.set(caught);
                        throw caught;
                    } finally {
                        finished.countDown();
                    }
                });
            try {
                assertTrue(ready.await(8, TimeUnit.SECONDS));
                handle.requestCancel();
            } finally {
                proceed.countDown();
            }
            assertTrue(finished.await(8, TimeUnit.SECONDS));
            if (failure.get() != null)
                throw new AssertionError("Cancelled plan callback failed", failure.get());
            assertEquals(Outcome.CANCELLED,
                handle.completion().toCompletableFuture().get(12, TimeUnit.SECONDS).outcome());
            assertEquals(0, scalar(runtime,
                "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind='FILE' AND released_at IS NULL"));
        }
    }

    @Test
    void realTaskBindsAnalyzedSourceAndArchiveThenPlansAndRendersGo() throws Exception {
        Path input = selectedSourceDirectory();
        Files.createDirectories(input);
        Files.writeString(input.resolve("go.mod"), "module example.test/service\n\ngo 1.24\n");
        Files.writeString(input.resolve("go.sum"), "example.test/dependency v1.0.0 h1:fixture\n");
        Files.writeString(input.resolve("main.go"), "package main\nfunc main() {}\n");
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            var failure = new AtomicReference<Throwable>();
            var handle = context.tasks().submit(new TaskRequest("standard-planning", "Plan source", List.of(), true),
                task -> {
                    try {
                        var selection = context.file().selectDirectory("Source", task);
                        try (var material = new DeploymentSourcePreparer(context.file()).prepare(selection, 65536,
                            100, task);
                             var archive = new DeploymentSourceArchiver(context.file()).prepare(material,
                                 65536, task)) {
                            var analyzed = new DeploymentStandardAnalyzer().analyze(material, "Reviewed Go Service",
                                DeploymentProjectKind.GO_SERVICE, Optional.empty(), false, task);
                            assertEquals(Outcome.SUCCEEDED, analyzed.outcome());
                            var original = analyzed.value().orElseThrow();
                            var facts = original.assessment().facts().orElseThrow();
                            var server = new ServerRef("server-1");
                            var request = request(server, facts, material, archive);
                            var planner = new DeploymentStandardPlanner();
                            var plan = planner.plan(material, original, request, task);
                            assertEquals(Outcome.SUCCEEDED, plan.outcome());
                            assertEquals(DeploymentPlanAction.ROLLBACK_ON_FAILURE,
                                plan.value().orElseThrow().steps().getLast());
                            assertNotEquals(archive.sha256(), material.snapshot().sha256());
                            var rendered = planner.render(plan.value().orElseThrow(), task);
                            assertEquals(Outcome.SUCCEEDED, rendered.outcome());
                            var script = rendered.value().orElseThrow().buildScript();
                            assertFalse(script.isBlank());
                            assertTrue(script.contains(archive.sha256()));
                            assertEquals(ReviewedPlanIdentity.from(request),
                                rendered.value().orElseThrow().reviewedPlanSha256());
                            var secretA = new DeploymentSecretReference("alpha", 1);
                            var secretB = new DeploymentSecretReference("beta", 2);
                            var secrets = new ReviewedDeploymentRequest(server, facts, material, archive,
                                request.configuration(), List.of(secretA, secretB), request.resources(),
                                request.runtime(), request.userAccessUrl(), request.limits(), request.review(),
                                false, true);
                            var reordered = new ReviewedDeploymentRequest(server, facts, material, archive,
                                request.configuration(), List.of(secretB, secretA), request.resources(),
                                request.runtime(), request.userAccessUrl(), request.limits(), request.review(),
                                false, true);
                            assertEquals(ReviewedPlanIdentity.from(secrets), ReviewedPlanIdentity.from(reordered));
                            assertNotEquals(ReviewedPlanIdentity.from(request), ReviewedPlanIdentity.from(secrets));
                            assertNotEquals(ReviewedPlanIdentity.from(secrets),
                                ReviewedPlanIdentity.from(reviewed(secrets, facts,
                                    List.of(new DeploymentSecretReference("alpha", 2), secretB),
                                    secrets.resources(), secrets.runtime())));
                            assertNotEquals(ReviewedPlanIdentity.from(request),
                                ReviewedPlanIdentity.from(new ReviewedDeploymentRequest(server, facts, material,
                                    archive, request.configuration(), request.secretReferences(),
                                    new DeploymentResourceBindings(List.of(), Optional.empty()),
                                    request.runtime(), request.userAccessUrl(), request.limits(),
                                    request.review(), false, true)));
                            assertNotEquals(ReviewedPlanIdentity.from(request),
                                ReviewedPlanIdentity.from(new ReviewedDeploymentRequest(server, facts, material,
                                    archive, request.configuration(), request.secretReferences(),
                                    request.resources(),
                                    new DeploymentRuntimeDefinition.GoService("1.24", facts.applicationId(),
                                        "main.go", new DeploymentHealthCheck.Tcp(8081, 10, 2)),
                                    request.userAccessUrl(), request.limits(), request.review(), false,
                                    true)));
                            var springRuntime = new DeploymentRuntimeDefinition.SpringBoot(
                                new DeploymentHealthCheck.Tcp(8080, 10, 2));
                            var gradleFacts = new gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentProjectFacts(
                                facts.sourceRoot(), facts.applicationId(), DeploymentProjectKind.SPRING_BOOT,
                                gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentBuildToolType.GRADLE_WRAPPER,
                                List.of(), List.of(), List.of());
                            var mavenFacts = new gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentProjectFacts(
                                facts.sourceRoot(), facts.applicationId(), DeploymentProjectKind.SPRING_BOOT,
                                gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentBuildToolType.MAVEN_WRAPPER,
                                List.of(), List.of(), List.of());
                            assertNotEquals(
                                ReviewedPlanIdentity.from(reviewed(request, gradleFacts,
                                    request.secretReferences(), request.resources(), springRuntime)),
                                ReviewedPlanIdentity.from(reviewed(request, mavenFacts,
                                    request.secretReferences(), request.resources(), springRuntime)));
                            var password = new DeploymentSecretReference("database-password", 3);
                            var withPassword = reviewed(request, facts, List.of(password), request.resources(),
                                request.runtime());
                            var postgres = new DeploymentDatabaseBinding("orders",
                                new DeploymentDatabaseConnection.Server(DeploymentDatabaseEngineType.POSTGRESQL,
                                    "db.internal", 5432, "orders", "application", password, true));
                            var changedPort = new DeploymentDatabaseBinding("orders",
                                new DeploymentDatabaseConnection.Server(DeploymentDatabaseEngineType.POSTGRESQL,
                                    "db.internal", 5433, "orders", "application", password, true));
                            var dbResources = new DeploymentResourceBindings(List.of(),
                                Optional.of(List.of(postgres)));
                            assertNotEquals(ReviewedPlanIdentity.from(withPassword),
                                ReviewedPlanIdentity.from(reviewed(withPassword, facts, List.of(password),
                                    dbResources, request.runtime())));
                            assertNotEquals(
                                ReviewedPlanIdentity.from(reviewed(withPassword, facts, List.of(password),
                                    dbResources, request.runtime())),
                                ReviewedPlanIdentity
                                    .from(reviewed(withPassword, facts, List.of(password),
                                        new DeploymentResourceBindings(List.of(),
                                            Optional.of(List.of(changedPort))),
                                        request.runtime())));
                            assertThrows(IllegalArgumentException.class,
                                () -> reviewed(request, facts, List.of(), dbResources, request.runtime()));
                            var dockerFacts = new gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentProjectFacts(
                                facts.sourceRoot(), facts.applicationId(),
                                DeploymentProjectKind.DOCKERFILE_CONTAINER,
                                gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentBuildToolType.CONTAINER_BUILD,
                                List.of(), List.of(), List.of());
                            var container = new DeploymentRuntimeDefinition.Container(
                                DeploymentRuntimeDefinition.ContainerEngineType.DOCKER, Map.of(8080, 8080),
                                List.of(new DeploymentRuntimeDefinition.ManagedVolume(
                                    "windowstolinux-demo-data", "/data", false)),
                                new DeploymentHealthCheck.Tcp(8080, 10, 2));
                            assertThrows(IllegalArgumentException.class, () -> reviewed(request, dockerFacts,
                                List.of(), request.resources(), container));
                            var dockerAccepted = new ReviewedDeploymentRequest(server, dockerFacts, material,
                                archive, request.configuration(), List.of(), request.resources(), container,
                                Optional.empty(), request.limits(), request.review(), true, true);
                            assertEquals(1, dockerAccepted.resources().fileBindings().size());
                            assertEquals("demo-data",
                                dockerAccepted.resources().fileBindings().getFirst().bindingId());
                            assertEquals("/data",
                                dockerAccepted.resources().fileBindings().getFirst().dataPath().path());
                            var sameBinding = dockerAccepted.resources().fileBindings().getFirst();
                            var reused = new ReviewedDeploymentRequest(server, dockerFacts, material, archive,
                                request.configuration(), List.of(),
                                new DeploymentResourceBindings(List.of(sameBinding), Optional.of(List.of())),
                                container, Optional.empty(), request.limits(), request.review(), true, true);
                            assertEquals(List.of(sameBinding), reused.resources().fileBindings());
                            var incompatible = new DeploymentFileBinding(
                                "demo-data", new DeploymentStoragePath("/other",
                                DeploymentStoragePath.AccessMode.READ_WRITE, "files", true),
                                DeploymentStorageLocation.defaults());
                            assertThrows(IllegalArgumentException.class,
                                () -> new ReviewedDeploymentRequest(server, dockerFacts, material, archive,
                                    request.configuration(), List.of(),
                                    new DeploymentResourceBindings(List.of(incompatible),
                                        Optional.of(List.of())),
                                    container, Optional.empty(), request.limits(), request.review(), true,
                                    true));
                            var nested = new DeploymentFileBinding("nested",
                                new DeploymentStoragePath("/data/nested",
                                    DeploymentStoragePath.AccessMode.READ_WRITE, "files", true),
                                DeploymentStorageLocation.defaults());
                            assertThrows(IllegalArgumentException.class, () -> new ReviewedDeploymentRequest(server,
                                dockerFacts, material, archive, request.configuration(), List.of(),
                                new DeploymentResourceBindings(List.of(nested), Optional.of(List.of())),
                                container, Optional.empty(), request.limits(), request.review(), true, true));
                            assertNotEquals(ReviewedPlanIdentity.from(request),
                                ReviewedPlanIdentity.from(new ReviewedDeploymentRequest(server, facts, material,
                                    archive,
                                    DeploymentConfigurationSnapshot.create(facts.applicationId(), 2, "v1",
                                        Instant.now(), List.of()),
                                    request.secretReferences(), request.resources(), request.runtime(),
                                    request.userAccessUrl(), request.limits(), request.review(), false,
                                    true)));
                            assertThrows(IllegalArgumentException.class,
                                () -> new ReviewedDeploymentRequest(server, facts, material, archive,
                                    request.configuration(), request.secretReferences(),
                                    request.resources(), request.runtime(), request.userAccessUrl(),
                                    request.limits(),
                                    new DeploymentReviewConsent(facts.applicationId(), server.id(),
                                        material.snapshot().sha256(), "0".repeat(64), Instant.now()),
                                    false, true));
                        }
                        return new OperationResult<Void>(Outcome.SUCCEEDED, Optional.empty(), Optional.empty());
                    } catch (Exception | AssertionError caught) {
                        failure.set(caught);
                        throw caught;
                    }
                });
            OperationResult<Void> result;
            try {
                result = handle.completion().toCompletableFuture().get(12, TimeUnit.SECONDS);
            } catch (TimeoutException timeout) {
                if (failure.get() != null)
                    throw new AssertionError("Original planning callback failed", failure.get());
                throw timeout;
            }
            if (failure.get() != null)
                throw new AssertionError("Original planning callback failed", failure.get());
            assertEquals(Outcome.SUCCEEDED, result.outcome(), result.toString());
            assertEquals(0, scalar(runtime,
                "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind='FILE' AND released_at IS NULL"));
        }
    }

    private static ReviewedDeploymentRequest request(ServerRef server,
                                                     gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentProjectFacts facts,
                                                     DeploymentSourceMaterial material, DeploymentSourceArchive archive) {
        var runtime = new DeploymentRuntimeDefinition.GoService("1.24", facts.applicationId(), "main.go",
            new DeploymentHealthCheck.Tcp(8080, 10, 2));
        return request(server, facts, material, archive, runtime);
    }

    private static ReviewedDeploymentRequest request(ServerRef server,
                                                     gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentProjectFacts facts,
                                                     DeploymentSourceMaterial material, DeploymentSourceArchive archive, DeploymentRuntimeDefinition runtime) {
        return new ReviewedDeploymentRequest(server, facts, material, archive,
            DeploymentConfigurationSnapshot.create(facts.applicationId(), 1, "v1", Instant.now(), List.of()),
            List.of(), new DeploymentResourceBindings(List.of(), Optional.of(List.of())), runtime, Optional.empty(),
            DeploymentBuildLimits.defaultNonRoot(), new DeploymentReviewConsent(facts.applicationId(), server.id(),
            material.snapshot().sha256(), archive.sha256(), Instant.now()),
            false, true);
    }

    private static ReviewedDeploymentRequest reviewed(ReviewedDeploymentRequest base,
                                                      gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentProjectFacts facts,
                                                      List<DeploymentSecretReference> secrets, DeploymentResourceBindings resources,
                                                      DeploymentRuntimeDefinition runtime) {
        return new ReviewedDeploymentRequest(base.server(), facts, base.material(), base.archive(),
            base.configuration(), secrets, resources, runtime, base.userAccessUrl(), base.limits(), base.review(),
            base.containerDaemonRiskAccepted(), base.experimentalAdapterRiskAccepted());
    }
}
