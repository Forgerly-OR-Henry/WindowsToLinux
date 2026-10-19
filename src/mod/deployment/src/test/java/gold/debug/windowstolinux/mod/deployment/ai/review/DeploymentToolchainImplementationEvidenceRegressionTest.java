package gold.debug.windowstolinux.mod.deployment.ai.review;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.*;
import gold.debug.windowstolinux.mod.deployment.config.*;
import gold.debug.windowstolinux.mod.deployment.linux.helper.*;
import gold.debug.windowstolinux.mod.deployment.service.standard.assisted.DeploymentAssistedPreparationReview;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class DeploymentToolchainImplementationEvidenceRegressionTest {
    static Stream<Arguments> ecosystems() {
        return Stream.of(false, true).flatMap(custom -> Stream.of("JAVA:21:21.0.12.1+1", "NODE:22:22.0.0", "PYTHON:3.12:3.12.1",
                "DOTNET:8:8.0.100", "KOTLIN:2.1:2.1.0", "GO:1.26:1.26.0", "RUST:1.95:1.95.0", "PHP:8.4:8.4.0", "RUBY:3.0:3.0.1")
            .map(value -> Arguments.of(custom, value.split(":"))));
    }

    @ParameterizedTest
    @MethodSource("ecosystems")
    void currentPreparationUsesOnlySourceBoundContractAndExactArguments(boolean custom, String[] values) throws Exception {
        var layout = layout(custom);
        var server = new ServerId("fixture");
        String javaHome = values[0].equals("KOTLIN") ? layout.toolchains() + "/versions/java-" + "a".repeat(64) : "-";
        try (var request = request(layout, values[0], values[1], values[2], javaHome)) {
            var intent = intent(server, request, layout.toolchains());
            var shared = DeploymentCommandEvidence.facts(intent, request, layout);
            var compact = DeploymentAssistedPreparationReview.facts(intent, request, layout);
            var json = new ObjectMapper().valueToTree(compact);
            String contract = DeploymentHelperReviewContract.text(layout);
            assertEquals(DeploymentManagedHelperBundle.helperSha256(layout), json.path("helperSha256").asText());
            assertTrue(contract.contains(json.path("helperSha256").asText()));
            assertEquals(layout.canonical(), json.path("remoteLayout").asText());
            assertEquals(shared.get("toolchainPreparation"), compact.get("arguments"));
            assertEquals(values[0], json.at("/arguments/ecosystem").asText());
            assertEquals(values[2], json.at("/arguments/requestedExact").asText());
            assertEquals(javaHome, json.at("/arguments/javaHome").asText());
            assertFalse(json.has("sources"));
            assertFalse(shared.containsKey("toolchainPreparationContract"), "Shared Agent/STATIC evidence stays unchanged");
            assertTrue(json.toString().getBytes(StandardCharsets.UTF_8).length < 4096);
            String instructions = DeploymentAssistedPreparationReview.instructions(intent, request, layout);
            assertTrue(instructions.contains("selected ecosystem " + values[0]));
            assertTrue(instructions.contains("future results"));
            assertTrue(instructions.contains("partial effects"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"HASH", "APPEND", "LAYOUT", "TARGET", "SERVER", "INTENT", "BRANCH", "EXACT", "SECONDS", "JAVA", "STDIN", "TIMEOUT", "C", "CPP"})
    void alteredPreparationCannotGetCompactContractOrTrustedSupplement(String mutation) throws Exception {
        var layout = layout(true);
        var server = new ServerId("fixture");
        String invocation = "'" + layout.helperPath() + "' prepare-toolchains 'prepare' 'NODE' '22' '-' '120' '-'";
        invocation = switch (mutation) {
            case "APPEND" -> invocation + "; true";
            case "BRANCH" -> invocation.replace("'22'", "'999'");
            case "EXACT" -> invocation.replace("'22' '-'", "'22' '20.0.0'");
            case "SECONDS" -> invocation.replace("'120'", "'7201'");
            case "JAVA" -> invocation.substring(0, invocation.length() - 3) + "'/tmp/java'";
            case "C", "CPP" -> invocation.replace("'NODE' '22'", "'" + mutation + "' '17'");
            default -> invocation;
        };
        String script = DeploymentManagedHelperBundle.verifiedCommand(invocation, layout);
        if (mutation.equals("HASH"))
            script = script.replace(DeploymentManagedHelperBundle.helperSha256(layout), "f".repeat(64));
        try (var request = new CommandRequest(script, mutation.equals("STDIN") ? new byte[]{1} : new byte[0],
            Duration.ofSeconds(mutation.equals("TIMEOUT") ? 134 : 135), 65536);
             var original = request(layout, "NODE", "22", "-", "-")) {
            var intent = intent(server, mutation.equals("INTENT") ? original : request,
                layout.toolchains() + (mutation.equals("TARGET") ? "/other" : ""));
            if (mutation.equals("SERVER")) intent = intent(new ServerId("other"), request, layout.toolchains())
                .withSemantics(intent.semantics().orElseThrow());
            if (mutation.equals("INTENT"))
                intent = new ActionIntent(intent.type(), intent.serverIds(), intent.target(), intent.revision(),
                    intent.exactAction() + "changed", intent.effectSummary()).withSemantics(intent.semantics().orElseThrow());
            var actual = intent;
            var selected = mutation.equals("LAYOUT") ? layout(false) : layout;
            assertThrows(IllegalArgumentException.class, () -> DeploymentAssistedPreparationReview.facts(actual, request, selected));
            assertThrows(IllegalArgumentException.class, () -> DeploymentAssistedPreparationReview.instructions(actual, request, selected));
        }
    }

    private static DeploymentRemoteLayout layout(boolean custom) {
        return custom ? new DeploymentRemoteLayout(new DeploymentConfig.RemotePaths(
            "/fixture/helper", "/fixture/apps", "/fixture/state", "/fixture/config", "/fixture/data")) : DeploymentRemoteLayout.original();
    }

    static CommandRequest request(DeploymentRemoteLayout layout, String ecosystem, String branch, String exact, String javaHome) {
        return new CommandRequest(DeploymentManagedHelperBundle.verifiedCommand("'" + layout.helperPath()
            + "' prepare-toolchains 'prepare' '" + ecosystem + "' '" + branch + "' '" + exact + "' '120' '" + javaHome + "'", layout),
            new byte[0], Duration.ofSeconds(135), 65536);
    }

    static ActionIntent intent(ServerId server, CommandRequest request, String target) {
        return RemoteActions.command(server, "model-revision", request).withSemantics(new ActionSemantics(
            "deployment.toolchain.prepare.v1", Set.of(new ActionTarget(server.id(), "deployment-build", target))));
    }
}
