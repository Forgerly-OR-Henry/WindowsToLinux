package gold.debug.windowstolinux.mod.deployment.service.standard.assisted;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.appapi.ai.model.AiToolCall;
import gold.debug.windowstolinux.appapi.file.snapshot.FileSnapshot;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.mod.deployment.ai.NativeAiFixture;
import gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceMaterial;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.kind.DeploymentProjectKind;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.contract.DeploymentStandardInputs.Prompt;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.input.DeploymentReviewField;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.input.DeploymentStandardRuntimeResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeploymentAssistedRequiredPreflightTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String REVISION = "a".repeat(64);

    @TempDir
    Path source;

    @Test
    void completeTcpKeepsOptionalBlankCorrectionsWithoutCallingPreflight() throws Exception {
        var fixture = new NativeAiFixture();
        var defaults = completeTcp();
        var resolver = new DeploymentStandardRuntimeResolver();
        assertTrue(resolver.missing("backend", DeploymentProjectKind.DOTNET_SERVICE, defaults).isEmpty());
        var prompt = prompt(defaults);
        assertEquals("", field(prompt, "healthEndpoint").value());

        var actual = coordinator(fixture).prompt(new ServerId("server"), prompt,
            new DeploymentAssistedAdvisor(fixture.context), material(), fixture.task);

        assertSame(prompt, actual);
        assertTrue(fixture.requests.isEmpty(), "An optional blank correction is not a missing required input");
        assertEquals(prompt.fields(), actual.fields(), "Keep every correction available to the final reviewer");
    }

    @ParameterizedTest
    @ValueSource(strings = {"primary", "secondary", "version", "port"})
    void missingRequiredTechnicalInputStillUsesEvidenceBackedPreflight(String missing) throws Exception {
        var fixture = new NativeAiFixture();
        var defaults = completeTcp();
        String candidate = defaults.remove(missing);
        var prompt = prompt(defaults);
        fixture.nativeReply(call("read", "READ", Map.of("path", "README.md", "offset", 0, "limit", 10)));
        fixture.nativeReply(call("advise", "ADVISE", Map.of(
            "suggestions", List.of(Map.of("field", "backend/" + missing, "candidate", candidate,
                "evidence", List.of("source/0"))), "summary", "Read the declared runtime input", "unresolved", List.of())));

        var actual = coordinator(fixture).prompt(new ServerId("server"), prompt,
            new DeploymentAssistedAdvisor(fixture.context), material(), fixture.task);

        assertEquals(2, fixture.requests.size());
        var facts = JSON.readTree(fixture.requests.getFirst().messages().getLast().text());
        assertEquals(1, facts.path("fields").size());
        assertEquals("backend/" + missing, facts.at("/fields/0/id").asText());
        assertEquals(candidate, field(actual, missing).value());
        assertEquals("", field(prompt, missing).value());
        assertEquals("", field(actual, "healthEndpoint").value());
        assertEquals(prompt.defaults(), actual.defaults(), "Suggestions are still inputs for final review");
        assertEquals(prompt.fields().stream().map(DeploymentReviewField::id).toList(),
            actual.fields().stream().map(DeploymentReviewField::id).toList());
    }

    private static DeploymentAssistedReviewCoordinator coordinator(NativeAiFixture fixture) {
        return new DeploymentAssistedReviewCoordinator(fixture.context, (prompt, answer) -> {
            throw new AssertionError("Preparing suggestions cannot replace final review");
        });
    }

    private static Map<String, String> completeTcp() {
        return new LinkedHashMap<>(Map.of("primary", "AssetLending", "secondary", "AssetLending.dll",
            "version", "10.0.300", "port", "18121", "healthMode", "TCP", "exposure", "INTERNAL",
            "expectedStatus", "200"));
    }

    private static Prompt prompt(Map<String, String> defaults) {
        var resolver = new DeploymentStandardRuntimeResolver();
        var fields = new LinkedHashMap<String, DeploymentReviewField>();
        resolver.missing("backend", DeploymentProjectKind.DOTNET_SERVICE, defaults)
            .forEach(field -> fields.put(field.id(), field));
        resolver.corrections("backend", defaults).forEach(field -> fields.putIfAbsent(field.id(), field));
        return new Prompt("backend", "fixture", DeploymentProjectKind.DOTNET_SERVICE, defaults,
            List.copyOf(fields.values()));
    }

    private static DeploymentReviewField field(Prompt prompt, String name) {
        return prompt.fields().stream().filter(field -> field.id().equals("backend/" + name))
            .findFirst().orElseThrow();
    }

    private static AiToolCall call(String id, String action, Map<String, ?> arguments) throws Exception {
        return new AiToolCall(id, "submit_result", JSON.writeValueAsString(Map.of(
            "action", action, "sourceRevision", REVISION, "arguments", arguments)));
    }

    private DeploymentSourceMaterial material() throws Exception {
        Files.writeString(source.resolve("README.md"),
            "SDK 10.0.300; artifact AssetLending; entrypoint AssetLending.dll; port 18121.\n");
        var snapshot = new FileSnapshot() {
            private final UUID id = UUID.randomUUID();
            public UUID id() { return id; }
            public String sha256() { return REVISION; }
            public Path directory() { return source; }
            public void close() { }
        };
        return new DeploymentSourceMaterial("fixture", snapshot, List.of("README.md"), List.of());
    }
}
