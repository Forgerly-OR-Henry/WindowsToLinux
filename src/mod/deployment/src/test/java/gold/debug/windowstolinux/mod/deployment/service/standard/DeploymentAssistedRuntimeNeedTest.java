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
    @ValueSource(strings = {"VALID", "REVISION", "SERVER", "SOURCE", "ARCHIVE_HANDLE", "TASK", "WORKFLOW", "BRANCH", "ECOSYSTEM", "EXACT", "DENY"})
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
            var plan = DeploymentAssistedApprovalBudgetLiveTest.staticSitePlan(material, archive, "wtl-survey", layout,
                mutation.equals("SERVER") ? new ServerId("other") : server);
            scope.archiveCaptured(material, archive, plan.request().facts().toolchainRequirements(), fixture.task);
            String identity = ReviewedPlanIdentity.from(plan.request());
            var observer = (DeploymentCommandReview) scope.interaction();
            if (mutation.equals("SOURCE")) sourceId.set(UUID.randomUUID());
            if (mutation.equals("ARCHIVE_HANDLE")) archiveId.set(UUID.randomUUID());
            if (mutation.equals("WORKFLOW")) permission.close();
            if (Set.of("REVISION", "SERVER", "SOURCE", "ARCHIVE_HANDLE", "TASK", "WORKFLOW").contains(mutation)) {
                assertThrows(IllegalArgumentException.class, () -> observer.toolchainPreparationPlanned(plan,
                    mutation.equals("REVISION") ? "d".repeat(64) : identity,
                    mutation.equals("TASK") ? new NativeAiFixture().task : fixture.task));
            } else {
                observer.toolchainPreparationPlanned(plan, identity, fixture.task);
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
                                    assertThrows(ApiException.class, () -> observer.approveCommand(intent, complete, fixture.task));
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
