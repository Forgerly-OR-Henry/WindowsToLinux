package gold.debug.windowstolinux.mod.deployment.service.standard.assisted;

import java.time.*;
import java.util.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.*;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.config.*;
import gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentExecutionIdentity;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigSnapshot;
import gold.debug.windowstolinux.mod.deployment.service.core.model.application.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class DeploymentAssistedPublicationEvidenceRegressionTest {
    @Test
    void matchesOriginalDurableProposalWithoutClaimingRemoteContentOrPermission() throws Exception {
        var facts = new ObjectMapper().valueToTree(facts("VALID"));
        assertEquals("demo", facts.path("application").asText());
        assertEquals("c".repeat(64), facts.path("ownershipManifestSha256").asText());
        assertEquals("d".repeat(64), facts.path("releaseSha256").asText());
        assertEquals("e".repeat(64), facts.path("workflowPlanSha256").asText());
        assertFalse(facts.has("liveOwnership"));
        assertFalse(facts.has("commandGrant"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"TASK", "SERVER", "TARGET", "OWNER", "RELEASE", "APPLICATION", "EMPTY", "UNGUARDED"})
    void changedOriginalIdentityOrFrameCannotBecomeProposalEvidence(String mutation) {
        assertThrows(IllegalArgumentException.class, () -> facts(mutation));
    }

    private Map<String, Object> facts(String mutation) throws Exception {
        var server = new ServerId("fixture");
        var layout = new DeploymentRemoteLayout(new DeploymentConfig.RemotePaths(
            "/fixture/helper", "/fixture/apps", "/fixture/state", "/fixture/config", "/fixture/data"));
        UUID original = UUID.randomUUID();
        var task = (TaskContext) java.lang.reflect.Proxy.newProxyInstance(TaskContext.class.getClassLoader(),
            new Class<?>[]{TaskContext.class}, (proxy, method, args) -> {
                if (method.getName().equals("id")) return mutation.equals("TASK") ? UUID.randomUUID() : original;
                throw new AssertionError(method.getName());
            });
        var unit = DeploymentUnitIdentity.forManaged("demo", server, "c".repeat(64), layout);
        var runtime = new DeploymentUnitRuntimeConfig(new DeploymentHealthCheck.Process(10, 1), Optional.empty(),
            DeploymentIdentityMode.SYSTEMD_STATIC, DeploymentWorkload.unspecified());
        var commit = new DeploymentUnitCommit(unit, runtime, new DeploymentCurrentRelease("demo", "d".repeat(64), Instant.EPOCH),
            DeploymentConfigSnapshot.create("demo", 1, "v1", Instant.EPOCH, List.of()), List.of());
        var identity = new DeploymentExecutionIdentity(UUID.randomUUID(), original, Optional.empty(), "demo", server,
            DeploymentExecutionIdentity.DeploymentExecutionMode.ASSISTED, "e".repeat(64));
        String script = "'" + layout.helperPath() + "' 'publish-deployment' '" + (mutation.equals("APPLICATION") ? "other" : "demo")
            + "' 'demo-" + "a".repeat(16) + "' '" + (mutation.equals("RELEASE") ? "f" : "d").repeat(64)
            + "' '" + (mutation.equals("OWNER") ? "f" : "c").repeat(64) + "' 'identity-v2'";
        try (var request = new CommandRequest(mutation.equals("UNGUARDED") ? script : DeploymentManagedHelperBundle.verifiedCommand(script, layout),
            new byte[0], Duration.ofSeconds(30), 4096)) {
            var intent = RemoteActions.command(mutation.equals("SERVER") ? new ServerId("other") : server, "b".repeat(64), request)
                .withSemantics(new ActionSemantics("deployment.publication.publish.v1", Set.of(
                    new ActionTarget(server.id(), "deployment-release", unit.releaseRoot() + (mutation.equals("TARGET") ? ".other" : "")))));
            return DeploymentAssistedScope.publicationFacts(identity, mutation.equals("EMPTY") ? List.of() : List.of(commit), intent, request, task);
        }
    }
}
