package gold.debug.windowstolinux.app.service.deployment.automatic;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CancellationException;

import gold.debug.windowstolinux.app.service.contract.AutomaticDeploymentApplicationFacade;
import gold.debug.windowstolinux.app.service.contract.definition.*;
import gold.debug.windowstolinux.app.service.deployment.single.*;
import gold.debug.windowstolinux.app.service.lock.ServerOperationLockRegistry;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.source.*;
import gold.debug.windowstolinux.app.windows.workspace.WindowsSourcePreparer;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationSnapshot;
import gold.debug.windowstolinux.shared.deploy.contract.*;
import gold.debug.windowstolinux.shared.deploy.contract.result.deployment.DeploymentResult;
import gold.debug.windowstolinux.shared.model.deployment.*;
import gold.debug.windowstolinux.shared.model.health.UserAccessUrl;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;
import gold.debug.windowstolinux.shared.model.server.ServerIdentity;
import gold.debug.windowstolinux.shared.standard.analyze.core.DeploymentAnalysisCoordinator;
import gold.debug.windowstolinux.shared.standard.deploy.contract.DeploymentApproval;
import gold.debug.windowstolinux.shared.standard.deploy.contract.ReviewedDeploymentRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@org.junit.jupiter.api.Timeout(30)
class AutomaticDeploymentUseCaseTest {
    @TempDir
    Path directory;

    private final List<String> calls = new ArrayList<>();

    private boolean completeMultiple;

    private gold.debug.windowstolinux.shared.standard.deploy.assistance.AssistedAnalysisModelPort analysisModel;

    private int failuresRemaining;

    private int preflightCalls;

    private final List<String> reviewedVersions = new ArrayList<>();

    private AssistedDeploymentBoundary agentBoundary;

    private boolean declineEnvironment;

    private DeploymentStatus terminal = DeploymentStatus.SUCCEEDED;

    private final ServerIdentity server = new ServerIdentity("test-server", "192.0.2.1", 22, "SHA256:test-fingerprint");

    private final ServerProfile profile = new ServerProfile("test-server", "192.0.2.1", 22, "test", "ssh-test",
            CredentialStorageMode.WINDOWS_CREDENTIAL_MANAGER);

    @Test
    void completesOneLocalClickWithGroupedInputAndRealSnapshotWithoutRequiringConfigurationText() throws Exception {
        Path root = node(directory.resolve("source"), "demo");
        char[] master = "temporary-test-value".toCharArray();
        var result = useCase().deploy(
                new AutomaticDeploymentRequest(Optional.of(root), Optional.empty(), profile, Map.of()), master,
                interaction(false), ignored -> true, ignored -> {
                });
        assertEquals(DeploymentStatus.SUCCEEDED, result.status());
        assertEquals(1, result.handoffs().size());
        assertEquals(1, Collections.frequency(calls, "environment"));
        assertEquals(1, Collections.frequency(calls, "deploy"));
        assertEquals(1, Collections.frequency(calls, "environment-confirmed"));
        assertTrue(calls.indexOf("input") < calls.indexOf("environment"));
        assertTrue(calls.indexOf("environment-confirmed") < calls.indexOf("environment"));
        assertArrayEquals(new char[master.length], master);
        try (var snapshots = Files.list(directory.resolve("work/source-snapshots"))) {
            assertEquals(0, snapshots.count());
        }
    }

    @Test
    void cancellingMissingInputsStopsBeforeServerMutation() throws Exception {
        Path root = node(directory.resolve("source"), "demo");
        assertThrows(CancellationException.class,
                () -> useCase().deploy(
                        new AutomaticDeploymentRequest(Optional.of(root), Optional.empty(), profile, Map.of()),
                        new char[0], interaction(true), ignored -> true, ignored -> {
                        }));
        assertFalse(calls.contains("environment"));
        assertFalse(calls.contains("deploy"));
    }

    @Test
    void noPortBackgroundApplicationCompletesWithoutInventingNetworkConfiguration() throws Exception {
        Path root = node(directory.resolve("background"), "background");
        Files.delete(root.resolve("windowstolinux-application.properties"));
        var result = useCase().deploy(
                new AutomaticDeploymentRequest(Optional.of(root), Optional.empty(), profile, Map.of()), new char[0],
                interaction(false), ignored -> true, ignored -> {
                });
        assertEquals(DeploymentStatus.SUCCEEDED, result.status());
        assertEquals(1, Collections.frequency(calls, "input"));
        assertTrue(calls.contains("deploy"));
    }

