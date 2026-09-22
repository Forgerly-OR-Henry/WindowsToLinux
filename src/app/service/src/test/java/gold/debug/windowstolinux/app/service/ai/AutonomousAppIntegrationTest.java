package gold.debug.windowstolinux.app.service.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.app.db.DesktopPersistence;
import gold.debug.windowstolinux.app.service.contract.AutomaticDeploymentApplicationFacade;
import gold.debug.windowstolinux.app.service.contract.definition.AutomaticDeploymentRequest;
import gold.debug.windowstolinux.app.service.deployment.AutonomousDeploymentUseCase;
import gold.debug.windowstolinux.app.service.deployment.automatic.*;
import gold.debug.windowstolinux.app.service.lock.ServerOperationLockRegistry;
import gold.debug.windowstolinux.app.service.server.*;
import gold.debug.windowstolinux.app.service.source.SourcePreparationUseCase;
import gold.debug.windowstolinux.app.windows.workspace.WindowsSourcePreparer;
import gold.debug.windowstolinux.shared.ai.client.*;
import gold.debug.windowstolinux.shared.ai.transport.RoleChatResult;
import gold.debug.windowstolinux.shared.deploy.contract.AutomaticDeploymentInteraction;
import gold.debug.windowstolinux.shared.linux.command.*;
import gold.debug.windowstolinux.shared.linux.connection.*;
import gold.debug.windowstolinux.shared.linux.protocol.*;
import gold.debug.windowstolinux.shared.linux.protocol.backup.RemoteBackupArtifactPort;
import gold.debug.windowstolinux.shared.linux.runtime.HealthCheckResult;
import gold.debug.windowstolinux.shared.linux.session.DeploymentRemoteSession;
import gold.debug.windowstolinux.shared.linux.transfer.*;
import gold.debug.windowstolinux.shared.linux.workspace.*;
import gold.debug.windowstolinux.shared.model.ai.*;
import gold.debug.windowstolinux.shared.model.archive.SourceArchiveDescriptor;
import gold.debug.windowstolinux.shared.model.deployment.*;
import gold.debug.windowstolinux.shared.model.lifecycle.*;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;
import gold.debug.windowstolinux.shared.standard.analyze.core.DeploymentAnalysisCoordinator;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@Timeout(45)
class AutonomousAppIntegrationTest {
    @TempDir
    Path root;

    static final String SHA = "a".repeat(64);

    final ObjectMapper json = new ObjectMapper();
    char[] master() {
        return "autonomous fixture password".toCharArray();
    }
    final ServerProfile server = new ServerProfile("server", "fixture.invalid", 22, "root", "ssh/test",
            CredentialStorageMode.MASTER_PASSWORD);

    @Test
    void rejectsAnUnsupportedManagementAccountBeforeReadingSourceOrConnecting() {
        var unprivileged = new ServerProfile("server", "fixture.invalid", 22, "deploy", "ssh/test",
                CredentialStorageMode.MASTER_PASSWORD);
        var request = new AutomaticDeploymentRequest(Optional.of(root.resolve("unread")), Optional.empty(),
                unprivileged, Map.of());
        var useCase = new AutonomousDeploymentUseCase(null, null, null, null);
        assertThrows(SecurityException.class, () -> useCase.deploy(request, master(), null, null, m -> {
        }, null, null, e -> {
        }, r -> {
        }));
    }

