package gold.debug.windowstolinux.app.service.deployment.automatic;

import gold.debug.windowstolinux.app.service.contract.definition.*;

import gold.debug.windowstolinux.app.service.contract.AutomaticDeploymentApplicationFacade;
import gold.debug.windowstolinux.app.service.deployment.single.*;
import gold.debug.windowstolinux.app.service.source.*;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.lock.ServerOperationLockRegistry;
import gold.debug.windowstolinux.app.windows.workspace.WindowsSourcePreparer;
import gold.debug.windowstolinux.shared.analyze.core.DeploymentAnalysisCoordinator;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationSnapshot;
import gold.debug.windowstolinux.shared.deploy.contract.*;
import gold.debug.windowstolinux.shared.deploy.contract.result.deployment.DeploymentResult;
import gold.debug.windowstolinux.shared.model.deployment.*;
import gold.debug.windowstolinux.shared.model.health.UserAccessUrl;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;
import gold.debug.windowstolinux.shared.model.server.ServerIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CancellationException;
import static org.junit.jupiter.api.Assertions.*;

class AutomaticDeploymentUseCaseTest {
    @TempDir Path directory;
    private final List<String> calls = new ArrayList<>();
    private boolean completeMultiple;
    private boolean declineEnvironment;
    private DeploymentStatus terminal = DeploymentStatus.SUCCEEDED;
    private final ServerIdentity server = new ServerIdentity("test-server", "192.0.2.1", 22, "SHA256:test-fingerprint");
    private final ServerProfile profile = new ServerProfile("test-server", "192.0.2.1", 22, "test", "ssh-test", CredentialStorageMode.WINDOWS_CREDENTIAL_MANAGER);

    @Test void completesOneLocalClickWithGroupedInputAndRealSnapshotWithoutRequiringConfigurationText() throws Exception {
        Path root = node(directory.resolve("source"), "demo");
        char[] master = "temporary-test-value".toCharArray();
        var result = useCase().deploy(new AutomaticDeploymentRequest(Optional.of(root), Optional.empty(), profile, Map.of()),
                master, interaction(false), ignored -> true, ignored -> { });
        assertEquals(DeploymentStatus.SUCCEEDED, result.status());
        assertEquals(1, result.handoffs().size());
        assertEquals(1, Collections.frequency(calls, "environment"));
        assertEquals(1, Collections.frequency(calls, "deploy"));
        assertEquals(1, Collections.frequency(calls, "environment-confirmed"));
        assertTrue(calls.indexOf("input") < calls.indexOf("environment"));
        assertTrue(calls.indexOf("environment-confirmed") < calls.indexOf("environment"));
        assertArrayEquals(new char[master.length], master);
        try (var snapshots = Files.list(directory.resolve("work/source-snapshots"))) { assertEquals(0, snapshots.count()); }
    }

    @Test void cancellingMissingInputsStopsBeforeServerMutation() throws Exception {
        Path root = node(directory.resolve("source"), "demo");
        assertThrows(CancellationException.class, () -> useCase().deploy(
                new AutomaticDeploymentRequest(Optional.of(root), Optional.empty(), profile, Map.of()),
                new char[0], interaction(true), ignored -> true, ignored -> { }));
        assertFalse(calls.contains("environment")); assertFalse(calls.contains("deploy"));
    }

    @Test void decliningSystemPackagesStopsBeforePreparationAndDeployment() throws Exception {
        declineEnvironment = true;
        Path root = node(directory.resolve("source"), "demo");
        char[] master = "test-only".toCharArray();
        assertThrows(CancellationException.class, () -> useCase().deploy(
                new AutomaticDeploymentRequest(Optional.of(root), Optional.empty(), profile, Map.of()),
                master, interaction(false), ignored -> true, ignored -> { }));
        assertTrue(calls.contains("environment-declined"));
        assertFalse(calls.contains("environment"));
        assertFalse(calls.contains("deploy"));
        assertArrayEquals(new char[master.length], master);
    }

