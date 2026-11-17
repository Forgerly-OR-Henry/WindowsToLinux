package gold.debug.windowstolinux.mod.deployment.service.standard.assisted;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

import com.fasterxml.jackson.databind.ObjectMapper;

import gold.debug.windowstolinux.appapi.file.snapshot.FileSnapshot;
import gold.debug.windowstolinux.appapi.ai.model.AiPurpose;
import gold.debug.windowstolinux.appapi.ai.model.AiRole;
import gold.debug.windowstolinux.appapi.ai.model.AiToolCall;
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.action.ActionSemantics;
import gold.debug.windowstolinux.appapi.task.action.ActionTarget;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.action.ApprovalGrant;
import gold.debug.windowstolinux.appapi.task.action.InteractionService;
import gold.debug.windowstolinux.appapi.task.action.WorkflowGrant;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.appapi.ui.UiService;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import gold.debug.windowstolinux.mod.deployment.ai.NativeAiFixture;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentFixedCommandApproval;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentWorkflowAuthorization;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigLoader;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceArchive;
import gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceMaterial;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.linux.publish.DeploymentCandidateChannel;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentRemoteWorkspace;
import gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigSnapshot;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.kind.DeploymentProjectKind;
import gold.debug.windowstolinux.mod.deployment.service.core.model.resource.DeploymentResourceBindings;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentHealthCheck;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentRuntimeDefinition;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.contract.DeploymentPlanAction;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.contract.DeploymentReviewConsent;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.contract.ReviewedDeploymentPlan;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.contract.ReviewedDeploymentRequest;

class DeploymentAssistedCandidateReviewTest {
    private final ServerId server = new ServerId("server");
    private final UUID originalTask = UUID.randomUUID();
    private final DeploymentRemoteLayout layout = DeploymentRemoteLayout.original();
    private final String sha256 = "a".repeat(64);
    private final UUID sourceSnapshot = UUID.randomUUID();
    private final TaskContext task = (TaskContext) Proxy.newProxyInstance(getClass().getClassLoader(),
        new Class<?>[]{TaskContext.class}, (proxy, method, args) -> {
            if (method.getName().equals("id")) return originalTask;
            throw new AssertionError(method.getName());
        });
    private final Map<String, Map<String, Object>> sources = Map.of("demo", Map.of("application", "demo",
        "server", server.id(), "task", originalTask.toString(), "remoteLayoutSha256", layout.digest(),
        "archiveBytes", 14592L, "archiveSha256", sha256, "sourceManifestSha256", "b".repeat(64),
        "originSnapshotId", sourceSnapshot.toString()));