    @Test
    void unsupportedSingleAndDependentProjectsReachManagedInventoryThroughTheApp() throws Exception {
        for (int count : List.of(1, 2))
            try (var db = DesktopPersistence.open(root.resolve("db" + count))) {
                Path project = Files.createDirectories(root.resolve("custom" + count));
                Files.writeString(project.resolve("build.zig"), "custom unsupported fixture\n");
                var observed = new ArrayList<String>();
                var approved = new ArrayList<String>();
                var proposals = new ArrayDeque<Map<String, Object>>();
                proposals.add(tool("read", Map.of("path", "build.zig", "offset", 0, "limit", 100)));
                var components = new ArrayList<Map<String, Object>>();
                for (String id : count == 1 ? List.of("api") : List.of("api", "web"))
                    components.add(component(id, id.equals("web") ? List.of("api") : List.of()));
                proposals.add(tool("plan",
                        Map.of("explanation", "custom build observed", "components", components, "applicationHealth",
                                Map.of("component", count == 1 ? "api" : "web", "health",
                                        Map.of("type", "PROCESS", "timeout", 5, "stability", 1)))));
                proposals.add(tool("command", Map.of("component", "api", "script", "zig build broken", "seconds", 5)));
                for (var component : components) {
                    String id = (String) component.get("id");
                    proposals.add(
                            tool("command", Map.of("component", id, "script", "zig build fixed " + id, "seconds", 5)));
                    proposals.add(tool("seal", Map.of("component", id)));
                }
                proposals.add(tool("deliver", Map.of()));
                var secrets = new DesktopSecretStoreService(db.encryptedSecrets());
                for (var purpose : List.of(AiPurposeType.DEPLOYMENT, AiPurposeType.APPROVAL)) {
                    String id = purpose.name().toLowerCase(Locale.ROOT);
                    var profile = new AiProviderProfile(id, URI.create("https://fixture.invalid/v1/chat/completions"),
                            id, "ai/" + id, CredentialStorageMode.MASTER_PASSWORD);
                    try (var store = secrets.open(profile.credentialMode(), master())) {
                        store.save(profile.credentialKey(), "fixture".toCharArray());
                    }
                    db.aiProfiles().saveVerified(profile.stored(), id, Instant.now());
                    db.aiProfiles().purposes().save(purpose, List.of(new AiPurposeAssignment(id, true)));
                }
                var ai = new AiUseCaseFacade(db.aiProfiles(), secrets, new OpenAiCompatibleRoleClient(),
                        new StructuredAiClient((url, key, body) -> {
                            var request = json.readTree(body);
                            var input = json.readTree(request.get("messages").get(1).get("content").asText());
                            Object response;
                            if (request.get("model").asText().equals("approval")) {
                                var action = input.get("action");
                                approved.add(action.get("binding").asText());
                                response = Map.of("decision", "ALLOW", "risk", "NORMAL", "binding",
                                        action.get("binding").asText(), "reason", "fixture isolated command",
                                        "evidence", List.of("revision"));
                            } else {
                                var next = proposals.remove();
                                if (next.get("tool").equals("plan"))
                                    assertTrue(body.contains("custom unsupported fixture"));
                                if (next.toString().contains("zig build fixed api"))
                                    assertTrue(body.contains("missing build option"), input.toString());
                                response = next;
                            }
                            return new RoleChatResult(200, json.writeValueAsString(Map.of("choices",
                                    List.of(Map.of("message", Map.of("content", json.writeValueAsString(response)))))));
                        }));
                DeploymentLinuxGateway gateway = (endpoint, credential, verifier) -> {
                    assertEquals(HostKeyDecision.ACCEPT_FIRST_USE, verifier.verify(endpoint, "SHA256:fixture"));
                    assertTrue(verifier.authenticated(endpoint,
                            new HostKeyObservation("SHA256:fixture", "SHA256:fixture")));
                    return remote(observed);
                };
                var servers = new ServerUseCaseFacade(db.servers(), secrets, gateway);
                try (var store = secrets.open(server.credentialMode(), master())) {
                    servers.save(server, store, "fixture".toCharArray());
                }
                var source = new SourcePreparationUseCase(new DeploymentAnalysisCoordinator(),
                        new WindowsSourcePreparer(root.resolve("work" + count)));
                var standard = (AutomaticDeploymentApplicationFacade) Proxy.newProxyInstance(
                        getClass().getClassLoader(), new Class<?>[]{AutomaticDeploymentApplicationFacade.class},
                        (p, m, a) -> {
                            if (m.getName().equals("findServerProfile"))
                                return Optional.of(server);
                            throw new AssertionError(
                                    "autonomous mode must not invoke standard operation " + m.getName());
                        });
                var task = new AutomaticDeploymentTaskService(standard, ai, source, new ServerOperationLockRegistry(),
                        db.agentTasks(), null, new AutonomousDeploymentUseCase(db, servers, gateway, source));
                var interaction = new AutomaticDeploymentInteraction() {
                    public Optional<Map<String, String>> requestInputs(List<DeploymentInputField> fields) {
                        throw new AssertionError(fields);
                    }

                    public boolean confirm(String key, Map<String, ?> details) {
                        throw new AssertionError("full control: " + key);
                    }

                    public char[] requestSecret(String key) {
                        throw new AssertionError(key);
                    }
                };
                var request = new AutomaticDeploymentRequest(Optional.of(project), Optional.empty(), server,
                        Map.of("applicationId", "custom" + count))
                        .withAutomation(DeploymentAutomationMode.AGENT, AgentApprovalMode.FULL_CONTROL);
                var result = task.deploy(request, master(), interaction, f -> true, m -> {
                });
                assertEquals(DeploymentStatus.SUCCEEDED, result.status(),
                        db.agentTasks().events(request.taskId()).toString());
                assertEquals(count, result.handoffs().size());
                assertTrue(proposals.isEmpty());
                assertEquals(count + 1, approved.size());
                assertEquals(count * (count == 1 ? 1 : 2) + 1, Collections.frequency(observed, "health"));
                var graph = db.managedApplicationGraphs().find("custom" + count).orElseThrow();
                assertEquals(count, graph.components().size());
                assertTrue(graph.components().stream().allMatch(c -> c.reviewedRuntime()
                        .orElseThrow() instanceof DeploymentRuntimeSpecification.ManagedProcess));
                assertEquals("2", db.agentTasks().recent().getFirst().get("execution_semantics_version"));
                assertTrue(db.agentTasks().unresolved(server.id()).isEmpty());
                assertEquals("custom unsupported fixture\n", Files.readString(project.resolve("build.zig")));
            }
    }