    @Test void cancellingDatabaseInputsStopsBeforeServerChecksAndEnvironmentChanges() throws Exception {
        Path root = node(directory.resolve("source"), "demo");
        Files.writeString(root.resolve("windowstolinux-db.properties"), "db=main\ndb.main.engine=POSTGRESQL\n");
        assertThrows(CancellationException.class, () -> useCase().deploy(new AutomaticDeploymentRequest(Optional.of(root), Optional.empty(),
                profile, Map.of("port", "18080")), new char[0], interaction(true), ignored -> true, ignored -> { }));
        assertFalse(calls.contains("verify")); assertFalse(calls.contains("environment"));
    }

    @Test void repairsTimeoutAndConfigurationInTheSameTaskBeforeServerChecks() throws Exception {
        Path root = node(directory.resolve("source"), "demo");
        var original = interaction(false);
        var correcting = new AutomaticDeploymentInteraction() {
            private int attempts;
            @Override public Optional<Map<String, String>> requestInputs(List<DeploymentInputField> fields) {
                assertTrue(++attempts <= 2, "invalid input must be repairable without repeating an unchanged form");
                assertFalse(calls.contains("verify"));
                var answers = new LinkedHashMap<>(original.requestInputs(fields).orElseThrow());
                if (fields.stream().anyMatch(field -> field.id().endsWith("/timeout"))) {
                    assertTrue(fields.stream().anyMatch(field -> field.id().endsWith("/configuration")));
                    answers.put("app/timeout", "15"); answers.put("app/configuration", ""); calls.add("corrected");
                }
                return Optional.of(answers);
            }
            @Override public boolean confirm(String key, Map<String, ?> details) { return original.confirm(key, details); }
            @Override public char[] requestSecret(String key) { return original.requestSecret(key); }
        };
        var result = useCase().deploy(new AutomaticDeploymentRequest(Optional.of(root), Optional.empty(), profile,
                Map.of("timeout", "invalid", "configuration", "missing-equals")), new char[0], correcting, ignored -> true, ignored -> { });
        assertEquals(DeploymentStatus.SUCCEEDED, result.status());
        assertEquals(1, Collections.frequency(calls, "corrected"));
        assertEquals(1, Collections.frequency(calls, "deploy"));
    }

    @Test void cancellingHealthOwnerSelectionStopsBeforeDatabaseOrEnvironmentPreparation() throws Exception {
        Path root = Files.createDirectories(directory.resolve("shop")); node(root.resolve("api"), "api"); node(root.resolve("web"), "web");
        var original = interaction(false);
        var cancelHealth = new AutomaticDeploymentInteraction() {
            @Override public Optional<Map<String, String>> requestInputs(List<DeploymentInputField> fields) {
                if (fields.stream().anyMatch(field -> field.id().equals("application/healthOwner"))) return Optional.empty();
                return original.requestInputs(fields);
            }
            @Override public boolean confirm(String key, Map<String, ?> details) { return original.confirm(key, details); }
            @Override public char[] requestSecret(String key) { throw new AssertionError("no secret is needed"); }
        };
        assertThrows(CancellationException.class, () -> useCase().deploy(new AutomaticDeploymentRequest(Optional.of(root), Optional.empty(), profile,
                Map.of("api/port", "18081", "web/port", "18082")), new char[0], cancelHealth, ignored -> true, ignored -> { }));
        assertFalse(calls.contains("environment")); assertFalse(calls.contains("multi-review"));
    }

    @Test void snapshotPreservesRepositoryIdentityAndCannotUseAnEscapingName() throws Exception {
        Path root = node(directory.resolve("checkout"), "demo");
        try (var snapshot = gold.debug.windowstolinux.shared.source.snapshot.SourceDirectorySnapshot.create(root, directory.resolve("snapshots"), "my-repository")) {
            assertEquals("my-repository", snapshot.directory().getFileName().toString());
            assertTrue(Files.isRegularFile(snapshot.directory().resolve("package.json")));
        }
        assertThrows(java.io.IOException.class, () -> gold.debug.windowstolinux.shared.source.snapshot.SourceDirectorySnapshot
                .create(root, directory.resolve("snapshots"), "../escape"));
    }

