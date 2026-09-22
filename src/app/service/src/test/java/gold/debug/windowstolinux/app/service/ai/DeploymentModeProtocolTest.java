package gold.debug.windowstolinux.app.service.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.*;
import gold.debug.windowstolinux.app.db.DesktopPersistence;
import gold.debug.windowstolinux.app.service.server.DesktopSecretStoreService;
import gold.debug.windowstolinux.shared.ai.client.*;
import gold.debug.windowstolinux.shared.ai.transport.*;
import gold.debug.windowstolinux.shared.deploy.contract.AutomaticDeploymentInteraction;
import gold.debug.windowstolinux.shared.model.agent.*;
import gold.debug.windowstolinux.shared.model.ai.*;
import gold.debug.windowstolinux.shared.model.deployment.*;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;
import gold.debug.windowstolinux.shared.standard.deploy.assistance.AssistedDeploymentAdvisor;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@Timeout(45)
class DeploymentModeProtocolTest {
    @TempDir
    Path directory;

    final ObjectMapper json = new ObjectMapper();
    char[] master() {
        return "isolated-test-password".toCharArray();
    }

    AiProviderProfile profile(String id) {
        return new AiProviderProfile(id, URI.create("https://" + id + ".invalid/v1/chat/completions"), id,
                "ai/key/" + id, CredentialStorageMode.MASTER_PASSWORD);
    }

    void seed(DesktopPersistence db, AiPurposeType purpose, String... ids) throws Exception {
        var secrets = new DesktopSecretStoreService(db.encryptedSecrets());
        var members = new ArrayList<AiPurposeAssignment>();
        for (var id : ids) {
            var profile = profile(id);
            try (var store = secrets.open(profile.credentialMode(), master())) {
                store.save(profile.credentialKey(), ("temporary-" + id).toCharArray());
            }
            db.aiProfiles().saveVerified(profile.stored(), id, Instant.now());
            members.add(new AiPurposeAssignment(id, true));
        }
        db.aiProfiles().purposes().save(purpose, members);
    }

    AiUseCaseFacade facade(DesktopPersistence db, RoleChatTransport transport) {
        return new AiUseCaseFacade(db.aiProfiles(), new DesktopSecretStoreService(db.encryptedSecrets()),
                new OpenAiCompatibleRoleClient(), new StructuredAiClient(transport));
    }

    RoleChatResult reply(Object response) throws java.io.IOException {
        return new RoleChatResult(200,
                json.writeValueAsString(Map.of("choices",
                        List.of(Map.of("message", Map.of("content", json.writeValueAsString(response)))), "usage",
                        Map.of("total_tokens", 9))));
    }

    JsonNode input(String body) throws java.io.IOException {
        return json.readTree(json.readTree(body).path("messages").get(1).path("content").asText());
    }

    AgentAction action() {
        return new AgentAction("operation", "task", "server/account", "a".repeat(64), 1, AgentToolType.VERIFY_SERVER,
                Map.of(), Map.of("identity", "verified"), AgentRiskLevel.NORMAL);
    }

    @Test
    void reviewerTechnicalFailureMovesForwardButValidDenialNeverShopsForApproval() throws Exception {
        try (var db = DesktopPersistence.open(directory)) {
            seed(db, AiPurposeType.DEPLOYMENT, "deploy");
            seed(db, AiPurposeType.APPROVAL, "broken", "review", "unused");
            var calls = new ArrayList<String>();
            var facade = facade(db, (endpoint, key, body) -> {
                String model = json.readTree(body).path("model").asText();
                calls.add(model);
                assertFalse(body.contains("private-deployment-transcript"));
                if (model.equals("broken"))
                    return new RoleChatResult(503, "unavailable");
                var action = input(body).path("action");
                return reply(Map.of("decision", "DENY", "risk", "HIGH", "binding", action.path("binding").asText(),
                        "reason", "scope rejected", "evidence", List.of("identity")));
            });
            try (var scope = facade.openDeployment(DeploymentAutomationMode.AGENT);
                    var models = facade.agentModels(master())) {
                assertEquals(AgentReviewDecision.DENY,
                        models.review("deploy", action(), AgentRiskLevel.NORMAL).decision());
                assertEquals(List.of("broken", "review"), calls);
                assertEquals(AgentReviewDecision.DENY,
                        models.review("a different legitimate action", action(), AgentRiskLevel.NORMAL).decision());
                assertEquals(List.of("broken", "review", "review"), calls);
                assertEquals("18", scope.metrics().get("reportedTokens"));
            }
        }
    }

