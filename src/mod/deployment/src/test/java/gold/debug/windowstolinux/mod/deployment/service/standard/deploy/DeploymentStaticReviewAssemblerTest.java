package gold.debug.windowstolinux.mod.deployment.service.standard.deploy;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import gold.debug.windowstolinux.app.db.entity.StoredServerProfile;
import gold.debug.windowstolinux.app.service.contract.profile.ServerProfile;
import gold.debug.windowstolinux.appapi.server.ServerRef;
import gold.debug.windowstolinux.appapi.server.ServerService;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.service.core.model.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.analysis.DeploymentAdmissionStatus;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.component.ComponentIsolationSpecification;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.DeploymentStandardAnalyzer;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.component.ComponentAnalysisRequest;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.DeploymentStandardPlanner;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.contract.DeploymentReviewConsent;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.input.ApplicationDeclaration;
import gold.debug.windowstolinux.mod.deployment.source.*;
import org.junit.jupiter.api.Test;

/** Exercises reviewed input conversion inside a real CLASS/H2 task with platform source and server capabilities. / 在真实 CLASS/H2 任务及平台来源和服务器能力内验证审阅输入转换。 */
class DeploymentStaticReviewAssemblerTest extends DeploymentRuntimeFixture {
    /** Original user-selected source. / 原用户选定来源。 */
    @Override
    protected Path selectedSourceDirectory() {
        return root.resolve("input");
    }

