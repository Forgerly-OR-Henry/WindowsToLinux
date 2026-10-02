package gold.debug.windowstolinux.mod.deployment.service.standard;

import gold.debug.windowstolinux.appapi.task.ActionIntent;
import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.OperationResult;
import gold.debug.windowstolinux.appapi.task.Outcome;
import gold.debug.windowstolinux.appapi.task.TaskContext;
import gold.debug.windowstolinux.appapi.task.TaskSnapshot;
import gold.debug.windowstolinux.appapi.task.TaskState;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import com.fasterxml.jackson.databind.*;
import gold.debug.windowstolinux.app.db.server.StoredServerProfile;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.task.PlatformInteractionService;
import gold.debug.windowstolinux.appapi.ai.*;
import gold.debug.windowstolinux.appapi.linux.*;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.ServerRef;
import gold.debug.windowstolinux.appapi.task.CancelToken;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentAssistedBuildEvidenceAssertions;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentAssistedBuildReviewCodec;
import gold.debug.windowstolinux.mod.deployment.service.core.DeploymentReferenceFlow;
import gold.debug.windowstolinux.mod.deployment.service.core.model.*;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.component.DiscoveredProjectComponent;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.database.DeploymentPreparedDatabase;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.publication.DeploymentStandardPublicationRecovery;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Runs original single and graph workflows through physical failure receipts, human corrections and formal H2 CAS. / 以物理失败回执、人工纠正及正式H2 CAS运行原单项及图流程。 */
class DeploymentAssistedBuildWorkflowTest extends DeploymentRuntimeFixture {
    private final AtomicInteger language = new AtomicInteger(), independent = new AtomicInteger(),
        recoveries = new AtomicInteger();

    private final Set<String> reviewedOperations = ConcurrentHashMap.newKeySet();

    private final List<ActionIntent> hostApprovals = new CopyOnWriteArrayList<>();

    private boolean returnToOriginal;

    private java.util.function.Consumer<ActionIntent> reviewFault = ignored -> {
    };

    @Override
    protected Path selectedSourceDirectory() {
        return root.resolve("input");
    }

    @Override
    protected PlatformInteractionService.Prompts interactionPrompts() {
        return new PlatformInteractionService.Prompts() {
            public TransferConflictChoice transferConflict(TransferConflict c, ServerRef s, TaskSnapshot t,
                                                           CancelToken token) {
                throw new AssertionError();
            }

            public TransferStallChoice transferStalled(TransferStall c, ServerRef s, TaskSnapshot t,
                                                       CancelToken token) {
                throw new AssertionError();
            }

            public String transferProgress(TransferProgress progress) {
                return "Transferred " + progress.completedBytes();
            }

            public char[] text(String title, String prompt, boolean secret, TaskSnapshot task,
                               CancelToken token) {
                throw new AssertionError();
            }

            public boolean approve(ActionIntent intent, TaskSnapshot task, CancelToken token) {
                hostApprovals.add(intent);
                reviewFault.accept(intent);
                return true;
            }
        };
    }

    @ParameterizedTest
    @CsvSource({"1,MANUAL_REVIEW", "2,MANUAL_REVIEW", "1,AUTOMATIC", "2,AUTOMATIC", "1,FULL_CONTROL", "2,FULL_CONTROL"})
    void physicalCompilerFailureUsesOneOriginalCandidateAndCompleteReviewedTemplate(int count, String policy)
        throws Exception {
        run(count, policy, true, false);
    }

    @ParameterizedTest
    @CsvSource({"false,false", "true,true"})
    void unchangedInputsAndExplicitModelStopDoNotRebuild(boolean changed, boolean stop) throws Exception {
        run(1, "MANUAL_REVIEW", changed, stop);
    }

    @org.junit.jupiter.api.Test
    void thirdComponentCannotSendAnotherMinimumFallbackAfterTwoOriginalBranches() throws Exception {
        run(3, "MANUAL_REVIEW", true, false, true);
    }

    @org.junit.jupiter.api.Test
    void repeatedPhysicalInputFailureCannotResetTwoTotalRetriesOrOriginalBudget() throws Exception {
        returnToOriginal = true;
        run(1, "MANUAL_REVIEW", true, false, false, true);
    }