    @Test
    void explicitSnapshotReplacementDoesNotRetryPreviouslyConsumedModels() throws Exception {
        try (var db = DesktopPersistence.open(directory)) {
            seed(db, AiPurposeType.DEPLOYMENT, "a", "b");
            seed(db, AiPurposeType.APPROVAL, "review");
            var facade = facade(db, (endpoint, key, body) -> {
                throw new AssertionError("snapshot changes must not invoke models");
            });
            try (var scope = facade.openDeployment(DeploymentAutomationMode.AGENT)) {
                String before = scope.binding();
                assertTrue(scope.advance(AiPurposeType.DEPLOYMENT));
                seed(db, AiPurposeType.DEPLOYMENT, "a", "b", "c");
                assertEquals(List.of("a", "b"),
                        scope.providers(AiPurposeType.DEPLOYMENT).stream().map(AiProviderProfile::id).toList());
                scope.replace(facade.deploymentSnapshot(DeploymentAutomationMode.AGENT));
                assertEquals(List.of("b", "c"),
                        scope.remaining(AiPurposeType.DEPLOYMENT).stream().map(AiProviderProfile::id).toList());
                assertNotEquals(before, scope.binding());
                assertTrue(scope.advance(AiPurposeType.DEPLOYMENT));
                assertEquals("c", scope.remaining(AiPurposeType.DEPLOYMENT).getFirst().id());
            }
        }
    }

    @Test
    void assistedAnalysisSelectsSupportedCandidatesAndLeavesSecretsToHumans() throws Exception {
        try (var db = DesktopPersistence.open(directory)) {
            seed(db, AiPurposeType.APPROVAL, "review");
            seed(db, AiPurposeType.DEPLOYMENT, "assist");
            var phases = new ArrayList<String>();
            var pending = new ArrayList<String>();
            var facade = facade(db, (endpoint, key, body) -> {
                assertFalse(body.contains("NEVER_SEND_SECRET"));
                var context = input(body);
                String phase = context.path("phase").asText();
                phases.add(phase);
                return reply(Map.of("summary", "checked", "suggestions",
                        phase.equals("ANALYSIS")
                                ? List.of(
                                        Map.of("field", "app/type", "candidate", "NODE", "evidence",
                                                List.of("candidate/app/type")),
                                        Map.of("field", "app/runtime", "candidate", "A", "evidence",
                                                List.of("candidate/app/runtime")))
                                : List.of(),
                        "unresolved", List.of()));
            });
            var interaction = new AutomaticDeploymentInteraction() {
                public Optional<Map<String, String>> requestInputs(List<DeploymentInputField> fields) {
                    var values = new HashMap<String, String>();
                    for (var field : fields) {
                        pending.add(field.id());
                        values.put(field.id(), field.choices().getLast());
                    }
                    return Optional.of(values);
                }

                public boolean confirm(String key, Map<String, ?> details) {
                    throw new AssertionError("no provider failure or unresolved issues");
                }

                public char[] requestSecret(String key) {
                    throw new AssertionError();
                }
            };
            try (var scope = facade.openDeployment(DeploymentAutomationMode.ASSISTED)) {
                Path sourceRoot = Files.createDirectories(directory.resolve("source"));
                Files.writeString(sourceRoot.resolve("README.md"), "Node service requires version 21.");
                var source = new gold.debug.windowstolinux.shared.source.browse.SourceBrowser(sourceRoot);
                var analysisCalls = new AtomicInteger();
                var advisor = new AssistedDeploymentAdvisor(
                        (phase, candidates, evidence) -> facade.assist(phase, candidates, evidence, master()),
                        interaction, m -> {
                        }, (context, observations, left) -> {
                            var advice = new gold.debug.windowstolinux.shared.model.deployment.AssistedDeploymentAdvice(
                                    "source declaration", List.of(), List.of());
                            if (analysisCalls.getAndIncrement() == 0)
                                return new gold.debug.windowstolinux.shared.standard.deploy.assistance.AssistedAnalysisDecision(
                                        "READ", source.revision(), "README.md", 0, 10, advice);
                            return new gold.debug.windowstolinux.shared.standard.deploy.assistance.AssistedAnalysisDecision(
                                    "ADVISE", source.revision(), "", 0, 1,
                                    new gold.debug.windowstolinux.shared.model.deployment.AssistedDeploymentAdvice(
                                            "checked",
                                            List.of(new gold.debug.windowstolinux.shared.model.deployment.AssistedDeploymentAdvice.Candidate(
                                                    "app/type", "NODE_SERVICE", List.of("source/0")),
                                                    new gold.debug.windowstolinux.shared.model.deployment.AssistedDeploymentAdvice.Candidate(
                                                            "app/version", "21", List.of("source/0"))),
                                            List.of()));
                        }, new gold.debug.windowstolinux.shared.standard.deploy.assistance.AssistedDecisionBudget());
                advisor.source(source);
                var values = advisor.complete(List.of(
                        new DeploymentInputField("app/type", "label", "help", "",
                                List.of("NODE_SERVICE", "STATIC_SITE")),
                        new DeploymentInputField("app/version", "label", "help", "", List.of("17", "21")),
                        new DeploymentInputField("app/password", "label", "help", "", List.of("NEVER_SEND_SECRET"))));
                assertEquals("NODE_SERVICE", values.get("app/type"));
                assertEquals("21", values.get("app/version"));
                assertEquals(List.of("app/password"), pending);
                advisor.check("PREFLIGHT", Map.of("configuration", "validated"));
                advisor.check("FAILURE", Map.of("deploymentStatus", "FAILED_BUILD"));
                assertEquals(List.of("PREFLIGHT", "FAILURE"), phases);
                assertEquals("2", scope.metrics().get("modelCalls"));
            }
        }
    }

