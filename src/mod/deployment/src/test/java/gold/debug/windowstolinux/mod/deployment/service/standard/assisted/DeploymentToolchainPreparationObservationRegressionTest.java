package gold.debug.windowstolinux.mod.deployment.service.standard.assisted;

import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import gold.debug.windowstolinux.appapi.linux.command.*;
import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.*;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.ai.NativeAiFixture;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentWorkflowAuthorization;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.linux.build.*;
import gold.debug.windowstolinux.mod.deployment.linux.command.contract.DeploymentCommandReview;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.*;
import gold.debug.windowstolinux.mod.deployment.service.deploy.toolchain.DeploymentToolchainSetup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class DeploymentToolchainPreparationObservationRegressionTest {
    private static final DeploymentRemoteLayout LAYOUT = DeploymentRemoteLayout.original();

    private static final ToolchainRequirement REQUIREMENT = ToolchainRequirement.declared(DeploymentToolchainType.GO, "1.27", "go.mod", ToolchainRequirement.PurposeType.BUILD);

    private static final String DIRECTORY = LAYOUT.toolchains() + "/versions/go-" + "a".repeat(64);

    private static final String SOURCE = "https://fixture.invalid/go.tar.gz";

    private static final String RECEIPT = "TOOLCHAIN=GO|1.27.0|" + DIRECTORY + "|MANAGED|" + SOURCE + "|" + "b".repeat(64) + "\n";

    @Test
    void standardSetupRecordsMatchedReceiptsBeforeTheFreshBindingReview() throws Exception {
        try (var h = new Harness("VALID")) {
            var result = new DeploymentToolchainSetup(h.channel, DeploymentToolchainCatalog.defaults()).prepare(List.of(REQUIREMENT), 0, 120);
            assertEquals(TaskResult.SUCCEEDED, result.outcome(), result::toString);
            assertEquals(1, h.observations.get());
            assertEquals(2, h.grants.get());
            assertEquals(2, h.sends.get());
            assertEquals(DeploymentToolchainBindingCodec.identity(result.value().orElseThrow()), h.bindings().getFirst().get("bindingSha256"));
            assertTrue(h.fixture.requests.isEmpty(), "No real model or remote access belongs to this unit fixture");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"NONZERO", "TRUNCATED", "MALFORMED"})
    void unsuccessfulOrMalformedReceiptsCannotProducePreparationObservations(String mutation) throws Exception {
        try (var h = new Harness(mutation)) {
            var result = new DeploymentToolchainSetup(h.channel, DeploymentToolchainCatalog.defaults()).prepare(List.of(REQUIREMENT), 0, 120);
            assertNotEquals(TaskResult.SUCCEEDED, result.outcome());
            assertEquals(0, h.observations.get());
            assertEquals(mutation.equals("NONZERO") ? 2 : 1, h.sends.get());
            assertTrue(h.bindings().isEmpty());
        }
    }

    @Test
    void changedSelectionsAndReusedReceiptCannotCreateFreshObservations() throws Exception {
        try (var h = new Harness("VALID")) {
            assertThrows(IllegalArgumentException.class, () -> h.channel.toolchainsPrepared(tools(REQUIREMENT, DIRECTORY)));
            assertEquals(TaskResult.SUCCEEDED, h.channel.prepare(DeploymentToolchainType.GO, "1.27", "-", 120, "-").outcome());
            assertThrows(IllegalArgumentException.class, () -> h.channel.toolchainsPrepared(tools(REQUIREMENT, DIRECTORY + "-changed")));
            assertEquals(0, h.observations.get());
            h.channel.toolchainsPrepared(tools(REQUIREMENT, DIRECTORY));
            assertEquals(1, h.observations.get());
            assertThrows(IllegalArgumentException.class, () -> h.channel.toolchainsPrepared(tools(REQUIREMENT, DIRECTORY)));
            assertEquals(1, h.observations.get());
        }
    }

    @Test
    void observationRejectsAnotherTaskServerClosedWorkflowAndModelRoute() throws Exception {
        try (var h = new Harness("VALID")) {
            var observer = h.scope;
            var tools = tools(REQUIREMENT, DIRECTORY);
            assertThrows(IllegalArgumentException.class, () -> observer.toolchainsPrepared(new ServerId("other"), tools, h.fixture.task));
            var sameId = proxy(TaskContext.class, (p, m, a) -> h.fixture.task.id());
            assertThrows(IllegalArgumentException.class, () -> observer.toolchainsPrepared(h.server, tools, sameId));
            h.fixture.configurationRevision = "changed";
            assertThrows(ApiException.class, () -> observer.toolchainsPrepared(h.server, tools, h.fixture.task));
            h.fixture.configurationRevision = "fixture-route";
            h.permission.close();
            assertThrows(IllegalArgumentException.class, () -> observer.toolchainsPrepared(h.server, tools, h.fixture.task));
        }
    }

    @Test
    void recentPreparationSetsAreBoundedAndSharedByCurrentTaskReviews() throws Exception {
        try (var h = new Harness("VALID")) {
            var observer = h.scope;
            for (int index = 0; index < 33; index++) {
                var selection = tools(REQUIREMENT, DIRECTORY).selections().getFirst();
                observer.toolchainsPrepared(h.server, new DeploymentResolvedToolchains(DeploymentToolchainCatalog.defaults().revision(), List.of(
                    new DeploymentResolvedToolchains.Selection(REQUIREMENT, DeploymentToolchainVersion.parse(DeploymentToolchainType.GO, "1.27." + index).orElseThrow(),
                        selection.directory(), selection.origin(), selection.source(), selection.sha256()))), h.fixture.task);
            }
            assertEquals(32, h.bindings().size());
            var observation = (Map<?, ?>) h.scope.effectFacts(h.intent("deployment.candidate.capacity.v1"), h.fixture.task)
                .get("toolchainPreparationObservations");
            assertEquals(h.bindings(), observation.get("bindings"));
        }
    }

    private static DeploymentResolvedToolchains tools(ToolchainRequirement requirement, String directory) {
        return new DeploymentResolvedToolchains(DeploymentToolchainCatalog.defaults().revision(), List.of(new DeploymentResolvedToolchains.Selection(
            requirement, DeploymentToolchainVersion.parse(DeploymentToolchainType.GO, "1.27.0").orElseThrow(), directory,
            DeploymentResolvedToolchains.OriginType.MANAGED, SOURCE, "b".repeat(64))));
    }

    private static final class Harness implements AutoCloseable {
        final NativeAiFixture fixture = new NativeAiFixture();

        final ServerId server = new ServerId("fixture-server");

        final AtomicInteger grants = new AtomicInteger(), sends = new AtomicInteger(), observations = new AtomicInteger();

        final DeploymentAssistedScope scope;

        final WorkflowGrant permission;

        final DeploymentBuildCommandChannel channel;

        final Path temporary = Files.createTempDirectory("prepared-observation-");

        Harness(String mutation) throws Exception {
            var host = proxy(InteractionService.class, (p, m, a) -> {
                assertEquals("openReviewedWorkflow", m.getName());
                return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of((WorkflowGrant) () -> {
                }), Optional.empty());
            });
            var context = proxy(ModContext.class, (p, m, a) -> m.getName().equals("interaction") ? host : invoke(fixture.context, m, a));
            scope = new DeploymentAssistedScope(context, fixture.task, fixture.configurationRevision, "AUTOMATIC", true);
            permission = DeploymentWorkflowAuthorization.open(context, fixture.task, server, fixture.configurationRevision, "AUTOMATIC", true, "original source deployment");
            scope.workflowOpened(permission);
            var snapshot = (gold.debug.windowstolinux.appapi.file.snapshot.FileSnapshot) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{gold.debug.windowstolinux.appapi.file.snapshot.FileSnapshot.class}, (p, m, a) -> switch (m.getName()) {
                    case "id" -> UUID.fromString("11111111-1111-4111-8111-111111111111");
                    case "sha256" -> "a".repeat(64);
                    case "directory" -> temporary;
                    default -> throw new AssertionError(m.getName());
                });
            Files.writeString(temporary.resolve("go.mod"), "module fixture\ngo 1.27\n");
            Files.writeString(temporary.resolve("main.go"), "package main\nfunc main() {}\n");
            var material = new gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceMaterial("fixture", snapshot, List.of("go.mod", "main.go"), List.of());
            var archive = new gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceArchive(snapshot, "b".repeat(64), 1, 1, snapshot.id(), snapshot.sha256(), "");
            var facts = new gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentProjectFacts(temporary, "fixture",
                gold.debug.windowstolinux.mod.deployment.service.core.model.project.kind.DeploymentProjectKind.GO_SERVICE,
                gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentBuildToolType.GO_MODULE, List.of(), List.of(), List.of()).withToolchains(List.of(REQUIREMENT));
            var request = new gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.contract.ReviewedDeploymentRequest(server, facts, material, archive,
                gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigSnapshot.create("fixture", 1, "fixture", java.time.Instant.EPOCH, List.of()),
                List.of(), new gold.debug.windowstolinux.mod.deployment.service.core.model.resource.DeploymentResourceBindings(List.of(), Optional.of(List.of())),
                new gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentRuntimeDefinition.GoService("1.27", "app", "main.go",
                    new gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentHealthCheck.Process(5, 1)),
                Optional.empty(), gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigLoader.current().build().limits(),
                new gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.contract.DeploymentReviewConsent("fixture", server.id(), snapshot.sha256(), archive.sha256(), java.time.Instant.EPOCH), false, true, LAYOUT);
            scope.sourceCaptured(server, material, fixture.task);
            scope.archiveCaptured(material, archive, List.of(REQUIREMENT), fixture.task);
            var plan = new gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.contract.ReviewedDeploymentPlan(request, List.of(
                gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.contract.DeploymentPlanAction.VERIFY_SOURCE_IDENTITY,
                gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.contract.DeploymentPlanAction.CHECK_HEALTH,
                gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.contract.DeploymentPlanAction.ROLLBACK_ON_FAILURE));
            scope.bindPreparation(plan, fixture.task);
            var boundObserver = scope.preparationObserver(plan, fixture.task);
            var grant = new ApprovalGrant(UUID.randomUUID());
            var review = proxy(DeploymentCommandReview.class, (p, m, a) -> {
                assertEquals("approveCommand", m.getName());
                if (((ActionIntent) a[0]).semantics().orElseThrow().operation().equals("deployment.toolchain.bind.v1"))
                    assertFalse(bindings().isEmpty(), "Prior actual receipts must be available before the new binding grant");
                grants.incrementAndGet();
                return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(grant), Optional.empty());
            });
            var session = proxy(RemoteSession.class, (p, m, a) -> {
                if (m.getName().equals("facts")) return new gold.debug.windowstolinux.appapi.linux.session.RemoteFacts("linux", "amd64", "SHA256:controlled");
                assertEquals("execute", m.getName());
                assertSame(grant, a[1]);
                sends.incrementAndGet();
                String script = DeploymentManagedHelperBundle.verifiedBody(((CommandRequest) a[0]).script());
                if (script.contains("'diagnose-only'"))
                    return new OperationResult<CommandResult>(TaskResult.FAILED, Optional.empty(), Optional.of(new Problem(ProblemCode.IO_FAILURE,
                        "FIXTURE_DIAGNOSTIC_UNAVAILABLE", "Controlled diagnostic read is unavailable", Map.of(), Optional.empty())));
                if (script.contains("'bind' '")) {
                    var match = java.util.regex.Pattern.compile("'bind' '([^']+)'").matcher(script);
                    assertTrue(match.find());
                    var set = DeploymentToolchainBindingCodec.decode(new String(Base64.getDecoder().decode(match.group(1)), StandardCharsets.UTF_8));
                    return reply(0, "TOOLCHAIN_BINDING=" + DeploymentToolchainBindingCodec.identity(set) + "\n", false);
                }
                return reply(mutation.equals("NONZERO") ? 1 : 0, mutation.equals("MALFORMED") ? "TOOLCHAIN=GO|broken\n" : RECEIPT, mutation.equals("TRUNCATED"));
            });
            channel = new DeploymentBuildCommandChannel(session, review, server, fixture.task, "c".repeat(64), new DeploymentBuildBudget(3600),
                gold.debug.windowstolinux.mod.deployment.linux.command.dispatch.DeploymentSendGuard.NONE, LAYOUT,
                new gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentPreparationObserver() {
                    @Override
                    public void toolsPrepared(ServerId observedServer, DeploymentResolvedToolchains tools, TaskContext observedTask) throws ApiException {
                        boundObserver.toolsPrepared(observedServer, tools, observedTask);
                        observations.incrementAndGet();
                    }
                });
        }

        ActionIntent intent(String operation) {
            return new ActionIntent("platform.ssh.execute", List.of(server.id()), "fixture", "c".repeat(64), "exact", "effect")
                .withSemantics(new ActionSemantics(operation, Set.of(new ActionTarget(server.id(), "deployment-build", LAYOUT.toolchains()))));
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> bindings() throws ApiException {
            var facts = scope.effectFacts(intent("deployment.toolchain.bind.v1"), fixture.task);
            var observation = (Map<String, ?>) facts.get("toolchainPreparationObservations");
            return observation == null ? List.of() : (List<Map<String, Object>>) observation.get("bindings");
        }

        public void close() throws ApiException {
            permission.close();
            try (var paths = Files.walk(temporary)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            } catch (java.io.IOException failure) { throw new AssertionError(failure); }
        }
    }

    private static OperationResult<CommandResult> reply(int exit, String stdout, boolean truncated) {
        return new OperationResult<>(exit == 0 ? TaskResult.SUCCEEDED : TaskResult.FAILED,
            Optional.of(new CommandResult(Optional.of(exit), stdout, "", truncated)), exit == 0 ? Optional.empty() : Optional.of(
                new Problem(ProblemCode.IO_FAILURE, "FIXTURE_PREPARATION_NONZERO", "Controlled original preparation failed", Map.of(), Optional.empty())));
    }

    private static Object invoke(Object target, Method method, Object[] arguments) throws Throwable {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}