    @Test
    void exposesFullExpectedArchiveReplyOnlyForExactGuardAndOriginalCapture() {
        String candidate = new DeploymentRemoteWorkspace("demo", sha256, layout).candidateId();
        String script = DeploymentCandidateChannel.archiveVerificationScript(layout, "demo", candidate, originalTask);
        var facts = review(candidate, script, DeploymentAssistedCandidateReview.VERIFY, Set.of());
        assertEquals("CANDIDATE_STATE=owned\nBUILD_ACTIVE=no\n", facts.get("helperStdoutPrefix"));
        assertEquals("CANDIDATE_STATE=owned\nBUILD_ACTIVE=no\nBYTES=14592\nSHA256=" + sha256 + "\nOWNER=0\n", facts.get("expectedStdout"));
        var instructions = DeploymentAssistedCandidateReview.verificationInstructions();
        assertTrue(instructions.contains("helperStdoutPrefix"));
        assertTrue(instructions.contains("candidateObservation.expectedStdout against the complete outer command stdout"));
        assertEquals(sha256, facts.get("archiveSha256"));
        assertFalse(facts.containsKey("commandGrant"));
        var scoped = DeploymentAssistedCandidateReview.verificationEvidence(observed(facts, List.of(sources.get("demo"))));
        assertEquals(Set.of("workflowAuthorization", "sourceSelection", "candidateSshAuthentication", "candidateObservation",
            "reviewedApplicationSources"),
            scoped.keySet());
        assertEquals(facts, scoped.get("candidateObservation"));
        assertThrows(IllegalArgumentException.class, () -> review(candidate, script + ":\n",
            DeploymentAssistedCandidateReview.VERIFY, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> review(candidate,
            script.replace(originalTask.toString(), UUID.randomUUID().toString()), DeploymentAssistedCandidateReview.VERIFY, Set.of()));
    }

    @Test
    void scopeVerificationRetainsOnlyTheOriginalCurrentComponentAssociation(@TempDir Path temporary) throws Exception {
        try (var source = new ReviewedSource(temporary)) {
            for (var component : source.archives.entrySet())
                source.scope.bindPreparation(source.plan("deployed-" + component.getKey(), component.getValue()), source.fixture.task);
            for (String component : List.of("backend", "frontend")) {
                var archive = source.archives.get(component);
                String application = "deployed-" + component;
                assertNotEquals(source.material.displayName(), application);
                String candidate = new DeploymentRemoteWorkspace(application, archive.sha256(), layout).candidateId();
                String script = DeploymentCandidateChannel.archiveVerificationScript(layout, application, candidate, source.fixture.task.id());
                try (var request = command(script, new byte[0], 30, 4096)) {
                    var intent = RemoteActions.command(server, "revision", request).withSemantics(new ActionSemantics(
                        DeploymentAssistedCandidateReview.VERIFY, Set.of(new ActionTarget(server.id(), "deployment-candidate", layout.candidateRoot(candidate)))));
                    var originalGroup = (Map<?, ?>) source.scope.effectFacts(intent, source.fixture.task).get("reviewedApplicationSources");
                    assertEquals(2, ((List<?>) originalGroup.get("applications")).size());
                    var scoped = source.scope.facts(intent, request, source.fixture.task);
                    var group = (Map<?, ?>) scoped.get("reviewedApplicationSources");
                    var applications = (List<?>) group.get("applications");
                    assertEquals(1, applications.size());
                    var association = (Map<?, ?>) applications.getFirst();
                    assertEquals(application, association.get("application"));
                    assertEquals(component, association.get("relativeRoot"));
                    assertEquals(archive.sha256(), association.get("archiveSha256"));
                    assertEquals(archive.snapshot().id().toString(), association.get("archiveSnapshotId"));
                    assertEquals(archive.path().toString(), association.get("localArchivePath"));
                    assertEquals(archive.byteCount(), association.get("archiveBytes"));
                    assertEquals(source.fixture.task.id().toString(), association.get("task"));
                    assertEquals(server.id(), association.get("server"));
                    assertEquals(layout.digest(), association.get("remoteLayoutSha256"));
                    assertEquals(source.material.snapshot().id().toString(), association.get("originSnapshotId"));
                    assertEquals(source.material.snapshot().sha256(), association.get("sourceManifestSha256"));
                    assertEquals(originalGroup.get("meaning"), group.get("meaning"));
                    assertFalse(scoped.containsKey("sourceArchiveObservations"));
                    var priorProjection = new LinkedHashMap<>(scoped);
                    priorProjection.remove("reviewedApplicationSources");
                    var json = new ObjectMapper();
                    int additionalBytes = json.writeValueAsBytes(scoped).length - json.writeValueAsBytes(priorProjection).length;
                    int additionalCharacters = json.writeValueAsString(scoped).length() - json.writeValueAsString(priorProjection).length();
                    System.out.println("CANDIDATE_VERIFY_BINDING_OFFLINE_PROJECTION component=" + component
                        + " addedUtf8Bytes=" + additionalBytes + " addedCharacters=" + additionalCharacters
                        + " associationCount=" + applications.size());
                }
            }
            assertTrue(source.fixture.requests.isEmpty(), "This fixture must neither invoke a model nor execute a command");
        }
    }

    @Test
    void scopeRejectsAChangedLegalComponentRootBeforeRegisteringItsAssociation(@TempDir Path temporary) throws Exception {
        try (var source = new ReviewedSource(temporary)) {
            var original = source.archives.get("backend");
            var changed = new DeploymentSourceArchive(original.snapshot(), original.sha256(), original.byteCount(),
                original.uncompressedByteCount(), original.originSnapshotId(), original.sourceManifestSha256(), "frontend");
            var plan = source.plan("deployed-backend", changed);
            assertThrows(IllegalArgumentException.class, () -> source.scope.bindPreparation(plan, source.fixture.task));
            assertTrue(source.fixture.requests.isEmpty());
        }
    }

    @ParameterizedTest
    @CsvSource({"backend,APPROVE", "frontend,APPROVE", "backend,DENY", "frontend,DENY"})
    void independentReviewBindsOnlyTheCurrentTypedComponentAndPreservesHostAuthority(
            String component, String decision, @TempDir Path temporary) throws Exception {
        try (var source = new ReviewedSource(temporary)) {
            for (var original : source.archives.entrySet())
                source.scope.bindPreparation(source.plan("deployed-" + original.getKey(), original.getValue()), source.fixture.task);
            var archive = source.archives.get(component);
            String application = "deployed-" + component;
            assertNotEquals(source.material.displayName(), application);
            String candidate = new DeploymentRemoteWorkspace(application, archive.sha256(), layout).candidateId();
            String script = DeploymentCandidateChannel.archiveVerificationScript(layout, application, candidate, source.fixture.task.id());
            source.fixture.nativeReply(new AiToolCall("candidate-review-" + component, "submit_result", new ObjectMapper().writeValueAsString(
                Map.of("decision", decision, "reason", "Controlled independent review " + decision, "requiresUserConfirmation", false))));
            try (var command = command(script, new byte[0], 30, 4096)) {
                var intent = RemoteActions.command(server, "revision", command).withSemantics(new ActionSemantics(
                    DeploymentAssistedCandidateReview.VERIFY, Set.of(new ActionTarget(server.id(), "deployment-candidate", layout.candidateRoot(candidate)))));
                source.expectedIntent = intent;
                source.expectedCommand = command;
                if (decision.equals("APPROVE")) {
                    var result = DeploymentFixedCommandApproval.approveWithEvidence(source.context, source.host, intent, command,
                        source.fixture.task, source.fixture.configurationRevision, "AUTOMATIC", true, source.scope);
                    assertEquals(TaskResult.SUCCEEDED, result.outcome());
                    assertSame(source.grant, result.value().orElseThrow());
                    assertEquals(1, source.grants.get());
                } else {
                    var denied = assertThrows(ApiException.class, () -> DeploymentFixedCommandApproval.approveWithEvidence(
                        source.context, source.host, intent, command, source.fixture.task,
                        source.fixture.configurationRevision, "AUTOMATIC", true, source.scope));
                    assertEquals("DEPLOYMENT_COMMAND_REVIEW_DENIED", denied.problem().eventCode());
                    assertEquals("Controlled independent review DENY", denied.problem().context().get("reviewReason"));
                    assertEquals(0, source.grants.get());
                }
                assertEquals(1, source.fixture.requests.size());
                var review = source.fixture.requests.getFirst();
                assertEquals(AiPurpose.APPROVAL, review.purpose());
                assertEquals(List.of(AiRole.SYSTEM, AiRole.USER), review.messages().stream().map(message -> message.role()).toList());
                assertEquals(List.of("submit_result"), review.tools().stream().map(tool -> tool.name()).toList());
                var facts = new ObjectMapper().readTree(review.messages().getLast().text());
                assertEquals(intent.exactAction(), facts.path("exactAction").asText());
                assertEquals(new ObjectMapper().valueToTree(intent.serverIds()), facts.path("serverTargets"));
                assertEquals(DeploymentAssistedCandidateReview.VERIFY, facts.path("operation").asText());
                assertEquals(source.fixture.task.id().toString(), facts.at("/currentCommandEvidence/workflowAuthorization/task").asText());
                var associations = facts.at("/currentCommandEvidence/reviewedApplicationSources/applications");
                assertEquals(1, associations.size());
                var association = associations.get(0);
                assertEquals(application, association.path("application").asText());
                assertEquals(component, association.path("relativeRoot").asText());
                assertEquals(server.id(), association.path("server").asText());
                assertEquals(source.fixture.task.id().toString(), association.path("task").asText());
                assertEquals(layout.digest(), association.path("remoteLayoutSha256").asText());
                assertEquals(source.material.snapshot().id().toString(), association.path("originSnapshotId").asText());
                assertEquals(source.material.snapshot().sha256(), association.path("sourceManifestSha256").asText());
                assertEquals(archive.sha256(), association.path("archiveSha256").asText());
                assertEquals(archive.byteCount(), association.path("archiveBytes").asLong());
                var observed = facts.at("/currentCommandEvidence/candidateObservation");
                assertEquals(candidate, observed.path("candidate").asText());
                assertEquals(DeploymentCandidateChannel.archiveVerificationOutput(archive.byteCount(), archive.sha256()),
                    observed.path("expectedStdout").asText());
                assertFalse(facts.at("/currentCommandEvidence").has("sourceArchiveObservations"));
                assertEquals("INDEPENDENT_REVIEW_BEFORE_HOST_GRANT", facts.at("/commandAuthorizationStage/phase").asText());
                assertFalse(facts.at("/commandAuthorizationStage/hostGrantIssuedByThisBoundary").asBoolean(true));
                assertTrue(facts.at("/commandAuthorizationStage/exactHostGrantRequiredBeforeDispatch").asBoolean());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"task", "server", "remoteLayoutSha256", "archiveSha256", "NO_ASSOCIATION", "CANDIDATE"})
    void changedCandidateOrOriginalAssociationCannotProduceVerificationFacts(String mutation) {
        String candidate = new DeploymentRemoteWorkspace("demo", sha256, layout).candidateId();
        if (mutation.equals("CANDIDATE")) candidate = "demo-" + "c".repeat(16);
        String script = DeploymentCandidateChannel.archiveVerificationScript(layout, "demo", candidate, originalTask);
        var changed = new LinkedHashMap<>(sources.get("demo"));
        changed.put(mutation, mutation.equals("archiveSha256") || mutation.equals("remoteLayoutSha256") ? "c".repeat(64) : "other");
        var associations = mutation.equals("NO_ASSOCIATION") ? Map.<String, Map<String, Object>>of() : Map.of("demo", Map.copyOf(changed));
        String finalCandidate = candidate;
        assertThrows(IllegalArgumentException.class, () -> review(finalCandidate, script,
            DeploymentAssistedCandidateReview.VERIFY, Set.of(), associations));
    }

    @ParameterizedTest
    @ValueSource(strings = {"MISSING", "DUPLICATE", "APPLICATION", "task", "archiveBytes", "archiveSha256", "sourceManifestSha256", "originSnapshotId"})
    void projectionRejectsMissingAmbiguousOrChangedCurrentAssociation(String mutation) {
        String candidate = new DeploymentRemoteWorkspace("demo", sha256, layout).candidateId();
        var facts = review(candidate, DeploymentCandidateChannel.archiveVerificationScript(layout, "demo", candidate, originalTask),
            DeploymentAssistedCandidateReview.VERIFY, Set.of());
        var changed = new LinkedHashMap<>(sources.get("demo"));
        changed.put(mutation.equals("APPLICATION") ? "application" : mutation,
            mutation.equals("archiveBytes") ? 14593L : mutation.equals("archiveSha256") ? sha256.substring(0, 63) + "b" : "other");
        var applications = switch (mutation) {
            case "MISSING" -> List.<Map<String, Object>>of();
            case "DUPLICATE" -> List.of(sources.get("demo"), sources.get("demo"));
            default -> List.of(Map.copyOf(changed));
        };
        assertThrows(IllegalArgumentException.class, () -> DeploymentAssistedCandidateReview.verificationEvidence(observed(facts, applications)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"STDIN", "TIMEOUT", "OUTPUT", "ARCHIVE_PATH", "TASK", "TARGET", "OPERATION"})
    void verificationStillRejectsChangedOriginalCommandFrame(String mutation) {
        String candidate = new DeploymentRemoteWorkspace("demo", sha256, layout).candidateId();
        String script = DeploymentCandidateChannel.archiveVerificationScript(layout, "demo", candidate, originalTask);
        if (mutation.equals("ARCHIVE_PATH")) script = script.replace("source.tar.gz", "other.tar.gz");
        if (mutation.equals("TASK")) script = script.replace(originalTask.toString(), UUID.randomUUID().toString());
        try (var request = command(script, mutation.equals("STDIN") ? new byte[]{1} : new byte[0],
                mutation.equals("TIMEOUT") ? 31 : 30, mutation.equals("OUTPUT") ? 8192 : 4096)) {
            var target = new ActionTarget(server.id(), "deployment-candidate", layout.candidateRoot(candidate) + (mutation.equals("TARGET") ? "/other" : ""));
            var intent = RemoteActions.command(server, "revision", request).withSemantics(new ActionSemantics(
                mutation.equals("OPERATION") ? "deployment.candidate.create.v1" : DeploymentAssistedCandidateReview.VERIFY, Set.of(target)));
            assertThrows(IllegalArgumentException.class, () -> DeploymentAssistedCandidateReview.facts(intent, request, task, layout, sources, Set.of()));
        }
    }

    @Test
    void verifiesAlternateCandidateOnlyForTheOriginalDurableOperation() {
        UUID operation = UUID.randomUUID();
        String candidate = new DeploymentRemoteWorkspace("demo", sha256, Optional.of(operation), layout).candidateId();
        String script = DeploymentCandidateChannel.archiveVerificationScript(layout, "demo", candidate, originalTask);
        assertThrows(DeploymentAssistedCandidateReview.AlternateCandidate.class, () ->
            review(candidate, script, DeploymentAssistedCandidateReview.VERIFY, Set.of(UUID.randomUUID())));
        assertEquals(sha256, review(candidate, script, DeploymentAssistedCandidateReview.VERIFY, Set.of(operation)).get("archiveSha256"));
    }

    @Test
    void describesCandidateQueryWithoutClaimingObservedOwnership() {
        String candidate = new DeploymentRemoteWorkspace("demo", sha256, layout).candidateId();
        String script = DeploymentCandidateChannel.candidateQueryScript(layout, "demo", candidate, originalTask);
        var facts = review(candidate, script, DeploymentAssistedCandidateReview.QUERY, Set.of());
        assertEquals("CANDIDATE_STATE=owned\nBUILD_ACTIVE=no\n", facts.get("expectedStdout"));
        assertFalse(facts.containsKey("helperStdoutPrefix"));
        assertFalse(facts.containsKey("archiveSha256"));
        assertThrows(IllegalArgumentException.class, () -> review(candidate, script.replace("candidate-query-task", "candidate-cleanup-task"),
            DeploymentAssistedCandidateReview.QUERY, Set.of()));
    }

    private Map<String, Object> review(String candidate, String script, String operation, Set<UUID> reviewedOperations) {
        return review(candidate, script, operation, reviewedOperations, sources);
    }

    private Map<String, Object> review(String candidate, String script, String operation, Set<UUID> reviewedOperations,
                                       Map<String, Map<String, Object>> associations) {
        long seconds = operation.equals(DeploymentAssistedCandidateReview.VERIFY) ? 30 : 20;
        try (var request = command(script, new byte[0], seconds, 4096)) {
            var target = new ActionTarget(server.id(), "deployment-candidate", layout.candidateRoot(candidate));
            var intent = RemoteActions.command(server, "revision", request).withSemantics(new ActionSemantics(operation, Set.of(target)));
            return DeploymentAssistedCandidateReview.facts(intent, request, task, layout, associations, reviewedOperations);
        }
    }

    private CommandRequest command(String script, byte[] input, long seconds, int outputLimit) {
        return new CommandRequest(DeploymentManagedHelperBundle.verifiedCommand(script, layout), input, Duration.ofSeconds(seconds), outputLimit);
    }

    private Map<String, Object> observed(Map<String, Object> candidate, List<Map<String, Object>> applications) {
        return Map.of("candidateObservation", candidate, "workflowAuthorization", Map.of("task", originalTask.toString()),
            "sourceSelection", Map.of("snapshotId", sourceSnapshot.toString(), "sourceRevision", "b".repeat(64)),
            "candidateSshAuthentication", Map.of("server", server.id()), "hostPrerequisiteObservations", Map.of(),
            "toolchainPreparationObservations", Map.of(), "sourceArchiveObservations", Map.of(),
            "reviewedApplicationSources", Map.of("applications", applications, "meaning", "Original fixture association"));
    }

    private final class ReviewedSource implements AutoCloseable {
        final NativeAiFixture fixture = new NativeAiFixture();
        final DeploymentAssistedScope scope;
        final WorkflowGrant permission;
        final DeploymentSourceMaterial material;
        final Map<String, DeploymentSourceArchive> archives = new LinkedHashMap<>();
        final AtomicInteger grants = new AtomicInteger();
        final ApprovalGrant grant = new ApprovalGrant(UUID.randomUUID());
        final ModContext context;
        final InteractionService host;
        ActionIntent expectedIntent;
        CommandRequest expectedCommand;

        ReviewedSource(Path temporary) throws Exception {
            host = (InteractionService) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{InteractionService.class}, (p, m, a) -> {
                if (m.getName().equals("openReviewedWorkflow"))
                    return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of((WorkflowGrant) () -> {}), Optional.empty());
                assertEquals("approveReviewedAction", m.getName());
                assertSame(expectedIntent, a[0]);
                assertEquals(Optional.of(expectedCommand), a[1]);
                assertEquals(false, a[2]);
                assertSame(fixture.task, a[4]);
                assertEquals(1, fixture.requests.size(), "The host grant must follow this command's actual independent model request");
                grants.incrementAndGet();
                return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(grant), Optional.empty());
            });
            var ui = (UiService) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{UiService.class}, (p, m, a) -> {
                assertEquals("environment", m.getName());
                return new UiEnvironment(Locale.ENGLISH, "LIGHT", false);
            });
            context = (ModContext) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ModContext.class}, (p, m, a) -> {
                if (m.getName().equals("interaction")) return host;
                if (m.getName().equals("ui")) return ui;
                try { return m.invoke(fixture.context, a); }
                catch (InvocationTargetException failure) { throw failure.getCause(); }
            });
            scope = new DeploymentAssistedScope(context, fixture.task, fixture.configurationRevision, "AUTOMATIC", true);
            permission = DeploymentWorkflowAuthorization.open(context, fixture.task, server, fixture.configurationRevision,
                "AUTOMATIC", true, "Draft[logicalRootName=selected-project]");
            scope.workflowOpened(permission);
            material = new DeploymentSourceMaterial("selected-project", snapshot(temporary, UUID.randomUUID(), "b".repeat(64)),
                List.of("backend/go.mod", "backend/main.go", "frontend/go.mod", "frontend/main.go"), List.of());
            scope.sourceCaptured(server, material, fixture.task);
            for (String component : List.of("backend", "frontend")) {
                Path directory = Files.createDirectory(temporary.resolve(component));
                Files.writeString(directory.resolve("go.mod"), "module fixture\ngo 1.27\n", StandardCharsets.UTF_8);
                Files.writeString(directory.resolve("main.go"), "package main\nfunc main() {}\n", StandardCharsets.UTF_8);
                var archive = new DeploymentSourceArchive(snapshot(temporary.resolve("archive-" + component), UUID.randomUUID(), "c".repeat(64)),
                    (component.equals("backend") ? "a" : "d").repeat(64), 14592, 8264, material.snapshot().id(), material.snapshot().sha256(), component);
                archives.put(component, archive);
                scope.archiveCaptured(material, archive, List.of(), fixture.task);
            }
        }

        ReviewedDeploymentPlan plan(String application, DeploymentSourceArchive archive) {
            var facts = new DeploymentProjectFacts(material.snapshot().directory().resolve(archive.relativeRoot()), application,
                DeploymentProjectKind.GO_SERVICE, DeploymentBuildToolType.GO_MODULE, List.of(), List.of(), List.of());
            var runtime = new DeploymentRuntimeDefinition.GoService("1.27", "app", "main.go", new DeploymentHealthCheck.Process(5, 1));
            var request = new ReviewedDeploymentRequest(server, facts, material, archive,
                DeploymentConfigSnapshot.create(application, 1, "fixture", Instant.EPOCH, List.of()), List.of(),
                new DeploymentResourceBindings(List.of(), Optional.of(List.of())), runtime, Optional.empty(),
                DeploymentConfigLoader.current().build().limits(), new DeploymentReviewConsent(application, server.id(),
                material.snapshot().sha256(), archive.sha256(), Instant.EPOCH), false, true, layout);
            return new ReviewedDeploymentPlan(request, List.of(DeploymentPlanAction.VERIFY_SOURCE_IDENTITY,
                DeploymentPlanAction.CHECK_HEALTH, DeploymentPlanAction.ROLLBACK_ON_FAILURE));
        }

        public void close() throws Exception {
            permission.close();
        }
    }

    private FileSnapshot snapshot(Path directory, UUID id, String hash) {
        return (FileSnapshot) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{FileSnapshot.class}, (p, m, a) -> switch (m.getName()) {
            case "id" -> id;
            case "directory" -> directory;
            case "sha256" -> hash;
            default -> throw new AssertionError(m.getName());
        });
    }
}