    @Test
    void decliningSystemPackagesStopsBeforePreparationAndDeployment() throws Exception {
        declineEnvironment = true;
        Path root = node(directory.resolve("source"), "demo");
        char[] master = "test-only".toCharArray();
        assertThrows(CancellationException.class,
                () -> useCase().deploy(
                        new AutomaticDeploymentRequest(Optional.of(root), Optional.empty(), profile, Map.of()), master,
                        interaction(false), ignored -> true, ignored -> {
                        }));
        assertTrue(calls.contains("environment-declined"));
        assertFalse(calls.contains("environment"));
        assertFalse(calls.contains("deploy"));
        assertArrayEquals(new char[master.length], master);
    }

    @Test
    void cancellingDatabaseInputsStopsBeforeServerChecksAndEnvironmentChanges() throws Exception {
        Path root = node(directory.resolve("source"), "demo");
        Files.writeString(root.resolve("windowstolinux-db.properties"), "db=main\ndb.main.engine=POSTGRESQL\n");
        assertThrows(CancellationException.class, () -> useCase().deploy(
                new AutomaticDeploymentRequest(Optional.of(root), Optional.empty(), profile, Map.of("port", "18080")),
                new char[0], interaction(true), ignored -> true, ignored -> {
                }));
        assertFalse(calls.contains("verify"));
        assertFalse(calls.contains("environment"));
    }

    @Test
    void repairsTimeoutAndConfigurationInTheSameTaskBeforeServerChecks() throws Exception {
        Path root = node(directory.resolve("source"), "demo");
        var original = interaction(false);
        var correcting = new AutomaticDeploymentInteraction() {
            private int attempts;
            @Override
            public Optional<Map<String, String>> requestInputs(List<DeploymentInputField> fields) {
                assertTrue(++attempts <= 2, "invalid input must be repairable without repeating an unchanged form");
                assertFalse(calls.contains("verify"));
                var answers = new LinkedHashMap<>(original.requestInputs(fields).orElseThrow());
                if (fields.stream().anyMatch(field -> field.id().endsWith("/timeout"))) {
                    assertTrue(fields.stream().anyMatch(field -> field.id().endsWith("/configuration")));
                    answers.put("app/timeout", "15");
                    answers.put("app/configuration", "");
                    calls.add("corrected");
                }
                return Optional.of(answers);
            }

            @Override
            public boolean confirm(String key, Map<String, ?> details) {
                return original.confirm(key, details);
            }

            @Override
            public char[] requestSecret(String key) {
                return original.requestSecret(key);
            }
        };
        var result = useCase().deploy(
                new AutomaticDeploymentRequest(Optional.of(root), Optional.empty(), profile,
                        Map.of("timeout", "invalid", "configuration", "missing-equals")),
                new char[0], correcting, ignored -> true, ignored -> {
                });
        assertEquals(DeploymentStatus.SUCCEEDED, result.status());
        assertEquals(1, Collections.frequency(calls, "corrected"));
        assertEquals(1, Collections.frequency(calls, "deploy"));
    }

    @Test
    void cancellingHealthOwnerSelectionStopsBeforeDatabaseOrEnvironmentPreparation() throws Exception {
        Path root = Files.createDirectories(directory.resolve("shop"));
        node(root.resolve("api"), "api");
        node(root.resolve("web"), "web");
        var original = interaction(false);
        var cancelHealth = new AutomaticDeploymentInteraction() {
            @Override
            public Optional<Map<String, String>> requestInputs(List<DeploymentInputField> fields) {
                if (fields.stream().anyMatch(field -> field.id().equals("application/healthOwner")))
                    return Optional.empty();
                return original.requestInputs(fields);
            }

            @Override
            public boolean confirm(String key, Map<String, ?> details) {
                return original.confirm(key, details);
            }

            @Override
            public char[] requestSecret(String key) {
                throw new AssertionError("no secret is needed");
            }
        };
        assertThrows(CancellationException.class,
                () -> useCase().deploy(
                        new AutomaticDeploymentRequest(Optional.of(root), Optional.empty(), profile,
                                Map.of("api/port", "18081", "web/port", "18082")),
                        new char[0], cancelHealth, ignored -> true, ignored -> {
                        }));
        assertFalse(calls.contains("environment"));
        assertFalse(calls.contains("multi-review"));
    }