    @Test
    void halfAiProviderExhaustionRequiresAnExplicitManualChoice() throws Exception {
        for (boolean accept : List.of(false, true)) {
            try (var db = DesktopPersistence.open(directory.resolve(Boolean.toString(accept)))) {
                seed(db, AiPurposeType.APPROVAL, "review");
                seed(db, AiPurposeType.DEPLOYMENT, "broken");
                var requests = new AtomicInteger();
                var confirmations = new AtomicInteger();
                var facade = facade(db, (endpoint, key, body) -> {
                    requests.incrementAndGet();
                    return new RoleChatResult(503, "unavailable");
                });
                var interaction = new AutomaticDeploymentInteraction() {
                    public Optional<Map<String, String>> requestInputs(List<DeploymentInputField> fields) {
                        throw new AssertionError();
                    }

                    public boolean confirm(String key, Map<String, ?> details) {
                        assertEquals("deployment.assisted.manualFallback", key);
                        confirmations.incrementAndGet();
                        return accept;
                    }

                    public char[] requestSecret(String key) {
                        throw new AssertionError();
                    }
                };
                try (var scope = facade.openDeployment(DeploymentAutomationMode.ASSISTED)) {
                    var advisor = new AssistedDeploymentAdvisor(
                            (phase, candidates, evidence) -> facade.assist(phase, candidates, evidence, master()),
                            interaction, m -> {
                            });
                    if (accept) {
                        advisor.check("PREFLIGHT", Map.of("source", "verified"));
                        advisor.check("FAILURE", Map.of("error", "build"));
                    } else
                        assertThrows(CancellationException.class, () -> advisor.check("PREFLIGHT", Map.of()));
                    assertEquals(1, requests.get());
                    assertEquals(1, confirmations.get());
                }
            }
        }
    }

    @Test
    void cancellationStopsBeforeFallbackAndRefreshSelectsOnlyTheNewSnapshot() throws Exception {
        try (var db = DesktopPersistence.open(directory)) {
            seed(db, AiPurposeType.DEPLOYMENT, "deploy");
            seed(db, AiPurposeType.APPROVAL, "broken", "review");
            var calls = new AtomicInteger();
            var facade = facade(db, (endpoint, key, body) -> {
                calls.incrementAndGet();
                return new RoleChatResult(503, "unavailable");
            });
            try (var scope = facade.openDeployment(DeploymentAutomationMode.AGENT);
                    var models = facade.agentModels(master())) {
                scope.beforeRequest(() -> {
                    if (calls.get() > 0)
                        throw new CancellationException("cancelled before next provider");
                });
                assertThrows(CancellationException.class,
                        () -> models.review("deploy", action(), AgentRiskLevel.NORMAL));
                assertEquals(1, calls.get());
            }
            seed(db, AiPurposeType.APPROVAL, "review", "replacement");
            var used = new ArrayList<String>();
            var next = facade(db, (endpoint, key, body) -> {
                used.add(json.readTree(body).path("model").asText());
                return reply(Map.of("decision", "DENY", "risk", "HIGH", "binding", action().binding(), "reason",
                        "valid denial", "evidence", List.of("identity")));
            });
            try (var scope = next.openDeployment(DeploymentAutomationMode.AGENT);
                    var models = next.agentModels(master())) {
                scope.beforeRequest(() -> {
                    try {
                        db.aiProfiles().purposes().save(AiPurposeType.APPROVAL,
                                List.of(new AiPurposeAssignment("replacement", true)));
                        scope.replace(next.deploymentSnapshot(DeploymentAutomationMode.AGENT));
                    } catch (Exception failure) {
                        throw new IllegalStateException(failure);
                    }
                    scope.beforeRequest(() -> {
                    });
                });
                assertEquals(AgentReviewDecision.DENY,
                        models.review("deploy", action(), AgentRiskLevel.NORMAL).decision());
                assertEquals(List.of("replacement"), used);
            }
        }
    }
}