    @Test void wholeApplicationSuccessSummarizesEveryComponentAndFailureExposesNoHandoff() throws Exception {
        Path root = Files.createDirectories(directory.resolve("shop")); node(root.resolve("api"),"api"); node(root.resolve("web"),"web");
        completeMultiple = true;
        for (var state : List.of(DeploymentStatus.SUCCEEDED,DeploymentStatus.FAILED_ROLLED_BACK)) {
            terminal = state;
            var result = useCase().deploy(new AutomaticDeploymentRequest(Optional.of(root),Optional.empty(),profile,
                    Map.of("api/port","18081","web/port","18082")),new char[0],interaction(false),ignored -> true,ignored -> { });
            assertEquals(state,result.status());
            assertEquals(state == DeploymentStatus.SUCCEEDED ? Set.of("api","web") : Set.of(),result.handoffs().keySet());
        }
    }

    @Test void discoversAndValidatesWholeGraphBeforeEnvironmentPreparation() throws Exception {
        Path root = Files.createDirectories(directory.resolve("shop"));
        node(root.resolve("api"), "api"); node(root.resolve("web"), "web");
        assertThrows(ReviewReached.class, () -> useCase().deploy(new AutomaticDeploymentRequest(Optional.of(root), Optional.empty(), profile,
                Map.of("api/port", "18081", "web/port", "18082")), new char[0], interaction(false), ignored -> true, ignored -> { }));
        assertTrue(calls.contains("multi-review")); assertFalse(calls.contains("environment"));
    }

    @Test void portConflictCannotReachAnyEnvironmentChange() throws Exception {
        Path root = Files.createDirectories(directory.resolve("shop")); node(root.resolve("api"), "api"); node(root.resolve("web"), "web");
        assertThrows(IllegalArgumentException.class, () -> useCase().deploy(new AutomaticDeploymentRequest(Optional.of(root), Optional.empty(), profile,
                Map.of("port", "18081")), new char[0], interaction(false), ignored -> true, ignored -> { }));
        assertFalse(calls.contains("multi-review")); assertFalse(calls.contains("environment"));
    }

    private AutomaticDeploymentInteraction interaction(boolean cancel) {
        return new AutomaticDeploymentInteraction() {
            @Override public Optional<Map<String, String>> requestInputs(List<DeploymentInputField> fields) {
                calls.add("input"); if (cancel) return Optional.empty();
                Map<String, String> answers = new LinkedHashMap<>();
                fields.forEach(field -> answers.put(field.id(), field.id().endsWith("/port") ? "18080"
                        : field.id().endsWith("/dependencies") ? (field.id().startsWith("web/") ? "api" : "")
                        : field.choices().isEmpty() ? field.value() : field.choices().getFirst()));
                return Optional.of(answers);
            }
            @Override public boolean confirm(String key, Map<String, ?> details) {
                if (key.equals("environment.confirm")) {
                    assertEquals(profile.id(), details.get("serverId"));
                    assertEquals(profile.host(), details.get("host"));
                    calls.add(declineEnvironment ? "environment-declined" : "environment-confirmed");
                    return !declineEnvironment;
                }
                return true;
            }
            @Override public char[] requestSecret(String key) { throw new AssertionError("no secret is needed"); }
        };
    }