    @Test
    void snapshotPreservesRepositoryIdentityAndCannotUseAnEscapingName() throws Exception {
        Path root = node(directory.resolve("checkout"), "demo");
        try (var snapshot = gold.debug.windowstolinux.shared.source.snapshot.SourceDirectorySnapshot.create(root,
                directory.resolve("snapshots"), "my-repository")) {
            assertEquals("my-repository", snapshot.directory().getFileName().toString());
            assertTrue(Files.isRegularFile(snapshot.directory().resolve("package.json")));
        }
        assertThrows(java.io.IOException.class,
                () -> gold.debug.windowstolinux.shared.source.snapshot.SourceDirectorySnapshot.create(root,
                        directory.resolve("snapshots"), "../escape"));
    }

    @Test
    void wholeApplicationSuccessSummarizesEveryComponentAndFailureExposesNoHandoff() throws Exception {
        Path root = Files.createDirectories(directory.resolve("shop"));
        node(root.resolve("api"), "api");
        node(root.resolve("web"), "web");
        completeMultiple = true;
        for (var state : List.of(DeploymentStatus.SUCCEEDED, DeploymentStatus.FAILED_ROLLED_BACK)) {
            terminal = state;
            var result = useCase().deploy(
                    new AutomaticDeploymentRequest(Optional.of(root), Optional.empty(), profile,
                            Map.of("api/port", "18081", "web/port", "18082")),
                    new char[0], interaction(false), ignored -> true, ignored -> {
                    });
            assertEquals(state, result.status());
            assertEquals(state == DeploymentStatus.SUCCEEDED ? Set.of("api", "web") : Set.of(),
                    result.handoffs().keySet());
        }
    }

    @Test
    void discoversAndValidatesWholeGraphBeforeEnvironmentPreparation() throws Exception {
        Path root = Files.createDirectories(directory.resolve("shop"));
        node(root.resolve("api"), "api");
        node(root.resolve("web"), "web");
        assertThrows(ReviewReached.class,
                () -> useCase().deploy(
                        new AutomaticDeploymentRequest(Optional.of(root), Optional.empty(), profile,
                                Map.of("api/port", "18081", "web/port", "18082")),
                        new char[0], interaction(false), ignored -> true, ignored -> {
                        }));
        assertTrue(calls.contains("multi-review"));
        assertFalse(calls.contains("environment"));
    }

    @Test
    void portConflictCannotReachAnyEnvironmentChange() throws Exception {
        Path root = Files.createDirectories(directory.resolve("shop"));
        node(root.resolve("api"), "api");
        node(root.resolve("web"), "web");
        assertThrows(IllegalArgumentException.class, () -> useCase().deploy(
                new AutomaticDeploymentRequest(Optional.of(root), Optional.empty(), profile, Map.of("port", "18081")),
                new char[0], interaction(false), ignored -> true, ignored -> {
                }));
        assertFalse(calls.contains("multi-review"));
        assertFalse(calls.contains("environment"));
    }

