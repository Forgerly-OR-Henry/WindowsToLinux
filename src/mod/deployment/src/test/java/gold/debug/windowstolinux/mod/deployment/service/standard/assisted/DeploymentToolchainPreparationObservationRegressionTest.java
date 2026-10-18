package gold.debug.windowstolinux.mod.deployment.service.standard.assisted;

import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
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
            assertEquals(1, h.sends.get());
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
            var observer = (DeploymentCommandReview) h.scope.interaction();
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
    void recentPreparationSetsAreBoundedAndNeverIncludedInUnrelatedEffects() throws Exception {
        try (var h = new Harness("VALID")) {
            var observer = (DeploymentCommandReview) h.scope.interaction();
            for (int index = 0; index < 33; index++) {
                var requirement = ToolchainRequirement.declared(DeploymentToolchainType.GO, "1.27", "project" + index + "/go.mod", ToolchainRequirement.PurposeType.BUILD);
                observer.toolchainsPrepared(h.server, tools(requirement, DIRECTORY), h.fixture.task);
            }
            assertEquals(32, h.bindings().size());
            assertFalse(h.scope.effectFacts(h.intent("deployment.candidate.capacity.v1"), h.fixture.task).containsKey("toolchainPreparationObservations"));
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
            var grant = new ApprovalGrant(UUID.randomUUID());
            var review = proxy(DeploymentCommandReview.class, (p, m, a) -> {
                if (m.getName().equals("toolchainsPrepared")) {
                    ((DeploymentCommandReview) scope.interaction()).toolchainsPrepared((ServerId) a[0], (DeploymentResolvedToolchains) a[1], (TaskContext) a[2]);
                    observations.incrementAndGet();
                    return null;
                }
                assertEquals("approveCommand", m.getName());
                if (((ActionIntent) a[0]).semantics().orElseThrow().operation().equals("deployment.toolchain.bind.v1"))
                    assertFalse(bindings().isEmpty(), "Prior actual receipts must be available before the new binding grant");
                grants.incrementAndGet();
                return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(grant), Optional.empty());
            });
            var session = proxy(RemoteSession.class, (p, m, a) -> {
                assertEquals("execute", m.getName());
                assertSame(grant, a[1]);
                sends.incrementAndGet();
                String script = DeploymentManagedHelperBundle.verifiedBody(((CommandRequest) a[0]).script());
                if (script.contains("'bind' '")) {
                    var match = java.util.regex.Pattern.compile("'bind' '([^']+)'").matcher(script);
                    assertTrue(match.find());
                    var set = DeploymentToolchainBindingCodec.decode(new String(Base64.getDecoder().decode(match.group(1)), StandardCharsets.UTF_8));
                    return reply(0, "TOOLCHAIN_BINDING=" + DeploymentToolchainBindingCodec.identity(set) + "\n", false);
                }
                return reply(mutation.equals("NONZERO") ? 1 : 0, mutation.equals("MALFORMED") ? "TOOLCHAIN=GO|broken\n" : RECEIPT, mutation.equals("TRUNCATED"));
            });
            channel = new DeploymentBuildCommandChannel(session, review, server, fixture.task, "c".repeat(64), new DeploymentBuildBudget(3600));
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
        }
    }

    private static OperationResult<CommandResult> reply(int exit, String stdout, boolean truncated) {
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new CommandResult(Optional.of(exit), stdout, "", truncated)), Optional.empty());
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