    @SuppressWarnings("unchecked")
    private AutomaticDeploymentUseCase useCase() {
        var facade = (AutomaticDeploymentApplicationFacade) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{AutomaticDeploymentApplicationFacade.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "invokeAiRole" -> Optional.empty();
                    case "completeAutomaticDatabaseInputs" -> new AutomaticDatabaseUseCase(null, null, null, null, null,
                            (gold.debug.windowstolinux.app.service.contract.AiApplicationFacade) proxy).completeInputs((Path) args[0], (String) args[1],
                            (gold.debug.windowstolinux.shared.analyze.ecosystem.db.DatabaseProjectInspector.Assessment) args[2],
                            (char[]) args[3], (AutomaticDeploymentInteraction) args[4]);
                    case "verifyServer" -> { calls.add("verify"); yield null; }
                    case "findTrustedServer" -> Optional.of(server);
                    case "prepareEnvironmentWithStoredPassword" -> { calls.add("environment"); yield null; }
                    case "saveDeploymentConfigurationSnapshot", "planDeployment" -> null;
                    case "createReviewedDeploymentRequest" -> {
                        var source = (ReviewedSourcePreparation) args[0];
                        assertNotEquals(directory.resolve("source"), source.assessment().facts().orElseThrow().sourceRoot());
                        assertTrue(Files.exists(source.archive().orElseThrow().localArchive()));
                        var config = (ConfigurationSnapshot) args[2];
                        assertEquals(1, config.entries().size()); assertEquals("PORT", config.entries().getFirst().key());
                        yield new ReviewedDeploymentRequest(server, source.assessment().facts().orElseThrow(), source.sourceRevision().orElseThrow(),
                                source.archive().orElseThrow(), config, List.of(), Optional.of(List.of()), (DeploymentRuntimeSpecification) args[5],
                                (Optional<UserAccessUrl>) args[6], (BuildLimitConfiguration) args[7], new DeploymentApproval(config.applicationId(),
                                source.archive().orElseThrow().contentSha256(), server.id(), false, Instant.now()), true, true);
                    }
                    case "createReviewedMultiComponentApplication" -> {
                        PreparedMultiComponentSource source = (PreparedMultiComponentSource) args[0];
                        assertEquals(2, source.components().size());
                        assertTrue(source.assessment().components().stream().allMatch(component -> !component.artifactPaths().isEmpty()));
                        assertEquals(Set.of("api"), source.assessment().components().stream().filter(component -> component.componentId().equals("web"))
                                .findFirst().orElseThrow().dependencies());
                        calls.add("multi-review");
                        if (!completeMultiple) throw new ReviewReached();
                        try (var persistence = gold.debug.windowstolinux.app.db.DesktopPersistence.open(directory.resolve("review"))) {
                            var desktop = new gold.debug.windowstolinux.app.service.DesktopApplicationFacade(persistence,directory.resolve("review-work"),
                                    (endpoint,credential,verifier) -> { throw new AssertionError("review must not connect"); });
                            yield desktop.createReviewedMultiComponentApplication(source,server,
                                    (List<gold.debug.windowstolinux.app.service.contract.definition.MultiComponentReviewInput>)args[2],(ApplicationHealthGate)args[3]);
                        }
                    }
                    case "deployAutomaticallyReviewed" -> {
                        calls.add("deploy");
                        if (args[0] instanceof gold.debug.windowstolinux.app.service.deployment.multi.ReviewedMultiComponentApplication multi) {
                            yield new gold.debug.windowstolinux.shared.deploy.contract.result.deployment.MultiComponentDeploymentResult(terminal,List.of(),
                                    multi.components().stream().map(component -> new gold.debug.windowstolinux.shared.deploy.contract.result.deployment.ComponentDeploymentResult(
                                            component.componentId(),terminal == DeploymentStatus.SUCCEEDED
                                            ? gold.debug.windowstolinux.shared.deploy.contract.result.deployment.ComponentTransactionState.SUCCEEDED
                                            : gold.debug.windowstolinux.shared.deploy.contract.result.deployment.ComponentTransactionState.RESTORED,List.of(),Optional.empty())).toList(),
                                    terminal == DeploymentStatus.SUCCEEDED ? Optional.of("a".repeat(64)) : Optional.empty());
                        }
                        yield new DeploymentOutcome(new DeploymentResult(DeploymentStatus.SUCCEEDED, List.of(), Optional.empty(), Optional.of("a".repeat(64))),
                                Optional.of(new DeploymentHandoff.SystemdStartCommand("demo", "windowstolinux-demo.service", "a".repeat(64))));
                    }
                    default -> throw new AssertionError("unexpected method: " + method.getName());
                });
        return new AutomaticDeploymentUseCase(facade, new SourcePreparationUseCase(new DeploymentAnalysisCoordinator(),
                new WindowsSourcePreparer(directory.resolve("work"))), new ServerOperationLockRegistry());
    }

    private static Path node(Path root, String name) throws Exception {
        Files.createDirectories(root);
        Files.writeString(root.resolve("package.json"), "{\"name\":\"" + name + "\",\"engines\":{\"node\":\"22\"},\"scripts\":{\"build\":\"build\",\"start\":\"start\"}}");
        Files.writeString(root.resolve("package-lock.json"), "{\"name\":\"" + name + "\",\"lockfileVersion\":3,\"packages\":{\"\":{\"name\":\"" + name + "\"}}}");
        return root;
    }
    private static final class ReviewReached extends RuntimeException { }
}