    @Test
    void assistanceRunsStandardStepsWithoutDeploymentModelDecisions() throws Exception {
        var reviews = new ArrayList<gold.debug.windowstolinux.shared.model.agent.AgentToolType>();
        var control = new gold.debug.windowstolinux.shared.deploy.task.AgentTaskControl(state -> {
        });
        var models = new gold.debug.windowstolinux.shared.standard.deploy.assistance.AssistedActionModelPort() {
            public gold.debug.windowstolinux.shared.model.agent.AgentDecision decide(String goal,
                    java.util.List<gold.debug.windowstolinux.shared.model.agent.AgentAction> actions,
                    java.util.List<String> history, int budget) {
                throw new AssertionError("normal workflow must not ask the deployment model to choose steps");
            }

            public gold.debug.windowstolinux.shared.model.agent.AgentReview review(String goal,
                    gold.debug.windowstolinux.shared.model.agent.AgentAction action,
                    gold.debug.windowstolinux.shared.model.agent.AgentRiskLevel risk) {
                reviews.add(action.tool());
                calls.add("approved:" + action.tool());
                return new gold.debug.windowstolinux.shared.model.agent.AgentReview(
                        gold.debug.windowstolinux.shared.model.agent.AgentReviewDecision.ALLOW, risk, action.binding(),
                        "validated exact operation", java.util.List.copyOf(action.evidence().keySet()));
            }

            public boolean advanceDeployment() {
                throw new AssertionError("no model failure");
            }
        };
        agentBoundary = new AssistedDeploymentBoundary("task", profile, AgentApprovalMode.FULL_CONTROL, models, control,
                interaction(false), event -> {
                }, () -> true);
        Path root = node(directory.resolve("source"), "demo");
        var result = useCase().deploy(
                new AutomaticDeploymentRequest(Optional.of(root), Optional.empty(), profile, Map.of())
                        .withAutomation(DeploymentAutomationMode.ASSISTED, AgentApprovalMode.FULL_CONTROL),
                new char[0], interaction(false), value -> true, value -> {
                });
        assertEquals(DeploymentStatus.SUCCEEDED, result.status());
        assertEquals(List.of(gold.debug.windowstolinux.shared.model.agent.AgentToolType.ANALYZE_SOURCE,
                gold.debug.windowstolinux.shared.model.agent.AgentToolType.DEPLOY_TRANSACTION), reviews);
        assertTrue(calls.indexOf("verify") < calls.indexOf("environment"));
        assertTrue(calls.indexOf("approved:DEPLOY_TRANSACTION") < calls.indexOf("deploy"));
        assertFalse(calls.contains("environment-confirmed"));
        assertEquals(1, Collections.frequency(calls, "deploy"));
    }

    @Test
    void agentCannotEnterTheDeterministicPathWithoutItsApprovalBoundary() throws Exception {
        Path root = node(directory.resolve("source"), "demo");
        assertThrows(SecurityException.class,
                () -> useCase().deploy(
                        new AutomaticDeploymentRequest(Optional.of(root), Optional.empty(), profile, Map.of())
                                .withAutomation(DeploymentAutomationMode.AGENT, AgentApprovalMode.FULL_CONTROL),
                        new char[0], interaction(false), value -> true, value -> {
                        }));
        assertTrue(calls.isEmpty());
    }

    @Test
    void knownFailureIsReplannedOnlyAfterEvidenceBackedCorrection() throws Exception {
        failuresRemaining = 1;
        analysisModel = correctingModel("app/version");
        Path root = node(directory.resolve("source"), "demo");
        Files.writeString(root.resolve("README.md"), "Supported Node versions: 22, 20, 18.\n");
        var result = useCase().deploy(assistedRequest(root, Map.of()), new char[0], interaction(false), ignored -> true,
                ignored -> {
                });
        assertEquals(DeploymentStatus.SUCCEEDED, result.status());
        assertEquals(2, Collections.frequency(calls, "deploy"));
        assertEquals(1, Collections.frequency(calls, "environment"));
        assertEquals(2, preflightCalls);
        assertEquals(2, new HashSet<>(reviewedVersions).size());
    }

    @Test
    void correctiveDeploymentRetriesStopAfterTwoAttempts() throws Exception {
        failuresRemaining = 10;
        analysisModel = correctingModel("app/version");
        Path root = node(directory.resolve("source"), "demo");
        Files.writeString(root.resolve("README.md"), "Supported Node versions: 22, 20, 18.\n");
        var result = useCase().deploy(assistedRequest(root, Map.of()), new char[0], interaction(false), ignored -> true,
                ignored -> {
                });
        assertEquals(DeploymentStatus.FAILED_ROLLED_BACK, result.status());
        assertEquals(3, Collections.frequency(calls, "deploy"));
        assertEquals(1, Collections.frequency(calls, "environment"));
    }

    @Test
    void explicitUserVersionIsNotOverwrittenAndUnchangedCorrectionDoesNotRetry() throws Exception {
        failuresRemaining = 10;
        analysisModel = correctingModel("app/version");
        Path root = node(directory.resolve("source"), "demo");
        Files.writeString(root.resolve("README.md"), "Supported Node versions: 22, 20, 18.\n");
        var result = useCase().deploy(assistedRequest(root, Map.of("version", "22")), new char[0], interaction(false),
                ignored -> true, ignored -> {
                });
        assertEquals(DeploymentStatus.FAILED_ROLLED_BACK, result.status());
        assertEquals(1, Collections.frequency(calls, "deploy"));
        assertTrue(Collections.frequency(calls, "input") >= 2);
    }

