package gold.debug.windowstolinux.mod.deployment.ai.review;

import java.time.Duration;
import java.util.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.*;
import gold.debug.windowstolinux.mod.deployment.config.*;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import gold.debug.windowstolinux.app.ai.model.ModelProtocol;
import gold.debug.windowstolinux.app.ai.model.ModelProtocolCodec;
import gold.debug.windowstolinux.app.ai.model.ModelReasoningLevel;
import gold.debug.windowstolinux.app.ai.model.ModelRequestBudgetException;
import gold.debug.windowstolinux.appapi.ai.model.*;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.mod.deployment.linux.command.contract.DeploymentCommandReview;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DeploymentManagedApplicationFrameRegressionTest {
    private final DeploymentRemoteLayout layout = new DeploymentRemoteLayout(new DeploymentConfig.RemotePaths(
        "/fixture/helper", "/fixture/apps", "/fixture/state", "/fixture/config", "/fixture/data"));

    @Test
    void nativeFileTransferObservationFitsTheOriginalLateWireCeilingAndNeedsFreshHostGrant() throws Exception {
        try (var fixture = new MirroredInstallerTestFixture();
             var assisted = new DeploymentNativeHelperPackageImpactTest.Assisted(fixture)) {
            String application = "wtl-assisted-file-transfer-1wcgtokiy4-web";
            String call = "WTL_SYSTEMD_QUERY_SECONDS=20 '" + fixture.layout.helperPath()
                + "' 'observe-deployment' '" + application + "' '" + "c".repeat(64) + "'";
            try (var request = new CommandRequest(DeploymentManagedHelperBundle.verifiedCommand(call, fixture.layout),
                new byte[0], Duration.ofSeconds(20), 4096)) {
                var target = new ActionTarget(fixture.server.id(), "deployment-release", fixture.layout.applicationRoot(application));
                var semantics = new ActionSemantics("deployment.publication.observe.v1", Set.of(target));
                var intent = RemoteActions.command(fixture.server, "b".repeat(64), request).withSemantics(semantics);
                var port = (DeploymentCommandReview) assisted.scope.interaction();
                fixture.model.nativeReply(new AiToolCall("native-observe", "submit_result",
                    "{\"decision\":\"APPROVE\",\"requiresUserConfirmation\":false,\"reason\":\"Guarded native observation\"}"));
                assertEquals(TaskResult.SUCCEEDED, port.approveCommand(intent, request, fixture.task).outcome());
                assertEquals(List.of(intent), assisted.grantedIntents);
                var captured = fixture.model.requests.getFirst();
                assertEquals(1, captured.tools().size());
                var json = new ObjectMapper();
                var payload = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(captured.messages().getLast().text());
                assertEquals(intent.exactAction(), payload.path("exactAction").asText());
                assertEquals(application, payload.at("/currentCommandEvidence/publicationObservationScope/application").asText());
                assertEquals(fixture.task.id().toString(), payload.at("/currentCommandEvidence/workflowAuthorization/task").asText());
                assertEquals(target.id(), payload.at("/resourceTargets/0/id").asText());
                var system = captured.messages().getFirst();
                assertTrue(system.text().contains("A DAEMON queries its own UnitFileState"));
                assertFalse(system.text().contains("For tasks with a frozen mirror snapshot"));
                // Original wire was redacted; preserve the whole recorded facts length while encoding the new SYSTEM contract.
                int extra = 14385 - payload.toString().length() - 24;
                if (extra > 0) payload.put("replayFixturePadding", "x".repeat(extra));
                var messages = List.of(system, new AiMessage(AiRole.USER, payload.toString(), List.of()));
                int size = observationWire(captured, messages, 44149).length;
                assertEquals(size, assertThrows(ModelRequestBudgetException.class,
                    () -> observationWire(captured, messages, size - 1)).encodedBytes());
                System.out.printf("R29_NATIVE_OBSERVE_REPLAY encoded=%d originalCap=44149 oldEncoded=47597%n", size);
                var unrelated = intent.withSemantics(new ActionSemantics(semantics.operation(), Set.of(target,
                    new ActionTarget(fixture.server.id(), "deployment-release", fixture.layout.applicationRoot("sibling")))));
                assertTrue(DeploymentCommandEvidence.nativePublicationApplication(unrelated, request, fixture.layout).isEmpty());
                try (var executable = new CommandRequest(DeploymentManagedHelperBundle.verifiedCommand(
                    call + "\nprintf extra", fixture.layout), new byte[0], Duration.ofSeconds(20), 4096)) {
                    var altered = RemoteActions.command(fixture.server, intent.revision(), executable).withSemantics(semantics);
                    assertTrue(DeploymentCommandEvidence.nativePublicationApplication(altered, executable, fixture.layout).isEmpty());
                }
            }
        }
    }

    private static byte[] observationWire(AiRequest request, List<AiMessage> messages, int ceiling) {
        var bounded = new AiRequest(request.purpose(), messages, request.maxOutputTokens(), request.timeout(),
            request.jsonObjectResponse(), request.tools(), ceiling, request.requiredToolName(), request.generationRecoveryAttempts());
        return ModelProtocolCodec.forProtocol(ModelProtocol.OPENAI_RESPONSES).encode("gpt-6-luna", bounded, 65536,
            ModelReasoningLevel.HIGH, Map.of());
    }

    @ParameterizedTest
    @ValueSource(strings = {"retain", "inspect-runtime", "observe-deployment"})
    void bindsCommonApplicationOwnershipArgumentsAndDerivedPaths(String verb) throws Exception {
        var frame = facts(verb, "VALID").path("managedApplication");
        assertEquals("demo", frame.path("application").asText());
        assertEquals("c".repeat(64), frame.path("ownershipManifestSha256").asText());
        assertEquals(layout.applicationRoot("demo"), frame.path("derivedReleaseRoot").asText());
        assertEquals(verb.equals("retain"), frame.has("containerCredentialRoot"));
        if (verb.equals("retain"))
            assertEquals(layout.configurationRoot("demo") + "/.container-credentials", frame.path("containerCredentialRoot").asText());
        assertFalse(frame.has("verifiedOwnership"));
        assertFalse(frame.has("commandGrant"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"RELEASE", "CREDENTIALS", "APPLICATION"})
    void rejectsUnboundRetentionEffects(String mutation) {
        assertThrows(IllegalArgumentException.class, () -> facts("retain", mutation));
    }

    @ParameterizedTest
    @ValueSource(strings = {"EXTRA", "STDIN", "ACTION"})
    void alteredCommandsNeverReceiveExactFrameEvidence(String mutation) throws Exception {
        assertFalse(facts("retain", mutation).has("managedApplication"));
    }

    private JsonNode facts(String verb, String mutation) throws Exception {
        var server = new ServerId("fixture");
        String script = "'" + layout.helperPath() + "' '" + (mutation.equals("ACTION") ? "candidate-cleanup" : verb)
            + "' '" + (mutation.equals("APPLICATION") ? "other" : "demo") + "' '" + "c".repeat(64) + "'" + (mutation.equals("EXTRA") ? " 'extra'" : "");
        var targets = new HashSet<ActionTarget>();
        targets.add(new ActionTarget(server.id(), "deployment-release", layout.applicationRoot("demo") + (mutation.equals("RELEASE") ? ".other" : "")));
        if (verb.equals("retain"))
            targets.add(new ActionTarget(server.id(), "deployment-secret", layout.configurationRoot("demo") + "/.container-credentials" + (mutation.equals("CREDENTIALS") ? ".other" : "")));
        try (var request = new CommandRequest(DeploymentManagedHelperBundle.verifiedCommand(script, layout),
            mutation.equals("STDIN") ? new byte[]{1} : new byte[0], Duration.ofSeconds(60), 4096)) {
            String operation = verb.equals("retain") ? "retain" : verb.equals("inspect-runtime") ? "inspect" : "observe";
            var intent = RemoteActions.command(server, "b".repeat(64), request).withSemantics(new ActionSemantics(
                "deployment.publication." + operation + ".v1", targets));
            return new ObjectMapper().valueToTree(DeploymentCommandEvidence.facts(intent, request, layout));
        }
    }
}