    /** Uses the original admitted task for selection, analysis, review and planning. / 以原准入任务完成选择、分析、审阅与规划。 */
    @Test
    void originalTaskTurnsReviewedGoInputsIntoOneBoundPlan() throws Exception {
        Path input = selectedSourceDirectory();
        Files.createDirectories(input);
        Files.writeString(input.resolve("go.mod"), "module example.test/service\n\ngo 1.24\n");
        Files.writeString(input.resolve("go.sum"), "example.test/dependency v1.0.0 h1:fixture\n");
        Files.writeString(input.resolve("main.go"), "package main\nfunc main() {}\n");
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            runtime.servers().saveServerProfile(
                    ServerProfile
                            .fromStored(new StoredServerProfile("server", "127.0.0.1", 22, "root", "fixture-password")),
                    "fixture-password".toCharArray());
            var context = runtime.modContexts().get(OWNER).context();
            var failure = new AtomicReference<Throwable>();
            var handle = context.tasks()
                    .submit(new TaskRequest("static-input", "Review source", List.of("server"), true), task -> {
                        try {
                            var selection = context.source().selectDirectory("Source", task);
                            try (var material = new DeploymentSourcePreparer(context.source()).prepare(selection, 65536,
                                    100, task);
                                    var archive = new DeploymentSourceArchiver(context.source()).prepare(material,
                                            65536, task)) {
                                var analysis = new DeploymentStandardAnalyzer().analyze(material, "Reviewed Go Service",
                                        DeploymentProjectKind.GO_SERVICE, Optional.empty(), false, task);
                                assertEquals(Outcome.SUCCEEDED, analysis.outcome());
                                var facts = analysis.value().orElseThrow().assessment().facts().orElseThrow();
                                var review = new DeploymentReviewConsent(facts.applicationId(), "server",
                                        material.snapshot().sha256(), archive.sha256(), Instant.now());
                                var values = Map.ofEntries(Map.entry("primary", "service"),
                                        Map.entry("secondary", "main.go"), Map.entry("version", "1.24"),
                                        Map.entry("healthMode", "TCP"), Map.entry("port", "8080"),
                                        Map.entry("exposure", "INTERNAL"),
                                        Map.entry("configuration", "RUNTIME:PORT=8080"),
                                        Map.entry("secrets", "database-password:3"),
                                        Map.entry("databaseMode", "POSTGRESQL"), Map.entry("databaseDetails",
                                                "primary|db.example.test|5432|shop|shop|database-password:3|required"));
                                var files = List.of(new DeploymentFileBinding("data",
                                        new DeploymentStoragePath("data", DeploymentStoragePath.AccessMode.READ_WRITE,
                                                "files", true),
                                        DeploymentStorageLocation.defaults(),
                                        DeploymentStorageLocation.StorageResourceType.FILE));
                                var answers = new DeploymentStaticReviewAnswers(values, files, 1,
                                        DeploymentBuildLimits.defaultNonRoot(), review, false, true);
                                var planner = new DeploymentStandardPlanner();
                                var assembler = new DeploymentStaticReviewAssembler(context.servers(), planner);
                                var planned = assembler.plan(new ServerRef("server"), material, archive,
                                        analysis.value().orElseThrow(), answers, task);
                                assertEquals(Outcome.SUCCEEDED, planned.outcome(), planned.toString());
                                assertEquals(archive.sha256(),
                                        planned.value().orElseThrow().request().archive().sha256());
                                assertEquals(1,
                                        planned.value().orElseThrow().request().resources().fileBindings().size());
                                assertEquals(1, planned.value().orElseThrow().request().resources().databaseBindings()
                                        .orElseThrow().size());
                                assertEquals(new DeploymentSecretReference("database-password", 3),
                                        planned.value().orElseThrow().request().secretReferences().getFirst());
                                assertEquals(1,
                                        planned.value().orElseThrow().request().configuration().entries().size());
                                assertTrue(planner.render(planned.value().orElseThrow(), task).value().orElseThrow()
                                        .buildScript().contains(archive.sha256()));
                                var riskNotReviewed = new DeploymentStaticReviewAnswers(values, files, 1,
                                        DeploymentBuildLimits.defaultNonRoot(), review, false, false);
                                assertEquals(Outcome.FAILED,
                                        assembler
                                                .plan(new ServerRef("server"), material, archive,
                                                        analysis.value().orElseThrow(), riskNotReviewed, task)
                                                .outcome());
                                var changedAnalysis = new DeploymentStandardAnalyzer.SnapshotAssessment<>(
                                        java.util.UUID.randomUUID(), analysis.value().orElseThrow().snapshotSha256(),
                                        analysis.value().orElseThrow().assessment());
                                assertEquals(Outcome.FAILED, assembler.plan(new ServerRef("server"), material, archive,
                                        changedAnalysis, answers, task).outcome());
                                for (var code : List.of(ProblemCode.CANCELLED, ProblemCode.UNKNOWN)) {
                                    ServerService rejected = (ServerService) Proxy.newProxyInstance(
                                            ServerService.class.getClassLoader(), new Class<?>[]{ServerService.class},
                                            (proxy, method, args) -> {
                                                if (!method.getName().equals("get"))
                                                    throw new AssertionError(
                                                            "Only the original server read is expected");
                                                throw new ApiException(new Problem(code, "DEPLOYMENT_SERVER_REVIEW",
                                                        "Server review was not confirmed", Map.of(), Optional.empty()));
                                            });
                                    var classified = new DeploymentStaticReviewAssembler(rejected, planner).plan(
                                            new ServerRef("server"), material, archive, analysis.value().orElseThrow(),
                                            answers, task);
                                    assertEquals(code == ProblemCode.CANCELLED ? Outcome.CANCELLED : Outcome.UNKNOWN,
                                            classified.outcome());
                                }
                                var unreviewed = new DeploymentStaticReviewAnswers(
                                        Map.of("primary", "service", "secondary", "main.go", "version", "1.24",
                                                "healthMode", "TCP", "port", "8080", "exposure", "INTERNAL"),
                                        List.of(), 1, DeploymentBuildLimits.defaultNonRoot(), review, false, true);
                                assertEquals(Outcome.FAILED, assembler.plan(new ServerRef("server"), material, archive,
                                        analysis.value().orElseThrow(), unreviewed, task).outcome());
                                var duplicateConfiguration = new TreeMap<String, String>(values);
                                duplicateConfiguration.put("configuration", "PORT=8080;BUILD:PORT=8081");
                                var invalidConfiguration = assembler
                                        .plan(new ServerRef("server"), material, archive,
                                                analysis.value().orElseThrow(),
                                                new DeploymentStaticReviewAnswers(duplicateConfiguration, files, 1,
                                                        DeploymentBuildLimits.defaultNonRoot(), review, false, true),
                                                task);
                                assertEquals(Outcome.FAILED, invalidConfiguration.outcome());
                                assertEquals("configuration.snapshot.duplicate-key",
                                        invalidConfiguration.problem().orElseThrow().eventCode());
                                archive.close();
                                assertEquals(Outcome.FAILED, assembler.plan(new ServerRef("server"), material, archive,
                                        analysis.value().orElseThrow(), answers, task).outcome());
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
                    throw new AssertionError("Static review callback failed", failure.get());
                throw timeout;
            }
            if (failure.get() != null)
                throw new AssertionError("Static review callback failed", failure.get());
            assertEquals(Outcome.SUCCEEDED, result.outcome(), result.toString());
            assertEquals(0, scalar(runtime,
                    "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind='FILE' AND released_at IS NULL"));
        }
    }

    /** Forms an actual two-component graph while preserving each component's source and reviewed runtime. / 在保留各组件来源与已审阅运行时的条件下生成真实双组件图。 */
    @Test
    void originalTaskFormsCompleteReviewedComponentGraph() throws Exception {
        for (String id : List.of("api", "database")) {
            Path source = selectedSourceDirectory().resolve(id);
            Files.createDirectories(source);
            Files.writeString(source.resolve("go.mod"), "module example.test/" + id + "\n\ngo 1.24\n");
            Files.writeString(source.resolve("go.sum"), "example.test/dependency v1 h1:fixture\n");
            Files.writeString(source.resolve("main.go"), "package main\nfunc main() {}\n");
        }
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            runtime.servers().saveServerProfile(
                    ServerProfile
                            .fromStored(new StoredServerProfile("server", "127.0.0.1", 22, "root", "fixture-password")),
                    "fixture-password".toCharArray());
            var context = runtime.modContexts().get(OWNER).context();
            var failure = new AtomicReference<Throwable>();
            var plannedGraph = new CountDownLatch(1);
            var afterCancel = new CountDownLatch(1);
            var handle = context.tasks()
                    .submit(new TaskRequest("static-graph-input", "Review graph", List.of("server"), true), task -> {
                        try {
                            var selection = context.source().selectDirectory("Source", task);
                            try (var material = new DeploymentSourcePreparer(context.source()).prepare(selection, 65536,
                                    100, task)) {
                                try (var apiArchive = new DeploymentSourceArchiver(context.source()).prepare(material,
                                        "api", 65536, task);
                                        var databaseArchive = new DeploymentSourceArchiver(context.source())
                                                .prepare(material, "database", 65536, task)) {
                                    var archives = Map.of("api", apiArchive, "database", databaseArchive);
                                    var analyzer = new DeploymentStandardAnalyzer();
                                    var analyzed = analyzer
                                            .analyzeComponents(material, "Reviewed Shop", "shop",
                                                    List.of(component("api", 8080, Set.of("database")),
                                                            component("database", 5432, Set.of())),
                                                    Map.of(), false, task);
                                    assertEquals(Outcome.SUCCEEDED, analyzed.outcome());
                                    var answers = new TreeMap<String, DeploymentStaticReviewAnswers>();
                                    for (var component : analyzed.value().orElseThrow().assessment().components()) {
                                        int port = component.componentId().equals("api") ? 8080 : 5432;
                                        var values = Map.of("primary", component.componentId(), "secondary", "main.go",
                                                "version", "1.24", "healthMode", "TCP", "port", Integer.toString(port),
                                                "stability", "2", "exposure", "INTERNAL", "databaseMode", "NONE");
                                        var consent = new DeploymentReviewConsent(component.facts().applicationId(),
                                                "server", material.snapshot().sha256(),
                                                archives.get(component.componentId()).sha256(), Instant.now());
                                        answers.put(component.componentId(),
                                                new DeploymentStaticReviewAnswers(values, List.of(), 1,
                                                        DeploymentBuildLimits.defaultNonRoot(), consent, false, true));
                                    }
                                    var assembled = new DeploymentStaticReviewAssembler(context.servers(),
                                            new DeploymentStandardPlanner()).planComponents(new ServerRef("server"),
                                                    material, archives, analyzed.value().orElseThrow(), answers, task);
                                    assertEquals(Outcome.FAILED,
                                            new DeploymentStaticReviewAssembler(context.servers(),
                                                    new DeploymentStandardPlanner())
                                                    .planComponents(new ServerRef("server"), material,
                                                            Map.of("api", databaseArchive, "database", apiArchive),
                                                            analyzed.value().orElseThrow(), answers, task)
                                                    .outcome(),
                                            "Archives from the same snapshot cannot be exchanged across component roots");
                                    assertEquals(Outcome.SUCCEEDED, assembled.outcome(), assembled.toString());
                                    assertEquals(List.of(List.of("database"), List.of("api")),
                                            assembled.value().orElseThrow().ordering().buildWaves());
                                    assertEquals(2, assembled.value().orElseThrow().components().size());
                                    plannedGraph.countDown();
                                    assertTrue(afterCancel.await(8, TimeUnit.SECONDS));
                                    assertEquals(Outcome.CANCELLED,
                                            new DeploymentStaticReviewAssembler(context.servers(),
                                                    new DeploymentStandardPlanner())
                                                    .planComponents(new ServerRef("server"), material, archives,
                                                            analyzed.value().orElseThrow(), answers, task)
                                                    .outcome());
                                }
                            }
                            return new OperationResult<Void>(Outcome.CANCELLED, Optional.empty(), Optional.empty());
                        } catch (Exception | AssertionError caught) {
                            failure.set(caught);
                            throw caught;
                        }
                    });
            try {
                assertTrue(plannedGraph.await(8, TimeUnit.SECONDS));
                handle.requestCancel();
            } finally {
                afterCancel.countDown();
            }
            OperationResult<Void> result;
            try {
                result = handle.completion().toCompletableFuture().get(15, TimeUnit.SECONDS);
            } catch (TimeoutException timeout) {
                if (failure.get() != null)
                    throw new AssertionError("Static graph callback failed", failure.get());
                throw timeout;
            }
            if (failure.get() != null)
                throw new AssertionError("Static graph callback failed", failure.get());
            assertEquals(Outcome.CANCELLED, result.outcome(), result.toString());
        }
    }

    /** Mirrors one old fixed component input without claiming that it was inferred from a path. / 镜像一项旧固定组件输入，不声称它由路径授权推导。 */
    private static ComponentAnalysisRequest component(String id, int port, Set<String> dependencies) {
        var health = new DeploymentHealthCheck.Tcp(port, 10, 2);
        var values = Map.of("primary", id, "secondary", "main.go", "version", "1.24", "healthMode", "TCP", "port",
                Integer.toString(port), "stability", "2", "exposure", "INTERNAL", "databaseMode", "NONE");
        var runtime = new DeploymentRuntimeDefinition.GoService("1.24", id, "main.go", health)
                .withWorkload(ApplicationDeclaration.resolve(values));
        return new ComponentAnalysisRequest(id, id, DeploymentProjectKind.GO_SERVICE, Optional.of(runtime),
                List.of(id + "/.w2l/bin/" + id), Set.of(port), List.of("PORT"), List.of(), List.of(), dependencies,
                true, ComponentIsolationSpecification.managed());
    }

    /** Preserves one Node component's complete reviewed fields through an actual source-bound graph plan. / 在实际来源绑定图计划中保留一个Node组件的完整已审阅字段。
     * @throws Exception if actual CLASS source, analysis or review binding fails / 实际CLASS来源、分析或审阅绑定失败时 */
    @Test
    void originalTaskMapsReviewedNodeTcpComponentFieldsIntoItsBoundPlan() throws Exception {
        for (String id : List.of("api", "database")) {
            var directory = Files.createDirectories(selectedSourceDirectory().resolve(id));
            Files.writeString(directory.resolve("package.json"), "{\"name\":\"" + id
                    + "\",\"engines\":{\"node\":\"22\"},\"scripts\":{\"build\":\"node build.js\",\"start\":\"node main.js\"}}");
            Files.writeString(directory.resolve("package-lock.json"), "{\"name\":\"" + id
                    + "\",\"version\":\"1.0.0\",\"lockfileVersion\":3,\"requires\":true,\"packages\":{\"\":{\"name\":\""
                    + id + "\",\"version\":\"1.0.0\"}}}");
        }
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            runtime.servers().saveServerProfile(
                    ServerProfile
                            .fromStored(new StoredServerProfile("server", "127.0.0.1", 22, "root", "fixture-password")),
                    "fixture-password".toCharArray());
            var context = runtime.modContexts().get(OWNER).context();
            var failure = new AtomicReference<Throwable>();
            var originalTask = new AtomicReference<java.util.UUID>();
            var handle = context.tasks().submit(
                    new TaskRequest("node-component-input", "Review Node component", List.of("server"), true), task -> {
                        try {
                            originalTask.set(task.id());
                            var selected = context.source().selectDirectory("Source", task);
                            try (var material = new DeploymentSourcePreparer(context.source()).prepare(selected, 65536,
                                    100, task);
                                    var apiArchive = new DeploymentSourceArchiver(context.source()).prepare(material,
                                            "api", 65536, task);
                                    var databaseArchive = new DeploymentSourceArchiver(context.source())
                                            .prepare(material, "database", 65536, task)) {
                                var reviewedHealth = new DeploymentHealthCheck.Tcp(18081, 20, 1);
                                var apiValues = Map.of("version", "22", "healthMode", "TCP", "port", "18081", "timeout",
                                        "20", "stability", "1", "exposure", "INTERNAL", "configuration", "PORT=18081",
                                        "secrets", "database-password:1", "databaseMode", "NONE");
                                var apiRuntime = new DeploymentRuntimeDefinition.NodeService(22, reviewedHealth)
                                        .withWorkload(ApplicationDeclaration.resolve(apiValues));
                                var api = new ComponentAnalysisRequest("api", "api", DeploymentProjectKind.NODE_SERVICE,
                                        Optional.of(apiRuntime), List.of("api/package.json"), Set.of(18081),
                                        List.of("PORT"), List.of("database-password"), List.of(), Set.of("database"),
                                        true, ComponentIsolationSpecification.managed());
                                var databaseValues = Map.of("version", "22", "healthMode", "TCP", "port", "18082",
                                        "timeout", "20", "stability", "1", "exposure", "INTERNAL", "configuration",
                                        "PORT=18082", "databaseMode", "NONE");
                                var databaseRuntime = new DeploymentRuntimeDefinition.NodeService(22,
                                        new DeploymentHealthCheck.Tcp(18082, 20, 1))
                                        .withWorkload(ApplicationDeclaration.resolve(databaseValues));
                                var database = new ComponentAnalysisRequest("database", "database",
                                        DeploymentProjectKind.NODE_SERVICE, Optional.of(databaseRuntime),
                                        List.of("database/package.json"), Set.of(18082), List.of("PORT"), List.of(),
                                        List.of(), Set.of(), true, ComponentIsolationSpecification.managed());
                                var analysis = new DeploymentStandardAnalyzer().analyzeComponents(material,
                                        "Reviewed Node Shop", "shop", List.of(api, database), Map.of(), false, task);
                                assertEquals(Outcome.SUCCEEDED, analysis.outcome(), analysis.toString());
                                assertEquals(DeploymentAdmissionStatus.READY_FOR_PLANNING,
                                        analysis.value().orElseThrow().assessment().admission());
                                var analyzedApi = analysis.value().orElseThrow().assessment().components().stream()
                                        .filter(component -> component.componentId().equals("api")).findFirst()
                                        .orElseThrow();
                                assertEquals(api.componentId(), analyzedApi.componentId());
                                assertEquals(material.snapshot().directory().resolve("api"), analyzedApi.sourceRoot());
                                assertEquals(api.dependencies(), analyzedApi.dependencies());
                                assertEquals(api.ports(), analyzedApi.ports());
                                assertEquals(api.configurationKeys(), analyzedApi.configurationKeys());
                                assertEquals(api.secretIdentifiers(), analyzedApi.secretIdentifiers());
                                assertEquals(api.runtime(), analyzedApi.runtime());
                                assertEquals(api.isolation(), analyzedApi.isolation());
                                assertFalse(analyzedApi.isolation().arbitraryShell());
                                assertFalse(analyzedApi.isolation().hostPrivileges());
                                assertTrue(analyzedApi.isolation().managedOnly());
                                var consent = new DeploymentReviewConsent(analyzedApi.facts().applicationId(), "server",
                                        material.snapshot().sha256(), apiArchive.sha256(), Instant.now());
                                var apiAnswers = new DeploymentStaticReviewAnswers(apiValues, List.of(), 1,
                                        DeploymentBuildLimits.defaultNonRoot(), consent, false, true);
                                var analyzedDatabase = analysis.value().orElseThrow().assessment().components().stream()
                                        .filter(component -> component.componentId().equals("database")).findFirst()
                                        .orElseThrow();
                                var databaseConsent = new DeploymentReviewConsent(
                                        analyzedDatabase.facts().applicationId(), "server",
                                        material.snapshot().sha256(), databaseArchive.sha256(), Instant.now());
                                var databaseAnswers = new DeploymentStaticReviewAnswers(databaseValues, List.of(), 1,
                                        DeploymentBuildLimits.defaultNonRoot(), databaseConsent, false, true);
                                var result = new DeploymentStaticReviewAssembler(context.servers(),
                                        new DeploymentStandardPlanner()).planComponents(new ServerRef("server"),
                                                material, Map.of("api", apiArchive, "database", databaseArchive),
                                                analysis.value().orElseThrow(),
                                                Map.of("api", apiAnswers, "database", databaseAnswers), task);
                                assertEquals(Outcome.SUCCEEDED, result.outcome(), result.toString());
                                assertEquals(List.of(List.of("database"), List.of("api")),
                                        result.value().orElseThrow().ordering().buildWaves());
                                var request = result.value().orElseThrow().components().get("api").request();
                                assertEquals("shop-api", request.configuration().applicationId());
                                assertEquals(1, request.configuration().revision());
                                assertEquals(List.of(
                                        new gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigurationEntry(
                                                "PORT",
                                                gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigurationScopeType.RUNTIME,
                                                new gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigurationValue.Number(
                                                        18081))),
                                        request.configuration().entries());
                                assertEquals(List.of(new DeploymentSecretReference("database-password", 1)),
                                        request.secretReferences());
                                assertEquals(List.of(), request.resources().databaseBindings().orElseThrow());
                                assertEquals(apiRuntime, request.runtime());
                                assertEquals(reviewedHealth, request.runtime().healthCheck());
                                assertEquals(DeploymentBuildLimits.defaultNonRoot(), request.limits());
                                assertFalse(request.limits().runAsRoot());
                                assertSame(material, request.material());
                                assertSame(apiArchive, request.archive());
                                assertEquals(consent, request.review());
                                assertEquals(material.snapshot().sha256(),
                                        analysis.value().orElseThrow().snapshotSha256());
                                assertEquals(originalTask.get(), task.id());
                            }
                            return new OperationResult<Void>(Outcome.SUCCEEDED, Optional.empty(), Optional.empty());
                        } catch (Exception | AssertionError caught) {
                            failure.set(caught);
                            throw caught;
                        }
                    });
            OperationResult<Void> completed;
            try {
                completed = handle.completion().toCompletableFuture().get(15, TimeUnit.SECONDS);
            } catch (Exception unfinished) {
                throw new AssertionError("Original Node review failed: " + handle.snapshot(),
                        failure.get() == null ? unfinished : failure.get());
            }
            if (failure.get() != null)
                throw new AssertionError("Original Node review failed", failure.get());
            assertEquals(Outcome.SUCCEEDED, completed.outcome(), completed.toString());
            assertEquals(handle.id(), originalTask.get());
            assertEquals(TaskState.ENDED, handle.snapshot().state());
            assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
        }
    }
}