    @Test
    void unknownDeploymentResultNeverEntersCorrectionOrReplay() throws Exception {
        terminal = DeploymentStatus.MANUAL_RECOVERY_REQUIRED;
        analysisModel = (context, observations, remaining) -> {
            assertFalse(context.toString().contains("failure/"), "unknown results cannot enter recovery");
            return new gold.debug.windowstolinux.shared.standard.deploy.assistance.AssistedAnalysisDecision("ADVISE",
                    (String) context.get("sourceRevision"), "", 0, 1,
                    new AssistedDeploymentAdvice("Human input required", List.of(), List.of()));
        };
        Path root = node(directory.resolve("source"), "demo");
        var result = useCase().deploy(assistedRequest(root, Map.of()), new char[0], interaction(false), ignored -> true,
                ignored -> {
                });
        assertEquals(DeploymentStatus.MANUAL_RECOVERY_REQUIRED, result.status());
        assertEquals(1, Collections.frequency(calls, "deploy"));
    }

    @Test
    void multipleComponentsUseTheSameCorrectiveLoopAndWholeApplicationGate() throws Exception {
        completeMultiple = true;
        failuresRemaining = 1;
        analysisModel = correctingModel("api/version");
        Path root = Files.createDirectories(directory.resolve("shop"));
        node(root.resolve("api"), "api");
        node(root.resolve("web"), "web");
        Files.writeString(root.resolve("README.md"), "API supports Node 22 and 20; web depends on API.\n");
        var result = useCase().deploy(assistedRequest(root, Map.of("api/port", "18081", "web/port", "18082")),
                new char[0], interaction(false), ignored -> true, ignored -> {
                });
        assertEquals(DeploymentStatus.SUCCEEDED, result.status());
        assertEquals(Set.of("api", "web"), result.handoffs().keySet());
        assertEquals(2, Collections.frequency(calls, "multi-review"));
        assertEquals(2, Collections.frequency(calls, "deploy"));
        assertEquals(1, Collections.frequency(calls, "environment"));
    }

    @Test
    void incidentalServerChangesCannotAuthorizeDeploymentReplay() {
        var before = capabilities(false, 0, 1000, "before");
        assertFalse(AutomaticDeploymentUseCase.environmentImproved(null, before));
        assertFalse(AutomaticDeploymentUseCase.environmentImproved(before, capabilities(false, 0, 2000, "after")));
        assertTrue(AutomaticDeploymentUseCase.environmentImproved(before, capabilities(true, 0, 1000, "after")));
        assertTrue(AutomaticDeploymentUseCase.environmentImproved(before, capabilities(false, 10, 1000, "after")));
        assertFalse(AutomaticDeploymentUseCase.environmentImproved(capabilities(true, 10, 1000, "before"), before));
    }

    private gold.debug.windowstolinux.shared.model.capability.ServerCapabilityFacts capabilities(boolean java,
            int helper, long bytes, String evidence) {
        return new gold.debug.windowstolinux.shared.model.capability.ServerCapabilityFacts("Ubuntu 24.04", "x86_64",
                true, java, true, true, true, true, true, true, helper, bytes, evidence);
    }

    private AutomaticDeploymentRequest assistedRequest(Path root, Map<String, String> overrides) {
        return new AutomaticDeploymentRequest(Optional.of(root), Optional.empty(), profile, overrides)
                .withAutomation(DeploymentAutomationMode.ASSISTED, AgentApprovalMode.FULL_CONTROL);
    }

    private gold.debug.windowstolinux.shared.standard.deploy.assistance.AssistedAnalysisModelPort correctingModel(
            String field) {
        return (context, observations, remaining) -> {
            String revision = (String) context.get("sourceRevision");
            if (!context.toString().contains("failure/"))
                return new gold.debug.windowstolinux.shared.standard.deploy.assistance.AssistedAnalysisDecision(
                        "ADVISE", revision, "", 0, 1,
                        new AssistedDeploymentAdvice("Human input required", List.of(), List.of()));
            if (observations.isEmpty())
                return new gold.debug.windowstolinux.shared.standard.deploy.assistance.AssistedAnalysisDecision("READ",
                        revision, "README.md", 0, 20, new AssistedDeploymentAdvice("", List.of(), List.of()));
            String version = Collections.frequency(calls, "deploy") == 1 ? "20" : "18";
            return new gold.debug.windowstolinux.shared.standard.deploy.assistance.AssistedAnalysisDecision("ADVISE",
                    revision, "", 0, 1,
                    new AssistedDeploymentAdvice("Use a documented compatible runtime",
                            List.of(new AssistedDeploymentAdvice.Candidate(field, version, List.of("source/0"))),
                            List.of()));
        };
    }