    @ParameterizedTest
    @CsvSource({"1", "2"})
    void originalUnknownBuildOnlyObservesAndCleansAfterItsActualExitIsKnown(int count) throws Exception {
        unknown(count, "BUILD");
    }

    @ParameterizedTest
    @CsvSource({"1,FORMAL", "1,CHILD", "2,ROOT"})
    void pendingFormalChildOrRootReviewCannotSendAnotherBuild(int count, String boundary) throws Exception {
        unknown(count, boundary);
    }

    private void unknown(int count, String boundary) throws Exception {
        source(count);
        enable();
        var runtime = open();
        Throwable first = null;
        try {
            start(runtime, true);
            runtime.servers().saveServerProfile(
                ServerProfile
                    .fromStored(new StoredServerProfile("server", "127.0.0.1", 22, "root", "fixture-password")),
                "fixture-password".toCharArray());
            var actual = runtime.modContexts().get(OWNER).context();
            runtime.tasks().revokeOwnerServices(OWNER, VERSION);
            var tasks = runtime.tasks().bind(OWNER, VERSION, false);
            var peer = new DeploymentAssistedBuildPeer(false);
            peer.unknownBuild = boundary.equals("BUILD");
            var faultInstalled = new AtomicBoolean();
            if (!boundary.equals("BUILD"))
                reviewFault = intent -> {
                    if (intent.type().equals("deployment.assisted.build-review")
                        && faultInstalled.compareAndSet(false, true)) {
                        try {
                            installReviewFault(runtime, boundary);
                        } catch (Exception invalidFault) {
                            throw new AssertionError("The real H2 review fault must be installed before CAS",
                                invalidFault);
                        }
                    }
                };
            var ai = models(false);
            LinuxService linux = new LinuxService() {
                public RemoteSession connect(ServerRef server, TaskContext task) throws ApiException {
                    return peer.session(task);
                }

                public List<TransferCheckpoint> pendingTransfers(TaskContext task) {
                    return List.of();
                }
            };
            var bound = (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(),
                new Class<?>[]{ModContext.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("tasks"))
                        return tasks;
                    if (method.getName().equals("ai"))
                        return ai;
                    if (method.getName().equals("linux"))
                        return linux;
                    try {
                        return method.invoke(actual, arguments);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
            var flow = new DeploymentReferenceFlow(bound, new DeploymentStandardPublicationRecovery(bound));
            try (var recovery = tasks.registerRecovery(DeploymentReferenceFlow.TASK_TYPE, flow)) {
                var handle = new DeploymentStaticWorkflow(bound, flow).submit(new ServerRef("server"),
                    new DeploymentStaticWorkflow.Draft("", 65536, 100, 65536), Optional.empty(),
                    reviewer(new AtomicReference<>(), new AtomicReference<>(), count, true), true, "MANUAL_REVIEW");
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                while (handle.snapshot().state() != TaskState.UNKNOWN && handle.snapshot().state() != TaskState.ENDED
                    && System.nanoTime() < until)
                    Thread.sleep(20);
                assertEquals(TaskState.UNKNOWN, handle.snapshot().state(), handle.snapshot().toString());
                assertEquals(boundary.equals("BUILD") ? 0 : 1, recoveries.get());
                assertEquals(1, peer.builds.values().stream().mapToInt(Integer::intValue).sum());
                assertEquals(1, peer.creates.size());
                assertEquals(1, peer.uploads.size());
                int calls = language.get();
                var created = Map.copyOf(peer.creates);
                var uploaded = Map.copyOf(peer.uploads);
                var builds = Map.copyOf(peer.builds);
                assertTrue(scalar(runtime,
                    "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind='FILE' AND released_at IS NULL") > 0);
                if (boundary.equals("BUILD")) {
                    var unknown = runtime.tasks().reconcile(handle.id()).toCompletableFuture().get(20,
                        TimeUnit.SECONDS);
                    assertEquals(TaskState.UNKNOWN, unknown.state());
                } else {
                    assertTrue(faultInstalled.get());
                    assertReviewBoundary(runtime, boundary);
                    if (!boundary.equals("FORMAL")) {
                        int commands = peer.commands.size();
                        var unknown = runtime.tasks().reconcile(handle.id()).toCompletableFuture().get(20,
                            TimeUnit.SECONDS);
                        assertEquals(TaskState.UNKNOWN, unknown.state());
                        assertEquals(commands, peer.commands.size(),
                            "An unconfirmed formal checkpoint cannot admit remote observation or cleanup");
                    }
                    removeReviewFault(runtime, boundary);
                }
                assertEquals(builds, peer.builds);
                assertEquals(calls, language.get());
                assertEquals(created, peer.creates);
                assertEquals(uploaded, peer.uploads);
                assertFalse(peer.present.isEmpty());
                peer.active = false;
                var ended = runtime.tasks().reconcile(handle.id()).toCompletableFuture().get(20, TimeUnit.SECONDS);
                assertEquals(TaskState.ENDED, ended.state());
                assertEquals(Optional.of(Outcome.FAILED), ended.outcome());
                assertEquals(builds, peer.builds);
                assertEquals(calls, language.get());
                assertEquals(created, peer.creates);
                assertEquals(uploaded, peer.uploads);
                assertTrue(peer.present.isEmpty());
                assertTrue(peer.publications.isEmpty());
                assertEquals(Set.of(handle.id()), peer.tasks);
                try (var access = scope(runtime, true);
                     var connection = access.openConnection();
                     var statement = connection.createStatement();
                     var rows = statement.executeQuery("SELECT COUNT(*) FROM deployment_execution")) {
                    assertTrue(rows.next());
                    assertEquals(1, rows.getLong(1));
                }
                assertEquals(0,
                    scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
            }
        } catch (Exception | AssertionError failure) {
            first = failure;
            throw failure;
        } finally {
            if (first == null)
                runtime.close();
            else
                try {
                    runtime.close();
                } catch (Exception | AssertionError cleanup) {
                    first.addSuppressed(cleanup);
                }
        }
    }

    private void installReviewFault(gold.debug.windowstolinux.app.main.start.PlatformRuntime runtime, String boundary)
        throws Exception {
        if (boundary.equals("FORMAL"))
            try (var access = scope(runtime, false);
                 var connection = access.openConnection();
                 var statement = connection.createStatement()) {
                String previous;
                try (var rows = statement.executeQuery("SELECT reviewed_revision FROM deployment_execution")) {
                    assertTrue(rows.next());
                    previous = rows.getString(1);
                    assertFalse(rows.next());
                }
                statement.execute(
                    "ALTER TABLE deployment_execution ADD CONSTRAINT assisted_review_fault CHECK(reviewed_revision='"
                        + previous + "')");
            }
        else
            try (var connection = runtime.persistence().database().openConnection();
                 var statement = connection.createStatement()) {
                String key = boundary.equals("ROOT") ? "deployment-publication-graph" : "deployment-publication-remote";
                statement.execute(
                    "ALTER TABLE platform_task_resource ADD CONSTRAINT assisted_checkpoint_fault CHECK(resource_id<>'"
                        + OWNER + "/checkpoint/" + key + "' OR recovery_json NOT LIKE '%CONFIRMED%')");
            }
    }

    private void assertReviewBoundary(gold.debug.windowstolinux.app.main.start.PlatformRuntime runtime,
                                      String boundary) throws Exception {
        var json = new ObjectMapper();
        try (var access = scope(runtime, true);
             var connection = access.openConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT request_payload FROM deployment_execution")) {
            assertTrue(rows.next());
            var request = json.readTree(rows.getBytes(1));
            assertEquals(boundary.equals("FORMAL") ? 0 : 1, request.path("buildReviews").size());
            assertEquals(boundary.equals("FORMAL"), !request.path("buildIntent").isNull());
            assertFalse(rows.next());
        }
        String key = boundary.equals("ROOT") ? "deployment-publication-graph" : "deployment-publication-remote";
        try (var connection = runtime.persistence().database().openConnection();
             var statement = connection.prepareStatement(
                 "SELECT recovery_json FROM platform_task_resource WHERE resource_id=? AND released_at IS NULL")) {
            statement.setString(1, OWNER + "/checkpoint/" + key);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                var facts = json.readTree(json.readTree(rows.getString(1)).path("fields").path("payload").textValue());
                assertEquals("PENDING", facts.path("buildReviewState").textValue());
                assertFalse(rows.next());
            }
        }
        try (var connection = runtime.persistence().database().openConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery(
                 "SELECT recovery_json FROM platform_task_resource WHERE resource_kind='CUSTOM' AND resource_id LIKE '"
                     + OWNER + "/checkpoint/%' AND released_at IS NULL")) {
            int retained = 0;
            while (rows.next()) {
                var payload = json.readTree(rows.getString(1)).path("fields").path("payload").textValue();
                assertNotNull(payload);
                assertTrue(payload.getBytes(StandardCharsets.UTF_8).length <= 12000);
                retained++;
            }
            assertTrue(retained > 0, "The original task retains its actual author checkpoints");
        }
    }

    private void removeReviewFault(gold.debug.windowstolinux.app.main.start.PlatformRuntime runtime, String boundary)
        throws Exception {
        if (boundary.equals("FORMAL"))
            try (var access = scope(runtime, false);
                 var connection = access.openConnection();
                 var statement = connection.createStatement()) {
                statement.execute("ALTER TABLE deployment_execution DROP CONSTRAINT assisted_review_fault");
            }
        else
            try (var connection = runtime.persistence().database().openConnection();
                 var statement = connection.createStatement()) {
                statement.execute("ALTER TABLE platform_task_resource DROP CONSTRAINT assisted_checkpoint_fault");
            }
    }

    private void run(int count, String policy, boolean changed, boolean stop) throws Exception {
        run(count, policy, changed, stop, false);
    }

    private void run(int count, String policy, boolean changed, boolean stop, boolean preCandidate) throws Exception {
        run(count, policy, changed, stop, preCandidate, false);
    }

    private void run(int count, String policy, boolean changed, boolean stop, boolean preCandidate, boolean repeated)
        throws Exception {
        source(count);
        enable();
        if (preCandidate)
            for (int i = 0; i < count; i++)
                Files.writeString(selectedSourceDirectory().resolve("component" + i).resolve("go.mod"),
                    "module example.test/component" + i + "\n\ngo 1.24.9\n");
        String retainedRequest = null;
        try (var runtime = open()) {
            start(runtime, true);
            runtime.servers().saveServerProfile(
                ServerProfile
                    .fromStored(new StoredServerProfile("server", "127.0.0.1", 22, "root", "fixture-password")),
                "fixture-password".toCharArray());
            var original = runtime.modContexts().get(OWNER).context();
            var peer = new DeploymentAssistedBuildPeer(repeated);
            var ai = models(stop);
            LinuxService linux = new LinuxService() {
                public RemoteSession connect(ServerRef server, TaskContext task) throws ApiException {
                    return peer.session(task);
                }

                public List<TransferCheckpoint> pendingTransfers(TaskContext task) {
                    return List.of();
                }
            };
            var bound = (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(),
                new Class<?>[]{ModContext.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("ai"))
                        return ai;
                    if (method.getName().equals("linux"))
                        return linux;
                    try {
                        return method.invoke(original, arguments);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
            var id = new AtomicReference<UUID>();
            var archives = new AtomicReference<Map<String, String>>();
            var workflow = new DeploymentStaticWorkflow(bound, new DeploymentReferenceFlow(bound));
            var handle = workflow.submit(new ServerRef("server"),
                new DeploymentStaticWorkflow.Draft("", 65536, 100, 65536), Optional.empty(),
                reviewer(id, archives, count, changed), true, policy);
            OperationResult<Void> result;
            try {
                result = handle.completion().toCompletableFuture().get(45, TimeUnit.SECONDS);
            } catch (TimeoutException unavailable) {
                throw new AssertionError("Original build task did not settle: " + handle.snapshot()
                    + "; actual commands=" + peer.commands, unavailable);
            }
            boolean success = changed && !stop && !preCandidate && !repeated;
            assertEquals(success ? Outcome.SUCCEEDED : Outcome.FAILED, result.outcome(), result.toString());
            assertEquals(TaskState.ENDED, handle.snapshot().state());
            assertEquals(Set.of(handle.id()), peer.tasks);
            assertEquals(handle.id(), id.get());
            assertEquals(count, archives.get().size());
            assertEquals(preCandidate ? 0 : count, peer.creates.size());
            assertEquals(preCandidate ? 0 : count, peer.uploads.size());
            peer.creates.values().forEach(value -> assertEquals(1, value));
            peer.uploads.values().forEach(value -> assertEquals(1, value));
            assertEquals(preCandidate ? 0 : repeated ? 3 : success ? count * 2 : count,
                peer.builds.values().stream().mapToInt(Integer::intValue).sum());
            assertEquals(success ? count : 0, peer.publications.size());
            assertTrue(peer.present.isEmpty());
            for (var candidate : peer.builds.keySet()) {
                assertTrue(archives.get().containsValue(peer.archives.get(candidate)));
                var seconds = peer.deadlines.get(candidate);
                if (seconds.size() == 2)
                    assertTrue(seconds.get(1) <= seconds.get(0), "Original build deadline must not reset");
                var scripts = peer.scripts.get(candidate);
                if (scripts.size() == 2)
                    assertNotEquals(scripts.get(0), scripts.get(1),
                        "Corrected tool and runtime must change actual fixed script bytes");
                if (repeated) {
                    assertEquals(3, scripts.size());
                    assertEquals(scripts.get(0), scripts.get(2));
                    assertNotEquals(scripts.get(0), scripts.get(1));
                }
            }
            assertEquals(preCandidate ? 0 : repeated ? 2 : count, recoveries.get());
            assertTrue(language.get() <= 30);
            if (preCandidate)
                assertEquals(List.of("1.24", "1.25", "1.24", "1.25", "1.24"), peer.toolBranches,
                    "The third component may observe its first known failure but must not send a third fallback");
            if (policy.equals("MANUAL_REVIEW"))
                assertEquals(0, independent.get());
            else {
                assertTrue(independent.get() > language.get());
                assertTrue(reviewedOperations.containsAll(Set.of("deployment.toolchain.prepare.v1",
                    "deployment.toolchain.bind.v1", "deployment.build.run.v1", "deployment.publication.publish.v1",
                    "deployment.assisted.environment-recovery", "deployment.assisted.build-review")));
            }
            assertEquals(repeated ? 2 : success ? count : 0, hostApprovals.stream()
                .filter(intent -> intent.type().equals("deployment.assisted.environment-recovery")).count());
            try (var access = scope(runtime, true);
                 var connection = access.openConnection();
                 var statement = connection.createStatement();
                 var rows = statement.executeQuery(
                     "SELECT platform_task_id,phase,mode,business_committed,reviewed_revision,request_payload FROM deployment_execution")) {
                assertTrue(rows.next());
                assertEquals(handle.id(), rows.getObject(1, UUID.class));
                assertEquals("FINISHED", rows.getString(2));
                assertEquals("ASSISTED", rows.getString(3));
                assertEquals(success, rows.getBoolean(4));
                var request = new ObjectMapper().readTree(new String(rows.getBytes(6), StandardCharsets.UTF_8));
                assertEquals(rows.getString(5), request.path("reviewedRevision").asText());
                assertEquals(repeated ? 2 : success ? count : 0, request.path("buildReviews").size());
                int reviewIndex = 0;
                for (var review : request.path("buildReviews")) {
                    var before = DeploymentAssistedBuildReviewCodec
                        .decodeDescription(review.path("previousPlan").asText());
                    var after = DeploymentAssistedBuildReviewCodec.decodeDescription(review.path("nextPlan").asText());
                    assertEquals(before.source(), after.source());
                    assertEquals(before.configuration(), after.configuration());
                    assertEquals(repeated && reviewIndex == 1 ? "1.25" : "1.24", before.inputs().get("version"));
                    assertEquals(repeated && reviewIndex == 1 ? "1.24" : "1.25", after.inputs().get("version"));
                    reviewIndex++;
                    assertTrue(archives.get().containsValue(after.source().get("archive")));
                    assertNotEquals(before.runtime(), after.runtime());
                }
                assertFalse(rows.next(), "Formal begin belongs to one original UUID and cannot be replayed");
            }
            assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
            if (success)
                try (var access = scope(runtime, false); var connection = access.openConnection()) {
                    retainedRequest = DeploymentAssistedBuildEvidenceAssertions.verify(connection,
                        peer.publicationDigests);
                }
            if (repeated)
                try (var access = scope(runtime, false); var connection = access.openConnection()) {
                    DeploymentAssistedBuildEvidenceAssertions.verifyContinuation(connection);
                }
        }
        if (retainedRequest != null)
            try (var reopened = open()) {
                start(reopened, true);
                try (var access = scope(reopened, true);
                     var connection = access.openConnection();
                     var statement = connection.createStatement();
                     var rows = statement.executeQuery("SELECT request_payload FROM deployment_execution")) {
                    assertTrue(rows.next());
                    assertEquals(retainedRequest, new String(rows.getBytes(1), StandardCharsets.UTF_8));
                    assertFalse(rows.next());
                }
                assertEquals(0,
                    scalar(reopened, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
            }
    }

    private DeploymentStaticWorkflow.Reviewer reviewer(AtomicReference<UUID> taskId,
                                                       AtomicReference<Map<String, String>> archives, int count, boolean changed) {
        var corrections = new HashMap<String, Integer>();
        return new DeploymentStaticWorkflow.Reviewer() {
            public OperationResult<DeploymentStaticWorkflow.Selection> select(ServerRef server, String sha,
                                                                              List<DiscoveredProjectComponent> components, TaskContext task) {
                assertEquals(count, components.size());
                taskId.set(task.id());
                var choices = new LinkedHashMap<String, DeploymentStaticWorkflow.Choice>();
                components.forEach(component -> choices.put(component.id(),
                    new DeploymentStaticWorkflow.Choice(DeploymentProjectKind.GO_SERVICE, Set.of())));
                return success(new DeploymentStaticWorkflow.Selection(
                    count == 1 ? components.getFirst().id() : "reviewed-application", choices,
                    count == 1 ? Optional.empty() : Optional.of(components.getFirst().id())));
            }

            public OperationResult<Map<String, DeploymentStaticWorkflow.Answers>> review(ServerRef server, String sha,
                                                                                         List<DeploymentStaticWorkflow.Prompt> prompts, TaskContext task) {
                assertEquals(taskId.get(), task.id());
                var values = new LinkedHashMap<String, DeploymentStaticWorkflow.Answers>();
                int port = 8080;
                for (var prompt : prompts) {
                    var answer = new LinkedHashMap<>(prompt.defaults());
                    answer.put("primary", prompt.componentId());
                    answer.put("secondary", "main.go");
                    answer.put("version", "1.24");
                    answer.put("jvmTarget", "");
                    answer.put("port", Integer.toString(port++));
                    answer.put("healthMode", "TCP");
                    answer.put("exposure", "INTERNAL");
                    var file = new DeploymentFileBinding("uploads",
                        new DeploymentStoragePath(prompt.componentId() + "-uploads",
                            DeploymentStoragePath.AccessMode.READ_WRITE, "uploads-v1", true),
                        DeploymentStorageLocation.defaults(), DeploymentStorageLocation.StorageResourceType.FILE);
                    values.put(prompt.componentId(), new DeploymentStaticWorkflow.Answers(answer, List.of(file),
                        new DeploymentBuildLimits(600, 32, 512, 65536, 128L * 1024 * 1024, false), false, true));
                }
                return success(Map.copyOf(values));
            }

            public OperationResult<DeploymentStaticWorkflow.Answers> reviewBuildCorrection(ServerRef server, String sha,
                                                                                           DeploymentStaticWorkflow.Prompt prompt, DeploymentStaticWorkflow.Answers original,
                                                                                           TaskContext task) {
                assertEquals(taskId.get(), task.id());
                assertEquals(Set.of("primary", "secondary", "version", "jvmTarget"),
                    prompt.fields().stream().map(field -> field.id().substring(field.id().lastIndexOf('/') + 1))
                        .collect(java.util.stream.Collectors.toSet()));
                assertEquals(600, original.limits().timeoutSeconds());
                assertTrue(original.adapterRisk());
                assertEquals(1, original.files().size());
                assertEquals("uploads", original.files().getFirst().bindingId());
                var answer = new LinkedHashMap<>(original.values());
                int attempt = corrections.merge(prompt.componentId(), 1, Integer::sum);
                answer.put("version", changed && (!returnToOriginal || attempt == 1) ? "1.25" : "1.24");
                return success(new DeploymentStaticWorkflow.Answers(answer, original.files(), original.limits(),
                    original.containerRisk(), original.adapterRisk()));
            }

            public OperationResult<Boolean> confirm(ServerRef server, String sha, Map<String, String> tar,
                                                    Map<String, DeploymentStaticWorkflow.Answers> answers,
                                                    Map<String, DeploymentPreparedDatabase> databases, Optional<DeploymentStaticWorkflow.GitReview> git,
                                                    TaskContext task) {
                assertEquals(taskId.get(), task.id());
                assertEquals(count, tar.size());
                archives.set(Map.copyOf(tar));
                return success(true);
            }
        };
    }

    private AiService models(boolean stop) {
        return new AiService() {
            public String configurationRevision(AiPurpose purpose, TaskContext task) {
                assertTrue(Set.of(AiPurpose.LANGUAGE, AiPurpose.APPROVAL).contains(purpose));
                return "a".repeat(64);
            }

            public OperationResult<AiResponse> call(AiRequest request, TaskContext task) throws ApiException {
                return call(request, "a".repeat(64), task);
            }

            public OperationResult<AiResponse> call(AiRequest request, String expected, TaskContext task)
                throws ApiException {
                assertEquals("a".repeat(64), expected);
                JsonNode facts;
                try {
                    facts = new ObjectMapper().readTree(request.messages().getLast().text());
                } catch (Exception invalid) {
                    throw new AssertionError(invalid);
                }
                String response;
                if (request.purpose() == AiPurpose.APPROVAL) {
                    independent.incrementAndGet();
                    reviewedOperations.add(facts.path("operation").asText());
                    response = "{\"decision\":\"APPROVE\",\"risk\":\"NORMAL\",\"reason\":\"Exact registered fixed action\"}";
                } else {
                    assertEquals(AiPurpose.LANGUAGE, request.purpose());
                    language.incrementAndGet();
                    if (facts.path("phase").asText().equals("BUILD")) {
                        recoveries.incrementAndGet();
                        assertTrue(facts.path("fields").isEmpty(),
                            "Source and human answers cannot be overwritten by AI");
                        response = "{\"decision\":\"" + (stop ? "STOP" : "RETRY")
                            + "\",\"reason\":\"Only the original human may correct typed fields\",\"suggestions\":{}}";
                    } else
                        response = "{\"action\":\"ADVISE\",\"sourceRevision\":\""
                            + facts.path("sourceRevision").asText()
                            + "\",\"arguments\":{\"suggestions\":[],\"summary\":\"Keep explicit human review\",\"unresolved\":[]}}";
                }
                return success(new AiResponse(response, "controlled-purpose", "stop",
                    new AiUsage(Optional.empty(), Optional.empty())));
            }
        };
    }

    private void source(int count) throws Exception {
        for (int i = 0; i < count; i++) {
            var directory = count == 1 ? selectedSourceDirectory() : selectedSourceDirectory().resolve("component" + i);
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("go.mod"), "module example.test/component" + i + "\n\ngo 1.24\n");
            Files.writeString(directory.resolve("go.sum"), "example.test/dependency v1 h1:fixture\n");
            Files.writeString(directory.resolve("main.go"), "package main\nfunc main() {}\n");
            Files.writeString(directory.resolve("windowstolinux-application.properties"),
                "version=1\nhealth.mode=TCP\n");
        }
    }

    private static <T> OperationResult<T> success(T value) {
        return new OperationResult<>(Outcome.SUCCEEDED, Optional.of(value), Optional.empty());
    }
}