    private Map<String, Object> tool(String tool, Map<String, Object> args) {
        return Map.of("tool", tool, "arguments", args);
    }

    private Map<String, Object> component(String id, List<String> dependencies) {
        var value = new LinkedHashMap<String, Object>();
        value.put("id", id);
        value.put("dependencies", dependencies);
        value.put("evidence", List.of("build.zig"));
        value.put("backend", "PROCESS");
        value.put("mode", "DAEMON");
        value.put("entrypoint", "bin/app");
        value.put("arguments", List.of());
        value.put("workingDirectory", "");
        value.put("health", Map.of("type", "PROCESS", "timeout", 5, "stability", 1));
        value.put("ports", List.of());
        value.put("resources", List.of());
        value.put("configuration", List.of());
        return value;
    }

    private DeploymentRemoteSession remote(List<String> observations) {
        var projects = new RemoteProjectPort() {
            public String open(String task, RemoteWorkspace workspace) {
                return SHA;
            }

            public String read(String task, RemoteWorkspace workspace, String revision, String path, int offset,
                    int limit) {
                throw new AssertionError();
            }

            public String patch(String task, RemoteWorkspace workspace, RemoteSourcePatch patch, String approval) {
                throw new AssertionError();
            }

            public String seal(String task, RemoteWorkspace workspace, String revision) {
                return "b".repeat(64);
            }

            public RemoteCommandResult command(String task, RemoteWorkspace workspace, String revision, String script,
                    Duration timeout, long limit) {
                var dispatch = CommandExecutionScope
                        .before(AssistedDeploymentBoundary.target(server), "helper 'agent-run'", script,
                                script.getBytes(java.nio.charset.StandardCharsets.UTF_8), timeout, limit)
                        .orElseThrow();
                boolean success = !script.contains("broken");
                var result = new RemoteCommandResult(success, false, "", success ? "built" : "missing build option", "",
                        success ? 0 : 2);
                dispatch.completed(result);
                return result;
            }
        };
        return (DeploymentRemoteSession) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{DeploymentRemoteSession.class}, (p, m, a) -> switch (m.getName()) {
                    case "close", "prepareManagedPlatform" -> null;
                    case "projects" -> projects;
                    case "uploadSource" -> {
                        var archive = (SourceArchiveDescriptor) a[0];
                        yield new SourceUploadResult(
                                ((RemoteWorkspace) a[1]).candidateRoot() + "/mutable/source.tar.gz",
                                archive.byteCount(), archive.contentSha256(), "fixture");
                    }
                    case "stageDeploymentInputs" ->
                        new RemoteDeploymentInputs(((RemoteRuntimeConfiguration) a[1]).sha256(), List.of());
                    case "snapshotDeployment" -> ReleaseSnapshot.firstDeployment("fixture");
                    case "publishDeployment", "cleanupCandidate", "retainRecentSuccessfulReleases" ->
                        new RemoteStepResult(true, false, "fixture");
                    case "checkDeploymentHealth" -> {
                        observations.add("health");
                        yield new HealthCheckResult(true, "actual fixture health");
                    }
                    case "observeDeployment" -> new LifecycleObservation((ManagedApplication) a[0],
                            RuntimeState.RUNNING, AutostartState.DISABLED, true, Instant.now(), "fixture ownership");
                    case "backupArtifacts" -> Proxy.newProxyInstance(getClass().getClassLoader(),
                            new Class<?>[]{RemoteBackupArtifactPort.class}, (bp, bm, ba) -> {
                                if (bm.getName().equals("endMaintenance"))
                                    return null;
                                throw new AssertionError(bm.getName());
                            });
                    default -> throw new AssertionError("unexpected remote operation " + m.getName());
                });
    }
}