    private AutomaticDeploymentInteraction interaction(boolean cancel) {
        return new AutomaticDeploymentInteraction() {
            @Override
            public Optional<Map<String, String>> requestInputs(List<DeploymentInputField> fields) {
                calls.add("input");
                if (cancel)
                    return Optional.empty();
                Map<String, String> answers = new LinkedHashMap<>();
                fields.forEach(field -> answers.put(field.id(),
                        field.id().endsWith("/port")
                                ? "18080"
                                : field.id().endsWith("/dependencies")
                                        ? (field.id().startsWith("web/") ? "api" : "")
                                        : field.choices().isEmpty() || field.choices().contains(field.value())
                                                ? field.value()
                                                : field.choices().getFirst()));
                return Optional.of(answers);
            }

            @Override
            public boolean confirm(String key, Map<String, ?> details) {
                if (key.equals("environment.confirm")) {
                    assertEquals(profile.id(), details.get("serverId"));
                    assertEquals(profile.host(), details.get("host"));
                    calls.add(declineEnvironment ? "environment-declined" : "environment-confirmed");
                    return !declineEnvironment;
                }
                return true;
            }

            @Override
            public char[] requestSecret(String key) {
                throw new AssertionError("no secret is needed");
            }
        };
    }

    @SuppressWarnings("unchecked")
    private AutomaticDeploymentUseCase useCase() {
        var facade = (AutomaticDeploymentApplicationFacade) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{AutomaticDeploymentApplicationFacade.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "invokeAiRole" -> Optional.empty();
                    case "completeAutomaticDatabaseInputs" -> new AutomaticDatabaseUseCase(null, null, null, null, null,
                            (gold.debug.windowstolinux.app.service.contract.AiApplicationFacade) proxy)
                            .completeInputs((Path) args[0], (String) args[1],
                                    (gold.debug.windowstolinux.shared.standard.analyze.ecosystem.db.DatabaseProjectInspector.Assessment) args[2],
                                    (char[]) args[3], (AutomaticDeploymentInteraction) args[4]);
                    case "verifyServer" -> {
                        calls.add("verify");
                        yield null;
                    }
                    case "findTrustedServer" -> Optional.of(server);
                    case "prepareEnvironmentWithStoredPassword" -> {
                        calls.add("environment");
                        yield null;
                    }
                    case "saveDeploymentConfigurationSnapshot", "planDeployment" -> null;
                    case "createReviewedDeploymentRequest" -> {
                        var source = (ReviewedSourcePreparation) args[0];
                        assertNotEquals(directory.resolve("source"),
                                source.assessment().facts().orElseThrow().sourceRoot());
                        assertTrue(Files.exists(source.archive().orElseThrow().localArchive()));
                        var config = (ConfigurationSnapshot) args[2];
                        var runtime = (DeploymentRuntimeSpecification) args[5];
                        reviewedVersions.add(runtime.toString());
                        if (runtime.healthCheck().portNumber().isPresent()) {
                            assertEquals(1, config.entries().size());
                            assertEquals("PORT", config.entries().getFirst().key());
                        } else
                            assertTrue(config.entries().isEmpty());
                        yield new ReviewedDeploymentRequest(server, source.assessment().facts().orElseThrow(),
                                source.sourceRevision().orElseThrow(), source.archive().orElseThrow(), config,
                                List.of(), Optional.of(List.of()), (DeploymentRuntimeSpecification) args[5],
                                (Optional<UserAccessUrl>) args[6], (BuildLimitConfiguration) args[7],
                                new DeploymentApproval(config.applicationId(),
                                        source.archive().orElseThrow().contentSha256(), server.id(), false,
                                        Instant.now()),
                                true, true);
                    }
                    case "createReviewedMultiComponentApplication" -> {
                        PreparedMultiComponentSource source = (PreparedMultiComponentSource) args[0];
                        assertEquals(2, source.components().size());
                        assertTrue(source.assessment().components().stream()
                                .allMatch(component -> !component.artifactPaths().isEmpty()));
                        assertEquals(Set.of("api"),
                                source.assessment().components().stream()
                                        .filter(component -> component.componentId().equals("web")).findFirst()
                                        .orElseThrow().dependencies());
                        calls.add("multi-review");
                        if (!completeMultiple)
                            throw new ReviewReached();
                        try (var persistence = gold.debug.windowstolinux.app.db.DesktopPersistence
                                .open(directory.resolve("review"))) {
                            var desktop = new gold.debug.windowstolinux.app.service.DesktopApplicationFacade(
                                    persistence, directory.resolve("review-work"), (endpoint, credential, verifier) -> {
                                        throw new AssertionError("review must not connect");
                                    });
                            yield desktop.createReviewedMultiComponentApplication(source, server,
                                    (List<gold.debug.windowstolinux.app.service.contract.definition.MultiComponentReviewInput>) args[2],
                                    (ApplicationHealthGate) args[3]);
                        }
                    }
                    case "deployAutomaticallyReviewed" -> {
                        calls.add("deploy");
                        DeploymentStatus attemptStatus = failuresRemaining-- > 0
                                ? DeploymentStatus.FAILED_ROLLED_BACK
                                : terminal;
                        if (args[0] instanceof gold.debug.windowstolinux.app.service.deployment.multi.ReviewedMultiComponentApplication multi) {
                            yield new gold.debug.windowstolinux.shared.deploy.contract.result.deployment.MultiComponentDeploymentResult(
                                    attemptStatus, List.of(),
                                    multi.components().stream().map(
                                            component -> new gold.debug.windowstolinux.shared.deploy.contract.result.deployment.ComponentDeploymentResult(
                                                    component.componentId(),
                                                    attemptStatus == DeploymentStatus.SUCCEEDED
                                                            ? gold.debug.windowstolinux.shared.deploy.contract.result.deployment.ComponentTransactionState.SUCCEEDED
                                                            : gold.debug.windowstolinux.shared.deploy.contract.result.deployment.ComponentTransactionState.RESTORED,
                                                    List.of(), Optional.empty()))
                                            .toList(),
                                    attemptStatus == DeploymentStatus.SUCCEEDED
                                            ? Optional.of("a".repeat(64))
                                            : Optional.empty());
                        }
                        yield new DeploymentOutcome(
                                new DeploymentResult(attemptStatus, List.of(), Optional.empty(),
                                        attemptStatus == DeploymentStatus.SUCCEEDED
                                                ? Optional.of("a".repeat(64))
                                                : Optional.empty()),
                                attemptStatus == DeploymentStatus.SUCCEEDED
                                        ? Optional.of(new DeploymentHandoff.SystemdStartCommand("demo",
                                                "windowstolinux-demo.service", "a".repeat(64)))
                                        : Optional.empty());
                    }
                    default -> throw new AssertionError("unexpected method: " + method.getName());
                });
        return new AutomaticDeploymentUseCase(facade,
                new SourcePreparationUseCase(
                        new DeploymentAnalysisCoordinator(), new WindowsSourcePreparer(directory.resolve("work"))),
                new ServerOperationLockRegistry(),
                agentBoundary == null && analysisModel == null
                        ? null
                        : new gold.debug.windowstolinux.shared.standard.deploy.assistance.AssistedDeploymentAdvisor(
                                (phase, candidates, evidence) -> {
                                    if (phase.equals("PREFLIGHT"))
                                        preflightCalls++;
                                    return new AssistedDeploymentAdvice("fixture", List.of(), List.of());
                                }, interaction(false), m -> {
                                }, analysisModel,
                                agentBoundary == null
                                        ? new gold.debug.windowstolinux.shared.standard.deploy.assistance.AssistedDecisionBudget()
                                        : agentBoundary.budget()),
                agentBoundary);
    }

    private static Path node(Path root, String name) throws Exception {
        Files.createDirectories(root);
        Files.writeString(root.resolve("windowstolinux-application.properties"), "version=1\nhealth.mode=TCP\n");
        Files.writeString(root.resolve("package.json"), "{\"name\":\"" + name
                + "\",\"engines\":{\"node\":\"22\"},\"scripts\":{\"build\":\"build\",\"start\":\"start\"}}");
        Files.writeString(root.resolve("package-lock.json"),
                "{\"name\":\"" + name + "\",\"lockfileVersion\":3,\"packages\":{\"\":{\"name\":\"" + name + "\"}}}");
        return root;
    }
    private static final class ReviewReached extends RuntimeException {
    }
}
