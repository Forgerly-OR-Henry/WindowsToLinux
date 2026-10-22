package gold.debug.windowstolinux.mod.deployment.service.standard;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.appapi.file.snapshot.FileSnapshot;
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.*;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.mod.deployment.ai.NativeAiFixture;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentWorkflowAuthorization;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.file.*;
import gold.debug.windowstolinux.mod.deployment.linux.command.contract.DeploymentCommandReview;
import gold.debug.windowstolinux.mod.deployment.service.standard.assisted.DeploymentAssistedScope;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.plan.ReviewedPlanIdentity;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeploymentAssistedRuntimeNeedTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void originalAssistedPreparationCarriesCapturedLayoutToItsFirstActualReview(boolean custom) throws Exception {
        Files.createDirectories(directory.resolve("frontend"));
        Files.writeString(directory.resolve("frontend/package.json"), "{\"scripts\":{\"build\":\"vite build\"}}");
        var sourceId = new AtomicReference<>(UUID.randomUUID());
        var archiveId = new AtomicReference<>(UUID.randomUUID());
        var material = new DeploymentSourceMaterial("survey", snapshot(sourceId, "a".repeat(64)), List.of("frontend/package.json"), List.of());
        var archive = new DeploymentSourceArchive(snapshot(archiveId, "c".repeat(64)), "b".repeat(64), 1, 1, sourceId.get(), "a".repeat(64), "frontend");
        var fixture = new NativeAiFixture();
        var layout = custom ? new DeploymentRemoteLayout(new gold.debug.windowstolinux.mod.deployment.config.DeploymentConfig.RemotePaths(
            "/srv/custom/helper", "/srv/custom/apps", "/srv/custom/state", "/srv/custom/config", "/srv/custom/data")) : DeploymentRemoteLayout.original();
        var json = new ObjectMapper();
        var payload = json.createObjectNode().put("formatVersion", 1).put("task", fixture.task.id().toString()).put("layoutSha256", layout.digest());
        payload.set("layout", json.readTree(layout.canonical()));
        var checkpoint = (gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint.class}, (proxy, method, args) -> {
                if (method.getName().equals("snapshot")) return new gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint.Snapshot(0, payload.toString(), true);
                throw new AssertionError(method.getName());
            });
        var tasks = (gold.debug.windowstolinux.appapi.task.TaskService) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{gold.debug.windowstolinux.appapi.task.TaskService.class}, (proxy, method, args) -> {
                if (method.getName().equals("findCheckpoint") && args[0] == fixture.task && args[1].equals("deployment.config.remote-layout.v1"))
                    return Optional.of(checkpoint);
                throw new AssertionError(method.getName());
            });
        var host = (InteractionService) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{InteractionService.class},
            (proxy, method, args) -> {
                if (method.getName().equals("openReviewedWorkflow")) return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of((WorkflowGrant) () -> {}), Optional.empty());
                throw new AssertionError("Independent DENY must precede host action admission: " + method.getName());
            });
        var db = (gold.debug.windowstolinux.appapi.db.ModDatabase) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{gold.debug.windowstolinux.appapi.db.ModDatabase.class}, (proxy, method, args) -> { throw new AssertionError(method.getName()); });
        var context = (ModContext) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ModContext.class}, (proxy, method, args) -> {
            return switch (method.getName()) {
                case "tasks" -> tasks;
                case "interaction" -> host;
                case "db" -> db;
                case "ui" -> Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{gold.debug.windowstolinux.appapi.ui.UiService.class},
                    (unused, uiMethod, values) -> new gold.debug.windowstolinux.appapi.ui.page.UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false));
                default -> method.invoke(fixture.context, args);
            };
        });
        var server = new ServerId("fixture");
        var scope = new DeploymentAssistedScope(context, fixture.task, fixture.configurationRevision, "AUTOMATIC", true);
        try (var permission = DeploymentWorkflowAuthorization.open(context, fixture.task, server, fixture.configurationRevision, "AUTOMATIC", true, "Original source deployment")) {
            scope.workflowOpened(permission);
            scope.sourceCaptured(server, material, fixture.task);
            var plan = DeploymentAssistedApprovalBudgetLiveTest.staticSitePlan(material, archive, "wtl-survey", layout, server);
            scope.archiveCaptured(material, archive, plan.request().facts().toolchainRequirements(), fixture.task);
            fixture.nativeReply(new gold.debug.windowstolinux.appapi.ai.model.AiToolCall("stop", "submit_result",
                "{\"decision\":\"DENY\",\"requiresUserConfirmation\":false,\"reason\":\"Stop before dispatch\"}"));
            var type = Class.forName("gold.debug.windowstolinux.mod.deployment.service.standard.assisted.publish.DeploymentAssistedPublishBuild");
            var constructor = type.getDeclaredConstructors()[0]; constructor.setAccessible(true);
            var build = constructor.newInstance(scope, new gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.build.DeploymentStandardCandidateRunner());
            var prepare = type.getDeclaredMethod("prepare", plan.getClass(), gold.debug.windowstolinux.appapi.linux.session.RemoteSession.class,
                gold.debug.windowstolinux.appapi.task.run.TaskContext.class); prepare.setAccessible(true);
            var remote = (gold.debug.windowstolinux.appapi.linux.session.RemoteSession) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{gold.debug.windowstolinux.appapi.linux.session.RemoteSession.class}, (proxy, method, args) -> { throw new AssertionError("No transport: " + method.getName()); });
            var failure = assertThrows(InvocationTargetException.class, () -> prepare.invoke(build, plan, remote, fixture.task));
            assertInstanceOf(ApiException.class, failure.getCause());
            assertEquals(1, fixture.requests.size());
            assertTrue(fixture.requests.getFirst().toString().contains(layout.helperPath()));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"VALID", "REVISION", "SERVER", "SOURCE", "ARCHIVE_HANDLE", "TASK", "WORKFLOW", "BRANCH", "ECOSYSTEM", "EXACT", "DENY", "HOST_MISSING", "HOST_CLEARED", "HOST_REVISION", "HOST_TASK"})
    void runtimeNeedIsBoundToThePlanAndSourceWithoutRewritingSourceDeclarations(String mutation) throws Exception {
        Files.createDirectories(directory.resolve("frontend"));
        Files.writeString(directory.resolve("frontend/package.json"), "{\"engines\":{\"node\":\">=24 <25\"},\"scripts\":{\"build\":\"vite build\"}}");
        var sourceId = new AtomicReference<>(UUID.randomUUID());
        var archiveId = new AtomicReference<>(UUID.randomUUID());
        FileSnapshot source = snapshot(sourceId, "a".repeat(64));
        FileSnapshot archived = snapshot(archiveId, "c".repeat(64));
        var material = new DeploymentSourceMaterial("survey", source, List.of("frontend/package.json"), List.of());
        var archive = new DeploymentSourceArchive(archived, "b".repeat(64), 1, 1, sourceId.get(), "a".repeat(64), "frontend");
        var fixture = new NativeAiFixture();
        var grants = new AtomicInteger();
        var host = (InteractionService) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{InteractionService.class},
            (proxy, method, args) -> {
                if (method.getName().equals("openReviewedWorkflow"))
                    return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of((WorkflowGrant) () -> {
                    }), Optional.empty());
                grants.incrementAndGet();
                throw new AssertionError("No host permission in evidence-only test");
            });
        var context = (ModContext) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ModContext.class},
            (proxy, method, args) -> {
                if (method.getName().equals("interaction")) return host;
                if (method.getName().equals("ui")) return Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{gold.debug.windowstolinux.appapi.ui.UiService.class}, (unused, uiMethod, values) -> {
                        if (uiMethod.getName().equals("environment"))
                            return new gold.debug.windowstolinux.appapi.ui.page.UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false);
                        throw new AssertionError(uiMethod.getName());
                    });
                try {
                    return method.invoke(fixture.context, args);
                } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                }
            });
        var server = new ServerId("fixture");
        var scope = new DeploymentAssistedScope(context, fixture.task, fixture.configurationRevision, "AUTOMATIC", true);
        var layout = DeploymentRemoteLayout.original();
        try (var permission = DeploymentWorkflowAuthorization.open(context, fixture.task, server, fixture.configurationRevision,
            "AUTOMATIC", true, "Deploy selected survey including its reviewed runtime dependencies")) {
            scope.workflowOpened(permission);
            scope.sourceCaptured(server, material, fixture.task);
            var initialPlan = DeploymentAssistedApprovalBudgetLiveTest.staticSitePlan(material, archive, "wtl-survey", layout,
                mutation.equals("SERVER") ? new ServerId("other") : server);
            var plan = mutation.startsWith("HOST_") ? companionPlan(initialPlan) : initialPlan;
            scope.archiveCaptured(material, archive, plan.request().facts().toolchainRequirements(), fixture.task);
            String identity = ReviewedPlanIdentity.from(plan.request());
            var observer = scope;
            if (mutation.equals("SOURCE")) sourceId.set(UUID.randomUUID());
            if (mutation.equals("ARCHIVE_HANDLE")) archiveId.set(UUID.randomUUID());
            if (mutation.equals("WORKFLOW")) permission.close();
            if (Set.of("REVISION", "SERVER", "SOURCE", "ARCHIVE_HANDLE", "TASK", "WORKFLOW").contains(mutation)) {
                assertThrows(IllegalArgumentException.class, () -> observer.toolchainPreparationPlanned(plan,
                    mutation.equals("REVISION") ? "d".repeat(64) : identity,
                    mutation.equals("TASK") ? new NativeAiFixture().task : fixture.task));
            } else {
                observer.toolchainPreparationPlanned(plan, identity, fixture.task);
                if (mutation.startsWith("HOST_")) {
                    var tools = preparedTools(plan);
                    if (mutation.equals("HOST_REVISION") || mutation.equals("HOST_TASK")) {
                        assertThrows(IllegalArgumentException.class, () -> observer.hostPrerequisitesObserved(plan,
                            mutation.equals("HOST_REVISION") ? "d".repeat(64) : identity,
                            hostFacts(false), tools, mutation.equals("HOST_TASK") ? new NativeAiFixture().task : fixture.task));
                    } else {
                        observer.hostPrerequisitesObserved(plan, identity, hostFacts(false), tools, fixture.task);
                        try (var command = DeploymentAssistedApprovalBudgetLiveTest.preparation(layout, "PYTHON", "3.12")) {
                            var intent = RemoteActions.command(server, identity, command).withSemantics(new ActionSemantics(
                                "deployment.toolchain.prepare.v1", Set.of(new ActionTarget(server.id(), "deployment-build", layout.toolchains()))));
                            var observed = new ObjectMapper().valueToTree(scope.effectFacts(intent, fixture.task));
                            assertEquals("wtl-survey", observed.at("/hostPrerequisiteObservations/applications/0/application").asText());
                            assertTrue(observed.at("/hostPrerequisiteObservations/applications/0/requiredCapability").asText().contains("CMake 3.25"));
                            assertEquals(identity, observed.at("/hostPrerequisiteObservations/applications/0/reviewedPlanSha256").asText());
                            if (mutation.equals("HOST_CLEARED")) {
                                observer.hostPrerequisitesObserved(plan, identity, hostFacts(true), tools, fixture.task);
                                assertFalse(scope.effectFacts(intent, fixture.task).containsKey("hostPrerequisiteObservations"));
                            }
                        }
                    }
                    assertTrue(fixture.requests.isEmpty());
                    assertEquals(0, grants.get());
                    return;
                }
                String ecosystem = mutation.equals("ECOSYSTEM") ? "RUBY" : "PYTHON";
                String branch = mutation.equals("ECOSYSTEM") ? "3.3" : mutation.equals("BRANCH") ? "3.13" : "3.12";
                try (var request = DeploymentAssistedApprovalBudgetLiveTest.preparation(layout, ecosystem, branch)) {
                    var actual = request;
                    if (mutation.equals("EXACT"))
                        actual = new gold.debug.windowstolinux.appapi.linux.command.CommandRequest(
                            request.script().replace("'3.12' '-'", "'3.12' '3.12.1'"), new byte[0], request.timeout(), request.outputLimitBytes());
                    try (var complete = actual) {
                        var intent = RemoteActions.command(server, identity, complete).withSemantics(new ActionSemantics(
                            "deployment.toolchain.prepare.v1", Set.of(new ActionTarget(server.id(), "deployment-build", layout.toolchains()))));
                        if (!Set.of("VALID", "DENY").contains(mutation))
                            assertThrows(IllegalArgumentException.class, () -> scope.facts(intent, complete, fixture.task));
                        else {
                            var facts = new ObjectMapper().valueToTree(scope.facts(intent, complete, fixture.task));
                            assertEquals("StaticSite", facts.at("/currentToolchainPlan/runtimeKind").asText());
                            assertEquals(identity, facts.at("/currentToolchainPlan/reviewedPlanSha256").asText());
                            var application = facts.at("/reviewedApplicationSources/applications/0");
                            assertEquals("wtl-survey", application.path("application").asText());
                            assertEquals("frontend", application.path("relativeRoot").asText());
                            assertEquals("a".repeat(64), application.path("sourceManifestSha256").asText());
                            assertEquals("b".repeat(64), application.path("archiveSha256").asText());
                            assertEquals("survey", facts.at("/sourceSelection/displayName").asText());
                            assertTrue(facts.at("/currentToolchainPlan/runtimeDependencyReason").asText().contains("Python 3.12"));
                            var needs = facts.at("/currentToolchainPlan/requirements");
                            assertTrue(java.util.stream.StreamSupport.stream(needs.spliterator(), false)
                                .anyMatch(value -> value.path("ecosystem").asText().equals("PYTHON") && value.path("derivation").asText().equals("REVIEWED_RUNTIME")));
                            var declarations = facts.at("/sourceArchiveObservations/archives/0/sourceToolchainRequirements");
                            assertTrue(java.util.stream.StreamSupport.stream(declarations.spliterator(), false)
                                .noneMatch(value -> value.path("ecosystem").asText().equals("PYTHON")));
                            if (mutation.equals("DENY")) {
                                for (int attempt = 0; attempt < 2; attempt++) {
                                    fixture.nativeReply(new gold.debug.windowstolinux.appapi.ai.model.AiToolCall("deny-" + attempt, "submit_result",
                                        "{\"decision\":\"DENY\",\"requiresUserConfirmation\":false,\"reason\":\"Independent refusal\"}"));
                                    assertThrows(ApiException.class, () -> ((DeploymentCommandReview) scope.interaction()).approveCommand(intent, complete, fixture.task));
                                }
                                assertEquals(2, fixture.requests.size());
                            }
                        }
                    }
                }
            }
            if (!mutation.equals("DENY")) assertTrue(fixture.requests.isEmpty());
            assertEquals(0, grants.get());
        }
    }


    private static gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.contract.ReviewedDeploymentPlan companionPlan(
        gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.contract.ReviewedDeploymentPlan initial) {
        var r = initial.request();
        var w = r.runtime().workload();
        var workload = new gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentWorkload(
            w.mode(), w.reviewed(), w.command(), w.workingDirectory(), w.endpoints(), w.verification(), w.expectedOutput(),
            w.client(), w.inputs(), List.of(new gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentCompanion(
                "worker", "native", gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentCompanion.BuildType.CMAKE_SERVICE,
                ".w2l/bin/worker", "NATIVE_HELPER")), w.buildDirectory(), w.workers());
        var request = new gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.contract.ReviewedDeploymentRequest(
            r.server(), r.facts(), r.material(), r.archive(), r.configuration(), r.secretReferences(), r.resources(),
            r.runtime().withWorkload(workload), r.userAccessUrl(), r.limits(), r.review(), r.containerDaemonRiskAccepted(),
            r.experimentalAdapterRiskAccepted(), r.layout());
        return new gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.contract.ReviewedDeploymentPlan(request, initial.steps());
    }

    private static gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentResolvedToolchains preparedTools(
        gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.contract.ReviewedDeploymentPlan plan) {
        var catalog = gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainCatalog.defaults();
        var selections = gold.debug.windowstolinux.mod.deployment.service.deploy.toolchain.DeploymentProjectToolchainRequirements
            .from(plan.request().facts(), plan.request().runtime()).stream().map(need ->
                new gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentResolvedToolchains.Selection(need,
                    gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainVersion.parse(need.ecosystem(),
                        need.ecosystem().name().equals("NODE") ? "24.0.0" : "3.12.0").orElseThrow(),
                    "/opt/fixture/" + need.ecosystem().name().toLowerCase(Locale.ROOT),
                    gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentResolvedToolchains.OriginType.MANAGED,
                    "fixture", "e".repeat(64))).toList();
        return new gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentResolvedToolchains(catalog.revision(), selections);
    }

    private static gold.debug.windowstolinux.mod.deployment.service.core.model.capability.DeploymentLinuxCapabilityFacts hostFacts(boolean ready) {
        var values = new HashMap<String, String>(Map.of("DISTRO_ID", "ubuntu", "VERSION", "24.04", "ARCH", "x86_64",
            "PACKAGE_MANAGER", "apt", "PACKAGE_ARCH", "amd64", "SYSTEMD", "1", "CPU_LEVEL", "x86-64-v1"));
        if (ready) {
            values.put("TOOL_CMAKE", "3.28.3");
            values.put("TOOL_NINJA", "1.11.1");
        }
        return gold.debug.windowstolinux.mod.deployment.linux.probe.DeploymentPlatformCapabilityParser.fromValues(values, "fixture");
    }

    private FileSnapshot snapshot(AtomicReference<UUID> id, String sha) {
        return (FileSnapshot) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{FileSnapshot.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "id" -> id.get();
                case "sha256" -> sha;
                case "directory" -> directory.toAbsolutePath();
                default -> throw new AssertionError(method.getName());
            });
    }
}
