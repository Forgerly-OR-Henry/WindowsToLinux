package gold.debug.windowstolinux.mod.deployment.ai.review;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.app.ai.model.ModelProtocol;
import gold.debug.windowstolinux.app.ai.model.ModelProtocolCodec;
import gold.debug.windowstolinux.app.ai.model.ModelReasoningLevel;
import gold.debug.windowstolinux.app.ai.model.ModelRequestBudgetException;
import gold.debug.windowstolinux.appapi.ai.model.*;
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.action.*;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson;
import gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceArchiveReview;
import gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentToolchainDiagnostics;
import gold.debug.windowstolinux.mod.deployment.linux.command.contract.DeploymentCommandReview;
import gold.debug.windowstolinux.mod.deployment.linux.command.dispatch.DeploymentSendGuard;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentHelperInstallerScript;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelper;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentInstallerEvidence;
import gold.debug.windowstolinux.mod.deployment.linux.mirror.DeploymentMirrorRuntime;
import gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentBuildOutputProtocol;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentBuildLimits;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentRemoteWorkspace;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.kind.DeploymentProjectKind;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentRuntimeDefinition;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentHealthCheck;
import gold.debug.windowstolinux.mod.deployment.service.deploy.build.ecosystem.GoBuildWriter;
import gold.debug.windowstolinux.mod.deployment.service.deploy.build.ecosystem.python.PipBuildWriter;
import gold.debug.windowstolinux.mod.deployment.service.deploy.toolchain.DeploymentToolchainDependencies;
import gold.debug.windowstolinux.mod.deployment.service.mirror.DeploymentMirrorTasks;
import gold.debug.windowstolinux.mod.deployment.service.mirror.DownloadPoint;
import gold.debug.windowstolinux.mod.deployment.service.standard.assisted.DeploymentAssistedScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class DeploymentToolchainReviewEvidenceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int R20_SMALLEST_REMAINING_BYTES = 365119;
    private static final int R41_LOCAL_TRACKED_FIRST_BYTES = 104843;
    private static final int R41_TASK_BOARD_TRACKED_FIRST_BYTES = 124690;
    private static final int R41_FILE_TRANSFER_TRACKED_FIRST_BYTES = 118009;
    private static final int R41_TASK_BOARD_TRACKED_PRESSURE = 1048576 - (R41_TASK_BOARD_TRACKED_FIRST_BYTES - R41_LOCAL_TRACKED_FIRST_BYTES);
    private static final int R41_FILE_TRANSFER_TRACKED_PRESSURE = 1048576 - (R41_FILE_TRANSFER_TRACKED_FIRST_BYTES - R41_LOCAL_TRACKED_FIRST_BYTES);
    private static final String PHASE = "toolchainReviewEvidence";
    private static final String RAW_PAGE = "WTL-COMMAND-EVIDENCE-PAGE-1\n";

    @Test
    void nativeInstallerPagesCompleteSourceWhileKeepingActualWorkflowVisible() throws Exception {
        try (var fixture = new MirroredInstallerTestFixture(); var assisted = new DeploymentNativeHelperPackageImpactTest.Assisted(fixture);
             var request = fixture.request(fixture.installer("TRACKED", true))) {
            var intent = fixture.intent("TRACKED", request);
            var continuations = new LinkedHashMap<UUID, String>();
            var bounded = wireBudgetContext(assisted.context, continuations, 0, () -> R20_SMALLEST_REMAINING_BYTES,
                delivered -> {
                    try {
                        if (!fixture.model.requests.isEmpty()) {
                            reply(fixture, verdict("denied-installer", "DENY"));
                            return;
                        }
                        String frame = delivered.messages().stream().filter(DeploymentToolchainReviewEvidenceTest::nodeInitialSupplement)
                            .findFirst().orElseThrow().text();
                        var header = installerRetainedHeader(frame);
                        var firstMissing = header.path("pendingRequired").get(0);
                        var read = read("tight-installer-helper-page", header.path("snapshotSha256").asText(),
                            firstMissing.path("pointer").asText(), firstMissing.path("offset").asInt(), 8000);
                        replyAtTotalBytes(fixture, continuations, 2000, "OPENAI_RESPONSES", read);
                    } catch (Exception failure) {
                        throw new AssertionError(failure);
                    }
                });
            var denied = assertThrows(ApiException.class, () -> DeploymentFixedCommandApproval.approveWithEvidence(
                bounded, assisted.host, intent, request, fixture.task, fixture.model.configurationRevision,
                "AUTOMATIC", false, assisted.scope));
            assertEquals("DEPLOYMENT_COMMAND_REVIEW_DENIED", denied.problem().eventCode());
            assertEquals(0, assisted.hosts.get());
            var initial = fixture.model.requests.getFirst();
            byte[] encoded = wire(initial, 365119, Map.of());
            var facts = (ObjectNode) JSON.readTree(initial.messages().getLast().text());
            assertEquals("currentCommandEvidence", facts.fieldNames().next());
            var authority = facts.at("/currentCommandEvidence/workflowAuthorization");
            assertEquals(DeploymentNativeHelperPackageImpactTest.WORKFLOW, authority.path("frozenWorkflowRequest").asText());
            assertEquals(fixture.task.id().toString(), authority.path("task").asText());
            assertEquals(fixture.server.id(), authority.at("/serverTargets/0").asText());
            var original = new LinkedHashMap<>(fixture.facts("TRACKED", request));
            original.put("currentCommandEvidence", assisted.scope.facts(intent, request, fixture.task));
            var evidence = DeploymentCommandSourceEvidence.project(DeploymentAiJson.facts(original, 1048576),
                intent, request, fixture.layout).orElseThrow();
            var sources = (ObjectNode) facts.at("/completeExecutableInput/sources");
            var exactSources = new LinkedHashMap<String, String>();
            for (String name : List.of("mirrorRuntime", "mirrorBootstrapShell", "managedHelper", "buildEntry")) {
                var descriptor = sources.path(name);
                assertEquals("EVIDENCE_PAGES", descriptor.path("retrieval").asText());
                String source = collect(evidence, descriptor, "installerReviewEvidence");
                assertIdentity(descriptor, source);
                exactSources.put(descriptor.path("sourcePointer").asText(), source);
                sources.put(name, source);
            }
            var segments = facts.at("/completeExecutableInput/segments");
            for (int index = 0; index < segments.size(); index += 2) {
                var segment = (ObjectNode) segments.get(index);
                var literal = segment.path("text");
                String source = collect(evidence, literal, "installerReviewEvidence");
                assertIdentity(literal, source);
                exactSources.put(literal.path("sourcePointer").asText(), source);
                segment.put("text", source);
            }
            assertArrayEquals(request.stdin(), DeploymentInstallerEvidence.reconstruct(facts.path("completeExecutableInput"))
                .getBytes(StandardCharsets.UTF_8));
            var installer = facts.path("installerReviewEvidence");
            var descriptor = installer.path("installerSelection");
            String excerpt = initial.messages().get(1 + descriptor.path("ordinal").asInt()).text()
                .substring(descriptor.path("headerCharacters").asInt());
            assertIdentity(descriptor, excerpt);
            assertTrue(excerpt.contains("require_root() {") && excerpt.contains("require_deployer() {"));
            assertTrue(excerpt.contains("probe)\n        [ \"$#\" -eq 0 ] || reject probe-arguments"));
            assertTrue(installer.path("selectedInstallerRanges").has(4));
            assertTrackedInstallerExcerpts(installer, excerpt);
            for (var range : installer.path("selectedInstallerRanges")) {
                String source = exactSources.get(range.path("sourcePointer").asText());
                int offset = range.path("offset").asInt(), next = range.path("next").asInt();
                int start = range.path("startCharacter").asInt();
                assertEquals(source.substring(offset, next), excerpt.substring(start, start + next - offset));
            }
            assertTrue(excerpt.endsWith(exactSources.get("/completeExecutableInput/sources/buildEntry")));
            var coverage = assertInstallerVisibleRanges(initial, installer, exactSources);
            String runtime = "/completeExecutableInput/sources/mirrorRuntime";
            assertEquals(Map.of(0, exactSources.get(runtime).length()), coverage.get(runtime));
            String helper = "/completeExecutableInput/sources/managedHelper";
            assertNotEquals(Map.of(0, exactSources.get(helper).length()), coverage.get(helper));
            var omitted = DeploymentEvidencePages.missing(exactSources.get(helper), 0, exactSources.get(helper).length(),
                coverage.get(helper)).getFirst();
            int offset = (int) omitted.get("offset");
            assertEquals(2, fixture.model.requests.size());
            var continuation = fixture.model.requests.getLast();
            assertTrue(wire(continuation, R20_SMALLEST_REMAINING_BYTES, continuations).length <= R20_SMALLEST_REMAINING_BYTES);
            assertInstallerReturnedPage(continuation, installer.path("snapshotSha256").asText(), helper, offset,
                exactSources.get(helper).substring(offset, offset + 8000));
            assertInstallerReadReceipts(evidence, JSON.readTree(evidence.messages().getLast().text()).path("installerReviewEvidence"),
                exactSources);
            System.out.printf("INSTALLER_REVIEW_WIRE bytes=%d ceiling=%d%n", encoded.length, 365119);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"DENY", "APPROVE"})
    void r47TrackedInstallerReusesMeasuredResponsesFrameAfterShrinkingAndPreservesSourceGate(String verdict) throws Exception {
        int factsCharacters = 31935;
        int[] ceilings = {1048576, 900869, 688218, 688218};
        int[] attempts = new int[ceilings.length];
        try (var fixture = new MirroredInstallerTestFixture(); var assisted = new DeploymentNativeHelperPackageImpactTest.Assisted(fixture);
             var request = fixture.request(fixture.installer("TRACKED", true))) {
            var action = fixture.intent("TRACKED", request);
            var continuations = new LinkedHashMap<UUID, String>();
            var bounded = wireBudgetContext(assisted.context, continuations, factsCharacters, () -> {
                int delivered = fixture.model.requests.size();
                attempts[delivered]++;
                return ceilings[delivered];
            }, delivered -> {
                try {
                    var facts = JSON.readTree(delivered.messages().getLast().text());
                    var manifest = facts.path("installerReviewEvidence");
                    String snapshot = manifest.path("snapshotSha256").asText();
                    int turn = fixture.model.requests.size();
                    var reads = r47InstallerReads(delivered, facts, snapshot, turn);
                    if (reads.isEmpty()) reply(fixture, verdict("r47-installer-independent-verdict", verdict));
                    else replyAtTotalBytes(fixture, continuations, 6000, "OPENAI_RESPONSES", reads.toArray(AiToolCall[]::new));
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            });
            var blocked = assertThrows(ApiException.class, () -> DeploymentFixedCommandApproval.approveWithEvidence(
                bounded, assisted.host, action, request, fixture.task, fixture.model.configurationRevision,
                "AUTOMATIC", false, assisted.scope));
            if (verdict.equals("DENY")) assertEquals("DEPLOYMENT_COMMAND_REVIEW_DENIED", blocked.problem().eventCode());
            else {
                assertEquals("EVIDENCE_INCOMPLETE_VERDICT", blocked.problem().context().get("reviewCode"));
                assertEquals("REQUIRED_SOURCE_MISSING", blocked.problem().context().get("reviewEvidenceBlock"));
            }
            assertEquals(0, assisted.hosts.get());
            assertEquals(ceilings.length, fixture.model.requests.size());
            assertEquals(2, attempts[1], "The first smaller physical allowance must be measured");
            assertEquals(2, attempts[2], "The second smaller physical allowance must be measured");
            assertEquals(1, attempts[3], "The unchanged measured physical allowance must be reused on the next request");
            var executable = JSON.valueToTree(fixture.facts("TRACKED", request)).path("completeExecutableInput");
            var exact = new LinkedHashMap<String, String>();
            for (var leaf : DeploymentInstallerEvidence.readableBodies(executable))
                exact.put("/completeExecutableInput" + leaf, executable.at(leaf).textValue());
            var encodedSizes = new ArrayList<Integer>();
            for (int turn = 0; turn < ceilings.length; turn++) {
                var delivered = fixture.model.requests.get(turn);
                assertTrue(delivered.messages().stream().noneMatch(message -> message.continuation().isPresent()));
                byte[] wire = wireWithR24FactsSize(delivered, factsCharacters, ceilings[turn], continuations);
                encodedSizes.add(wire.length);
                var wireInput = JSON.readTree(wire).path("input");
                for (var message : delivered.messages()) {
                    if (!message.text().startsWith("WTL-COMMAND-")) continue;
                    assertTrue(java.util.stream.StreamSupport.stream(wireInput.spliterator(), false)
                        .anyMatch(item -> item.at("/content/0/text").asText().equals(message.text())));
                }
                var manifest = JSON.readTree(delivered.messages().getLast().text()).path("installerReviewEvidence");
                var coverage = assertInstallerVisibleRanges(delivered, manifest, exact);
                if (turn != 0) {
                    var helper = "/completeExecutableInput/sources/managedHelper";
                    assertFalse(DeploymentEvidencePages.missing(exact.get(helper), 0, exact.get(helper).length(),
                        coverage.get(helper)).isEmpty());
                }
            }
            System.out.printf("R47_INSTALLER_REPLAY verdict=%s confirmedWire=%s ceilings=%s attempts=%s factsCharacters=%d%n",
                verdict, encodedSizes, java.util.Arrays.toString(ceilings), java.util.Arrays.toString(attempts), factsCharacters);
        }
    }

    private static List<AiToolCall> r47InstallerReads(AiRequest delivered, JsonNode facts, String snapshot, int turn) throws Exception {
        var segments = "/completeExecutableInput/segments/";
        if (turn == 0) {
            var reads = new ArrayList<>(List.of(
                read("r47-literal-0", snapshot, segments + "0/text", 0, 8000),
                read("r47-literal-6", snapshot, segments + "6/text", 0, 8000),
                read("r47-literal-6-middle", snapshot, segments + "6/text", 8000, 8000),
                read("r47-literal-6-tail", snapshot, segments + "6/text", 16000, 7679),
                read("r47-literal-4", snapshot, segments + "4/text", 0, 924),
                read("r47-literal-10", snapshot, segments + "10/text", 0, 3111)));
            // The synthetic task has a smaller mirror policy, so only replay the original tail when its offset exists.
            if (facts.at(segments + "0/text/characters").asInt() > 8000)
                reads.add(1, read("r47-literal-0-tail", snapshot, segments + "0/text", 8000, 415));
            return reads;
        }
        if (turn == 1) {
            var reads = new ArrayList<AiToolCall>();
            for (int index = 0; index < 8; index++)
                reads.add(read("r47-helper-page-" + index, snapshot, "/completeExecutableInput/sources/managedHelper",
                    544476 + index * 8000, 8000));
            return reads;
        }
        if (turn == 2) {
            // Hold the last observed ceiling for one further missing range to check reuse after a successful rebase.
            var frame = delivered.messages().get(delivered.messages().size() - 2).text();
            var pending = installerRetainedHeader(frame).path("pendingRequired").get(0);
            assertNotNull(pending);
            int offset = pending.path("offset").asInt();
            return List.of(read("r47-missing-page", snapshot, pending.path("pointer").asText(),
                offset, Math.min(8000, pending.path("next").asInt() - offset)));
        }
        return List.of();
    }

    private static void assertTrackedInstallerExcerpts(JsonNode installer, String excerpt) {
        var selected = installer.path("selectedInstallerRanges");
        for (var stage : Map.of(
                "installerPackageTransaction", Map.entry(6, "wtl_apt -o DPkg::Lock::Timeout=300 install"),
                "installerExclusiveStartedReceipt", Map.entry(6, "( set -C; printf '%s' "),
                "installerHelperPayloadWrite", Map.entry(6, "helper_tmp=$(/usr/bin/mktemp "),
                "installerBuildEntryPayloadWrite", Map.entry(8, " > \"$helper_tmp\"\nprintf '%s' "),
                "installerPayloadDigestChecks", Map.entry(10, "installer_verify_executable \"$helper_tmp\" "),
                "installerTerminalReceiptReplacement", Map.entry(10, "/usr/bin/mv -fT -- \"$bootstrap_terminal\" \"$bootstrap_receipt\"")).entrySet()) {
            var range = java.util.stream.StreamSupport.stream(selected.spliterator(), false)
                .filter(node -> node.path("section").asText().equals(stage.getKey())).findFirst().orElseThrow();
            assertEquals("/completeExecutableInput/segments/" + stage.getValue().getKey() + "/text", range.path("sourcePointer").asText());
            int start = range.path("startCharacter").asInt();
            assertTrue(excerpt.substring(start, start + range.path("next").asInt() - range.path("offset").asInt())
                .contains(stage.getValue().getValue()), stage.getKey());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {365119, 1048576, R41_TASK_BOARD_TRACKED_PRESSURE, R41_FILE_TRANSFER_TRACKED_PRESSURE})
    void nativeTrackedInstallerApprovalRequiresCompleteOriginalSourceAtPhysicalCeiling(int ceiling) throws Exception {
        try (var fixture = new MirroredInstallerTestFixture(); var assisted = new DeploymentNativeHelperPackageImpactTest.Assisted(fixture);
             var request = fixture.request(fixture.installer("TRACKED", true))) {
            int r41EarlyAllowance = Math.min(1048576, 1_500_000 - 65_536 - 70_657);
            boolean complete = ceiling == r41EarlyAllowance || ceiling == R41_TASK_BOARD_TRACKED_PRESSURE
                || ceiling == R41_FILE_TRANSFER_TRACKED_PRESSURE;
            var intent = fixture.intent("TRACKED", request);
            var context = wireBudgetContext(assisted.context, Map.of(), 0, () -> ceiling);
            Callable<OperationResult<ApprovalGrant>> approve = () -> DeploymentFixedCommandApproval.approveWithEvidence(
                context, assisted.host, intent, request, fixture.task, fixture.model.configurationRevision,
                "AUTOMATIC", false, assisted.scope);
            reply(fixture, verdict("candidate-installer-approve", "APPROVE"));
            if (complete) {
                assertEquals(TaskResult.SUCCEEDED, approve.call().outcome());
                assertEquals(List.of(intent), assisted.grantedIntents);
            } else {
                var blocked = assertThrows(ApiException.class, approve::call);
                assertEquals("EVIDENCE_INCOMPLETE_VERDICT", blocked.problem().context().get("reviewCode"));
                assertEquals("REQUIRED_SOURCE_MISSING", blocked.problem().context().get("reviewEvidenceBlock"));
                assertEquals(0, assisted.hosts.get());
            }
            assertEquals(1, fixture.model.requests.size(), "Only encoded requests under the physical ceiling reach the fixture model");
            var initial = fixture.model.requests.getFirst();
            var installer = JSON.readTree(initial.messages().getLast().text()).path("installerReviewEvidence");
            var exact = new LinkedHashMap<String, String>();
            var executable = JSON.valueToTree(fixture.facts("TRACKED", request)).path("completeExecutableInput");
            for (var leaf : DeploymentInstallerEvidence.readableBodies(executable))
                exact.put("/completeExecutableInput" + leaf, executable.at(leaf).textValue());
            var coverage = assertInstallerVisibleRanges(initial, installer, exact);
            var missing = exact.entrySet().stream().filter(entry -> !entry.getValue().isEmpty())
                .filter(entry -> !Map.of(0, entry.getValue().length()).equals(coverage.get(entry.getKey())))
                .map(Map.Entry::getKey).toList();
            assertEquals(complete ? List.of() : List.of("/completeExecutableInput/sources/managedHelper"), missing);
            int encoded = wire(initial, ceiling, Map.of()).length;
            System.out.printf("R42_INSTALLER_COVERAGE encoded=%d ceiling=%d complete=%b helperCharacters=%d runtimeCharacters=%d%n",
                encoded, ceiling, complete, exact.get("/completeExecutableInput/sources/managedHelper").length(),
                exact.get("/completeExecutableInput/sources/mirrorRuntime").length());
            if (ceiling == R41_TASK_BOARD_TRACKED_PRESSURE || ceiling == R41_FILE_TRANSFER_TRACKED_PRESSURE)
                System.out.printf("R42_INSTALLER_SYNTHETIC_PRESSURE reservedEncodedBytes=%d finalPhysicalCeiling=%d%n",
                    1048576 - ceiling, 1048576);
            if (complete) {
                String runtime = exact.get("/completeExecutableInput/sources/mirrorRuntime");
                String shell = exact.get("/completeExecutableInput/sources/mirrorBootstrapShell");
                String assignment = "BOOTSTRAP_SHELL = ";
                String embedded = runtime.lines().filter(line -> line.startsWith(assignment)).findFirst().orElseThrow();
                String decoded = JSON.readTree(embedded.substring(assignment.length())).textValue();
                assertTrue(shell.endsWith(decoded + "\n"));
                System.out.printf("R42_INSTALLER_FIXED_TRANSFORM sharedDecodedShellBytes=%d embeddedRuntimeJsonBytes=%d shellBytes=%d factsCharacters=%d%n",
                    decoded.getBytes(StandardCharsets.UTF_8).length, embedded.substring(assignment.length()).getBytes(StandardCharsets.UTF_8).length,
                    shell.getBytes(StandardCharsets.UTF_8).length, initial.messages().getLast().text().length());
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"APPROVE,459292,809654", "DENY,478459,811143", "DENY,451362,810883"})
    void readyTrackedInstallerRequestsFormalVerdictAfterFirstRedundantRebasedRead(
            String decision, int observedBeforeVerdict, int observedOpenBytes) throws Exception {
        try (var fixture = new MirroredInstallerTestFixture(); var assisted = new DeploymentNativeHelperPackageImpactTest.Assisted(fixture)) {
            String installer = readyTrackedInstaller(fixture);
            var executable = JSON.valueToTree(DeploymentInstallerEvidence.describe(installer, fixture.layout));
            var exact = new LinkedHashMap<String, String>();
            for (String leaf : DeploymentInstallerEvidence.readableBodies(executable))
                exact.put("/completeExecutableInput" + leaf, executable.at(leaf).textValue());
            int earlyBound = 1_500_000 - 65_536 - observedBeforeVerdict;
            var bounded = wireBudgetContext(assisted.context, Map.of(), 0,
                () -> fixture.model.requests.size() < 2 ? 1_048_576 : earlyBound, delivered -> {
                    try {
                        int turn = fixture.model.requests.size();
                        var manifest = JSON.readTree(delivered.messages().getLast().text()).path("installerReviewEvidence");
                        assertFalse(manifest.has("runtimeShellReconstruction"));
                        assertFalse(manifest.path("completeSourceRequired").isEmpty());
                        var visible = assertInstallerVisibleRanges(delivered, manifest, exact);
                        exact.forEach((pointer, source) -> {
                            if (!source.isEmpty()) assertEquals(Map.of(0, source.length()), visible.get(pointer));
                        });
                        assertEquals(0, assisted.hosts.get());
                        if (turn < 2) {
                            assertTrue(delivered.requiredToolName().isEmpty());
                            fixture.model.replies.add(success(mockResponses(read("ready-reread-" + turn,
                                manifest.path("snapshotSha256").asText(), "/completeExecutableInput/sources/managedHelper", 0, 8000))));
                        } else {
                            assertEquals(2, turn);
                            assertEquals(Optional.of("submit_result"), delivered.requiredToolName());
                            reply(fixture, verdict("ready-formal-verdict", decision));
                        }
                    } catch (Exception failure) {
                        throw new AssertionError(failure);
                    }
                });
            if (decision.equals("APPROVE")) {
                assertEquals(TaskResult.SUCCEEDED, installReadyTrackedFixture(fixture, assisted, bounded, installer).outcome());
                assertEquals(1, assisted.hosts.get());
            } else {
                var denied = assertThrows(ApiException.class, () -> installReadyTrackedFixture(fixture, assisted, bounded, installer));
                assertEquals("DEPLOYMENT_COMMAND_REVIEW_DENIED", denied.problem().eventCode());
                assertEquals(0, assisted.hosts.get());
            }
            assertEquals(3, fixture.model.requests.size());
            var preceding = fixture.model.requests.get(1);
            var formal = fixture.model.requests.getLast();
            assertEquals(preceding.messages().subList(1, preceding.messages().size()), formal.messages().subList(1, formal.messages().size()));
            assertEquals(preceding.timeout(), formal.timeout());
            assertEquals(preceding.maxOutputTokens(), formal.maxOutputTokens());
            assertEquals(0, formal.generationRecoveryAttempts());
            var encoded = wire(formal, earlyBound, Map.of());
            var nativeRequest = JSON.readTree(encoded);
            assertEquals("submit_result", nativeRequest.at("/tool_choice/name").asText());
            assertEquals(1, nativeRequest.path("tools").size());
            for (int index = 1; index < formal.messages().size(); index++)
                assertEquals(formal.messages().get(index).text(), nativeRequest.at("/input/" + index + "/content/0/text").asText());
            int projectedBytes = observedOpenBytes + encoded.length - wire(preceding, 1_048_576, Map.of()).length;
            assertTrue(projectedBytes <= earlyBound);
            System.out.printf("R57_READY_INSTALLER decision=%s encoded=%d projected=%d ceiling=%d observed=%d%n",
                decision, encoded.length, projectedBytes, earlyBound, observedBeforeVerdict);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-required", "new-range", "stale-snapshot", "invalid-cursor", "empty-page"})
    void readyTrackedInstallerKeepsBrowsingForAReadThatCannotAdvanceToFormalVerdict(String scenario) throws Exception {
        try (var fixture = new MirroredInstallerTestFixture(); var assisted = new DeploymentNativeHelperPackageImpactTest.Assisted(fixture)) {
            String installer = readyTrackedInstaller(fixture);
            int limit = scenario.equals("missing-required") ? 365119 : 1_048_576;
            String expectedBlock = switch (scenario) {
                case "missing-required" -> "REQUIRED_SOURCE_MISSING";
                case "stale-snapshot", "invalid-cursor" -> "READ_REJECTED";
                default -> "";
            };
            var bounded = wireBudgetContext(assisted.context, Map.of(), 0, () -> limit, delivered -> {
                try {
                    assertTrue(delivered.requiredToolName().isEmpty());
                    int turn = fixture.model.requests.size();
                    var facts = JSON.readTree(delivered.messages().getLast().text());
                    var manifest = facts.path("installerReviewEvidence");
                    String snapshot = manifest.path("snapshotSha256").asText();
                    String pointer = "/completeExecutableInput/sources/managedHelper";
                    int end = facts.at(pointer).path("characters").asInt();
                    if (turn == 0) {
                        fixture.model.replies.add(success(mockResponses(read("ready-initial", snapshot, pointer, 0, 64))));
                    } else if (turn == 1) {
                        var repeat = read("ready-repeat", snapshot, pointer, 0, 64);
                        var next = switch (scenario) {
                            case "new-range" -> read("ready-new", snapshot, "/exactAction", 0, 64);
                            case "stale-snapshot" -> read("ready-stale", "0".repeat(64), pointer, 0, 64);
                            case "invalid-cursor" -> read("ready-invalid", snapshot, pointer, end + 1, 64);
                            case "empty-page" -> read("ready-empty", snapshot, pointer, end, 64);
                            default -> repeat;
                        };
                        fixture.model.replies.add(success(next == repeat ? mockResponses(repeat) : mockResponses(repeat, next)));
                    } else {
                        assertEquals(2, turn);
                        var header = installerRetainedHeader(delivered.messages().get(delivered.messages().size() - 2).text());
                        switch (scenario) {
                            case "new-range" -> assertTrue(header.path("ranges").findValuesAsText("pointer").contains("/exactAction"));
                            case "stale-snapshot" -> assertEquals("SNAPSHOT_MISMATCH", header.at("/latestReads/1/rejectionCode").asText());
                            case "invalid-cursor" -> assertEquals("CURSOR_INVALID", header.at("/latestReads/1/rejectionCode").asText());
                            case "missing-required" -> assertFalse(header.path("pendingRequired").isEmpty());
                            case "empty-page" -> assertEquals(end, header.at("/latestReads/1/next").asInt());
                        }
                        reply(fixture, verdict("ready-browse-verdict", expectedBlock.isEmpty() ? "DENY" : "APPROVE"));
                    }
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            });
            var rejected = assertThrows(ApiException.class, () -> installReadyTrackedFixture(fixture, assisted, bounded, installer));
            if (expectedBlock.isEmpty()) assertEquals("DEPLOYMENT_COMMAND_REVIEW_DENIED", rejected.problem().eventCode());
            else {
                assertEquals("EVIDENCE_INCOMPLETE_VERDICT", rejected.problem().context().get("reviewCode"));
                assertEquals(expectedBlock, rejected.problem().context().get("reviewEvidenceBlock"));
            }
            assertEquals(3, fixture.model.requests.size());
            assertEquals(0, assisted.hosts.get());
        }
    }

    private static String readyTrackedInstaller(MirroredInstallerTestFixture fixture) throws Exception {
        var point = DownloadPoint.APT_UBUNTU;
        String bodyDigest = DeploymentHelperInstallerScript.readyBodyDigest(fixture.layout, point);
        return DeploymentHelperInstallerScript.renderTrackedReady(fixture.task.id(), fixture.operation,
            MirroredInstallerTestFixture.sha(fixture.server.id()), bodyDigest, fixture.layout, point);
    }

    private static OperationResult<CommandResult> installReadyTrackedFixture(MirroredInstallerTestFixture fixture,
            DeploymentNativeHelperPackageImpactTest.Assisted assisted, ModContext context, String installer) throws Exception {
        var session = (RemoteSession) java.lang.reflect.Proxy.newProxyInstance(DeploymentToolchainReviewEvidenceTest.class.getClassLoader(),
            new Class<?>[]{RemoteSession.class}, (proxy, method, arguments) -> {
                assertEquals("execute", method.getName());
                assertTrue(assisted.grants.contains(((ApprovalGrant) arguments[1]).id()));
                assertArrayEquals(installer.getBytes(StandardCharsets.UTF_8), ((CommandRequest) arguments[0]).stdin());
                return new OperationResult<>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
            });
        var helper = new DeploymentManagedHelper(session, assisted.host, fixture.server, fixture.task, fixture.model.configurationRevision,
            DeploymentSendGuard.NONE, (intent, request, task) -> DeploymentFixedCommandApproval.approveWithEvidence(
                context, assisted.host, intent, request, task, fixture.model.configurationRevision, "AUTOMATIC", false, assisted.scope));
        return helper.installTracked(installer.getBytes(StandardCharsets.UTF_8), () -> { });
    }

    private static Map<String, TreeMap<Integer, Integer>> assertInstallerVisibleRanges(
            AiRequest request, JsonNode installer, Map<String, String> exactSources) throws Exception {
        var bySource = new LinkedHashMap<String, TreeMap<Integer, Integer>>();
        var supplied = new LinkedHashMap<String, char[]>();
        exactSources.forEach((pointer, source) -> supplied.put(pointer, new char[source.length()]));
        var selection = installer.path("installerSelection");
        String excerpts = request.messages().get(1 + selection.path("ordinal").asInt()).text()
            .substring(selection.path("headerCharacters").asInt());
        for (var range : installer.path("selectedInstallerRanges"))
            assertInstallerRange(exactSources, bySource, supplied, range.path("sourcePointer").asText(), range, excerpts,
                range.path("startCharacter").asInt());
        String frame = request.messages().stream().map(AiMessage::text)
            .filter(text -> text.startsWith("WTL-COMMAND-RETAINED-SOURCE-1\n")).findFirst().orElseThrow();
        int end = frame.indexOf('\n', frame.indexOf('\n') + 1);
        var header = installerRetainedHeader(frame);
        assertEquals(installer.path("snapshotSha256"), header.path("snapshotSha256"));
        String source = frame.substring(end + 1);
        int position = 0;
        for (var range : header.path("ranges"))
            position += assertInstallerRange(exactSources, bySource, supplied, range.path("pointer").asText(), range, source, position);
        assertEquals(source.length(), position);
        if (exactSources.containsKey("/completeExecutableInput/sources/mirrorRuntime")) {
            assertTrue(header.path("runtimeShellReconstructible").asBoolean());
            var reconstruction = installer.path("runtimeShellReconstruction");
            assertEquals("JSON_STRING", reconstruction.path("encoding").asText());
            var from = reconstruction.path("source");
            var target = reconstruction.path("target");
            String shell = from.path("pointer").asText(), runtime = target.path("pointer").asText();
            int shellOffset = from.path("offset").asInt(), shellNext = from.path("next").asInt();
            var shellCoverage = bySource.get(shell).floorEntry(shellOffset);
            assertNotNull(shellCoverage);
            assertTrue(shellCoverage.getValue() >= shellNext, "Runtime reconstruction requires the actual shell text in this request");
            String serialized = JSON.writeValueAsString(new String(supplied.get(shell), shellOffset, shellNext - shellOffset));
            assertEquals(target.path("sha256").asText(), DeploymentEvidencePages.sha256(serialized));
            assertEquals(target.path("utf8Bytes").asInt(), serialized.getBytes(StandardCharsets.UTF_8).length);
            assertEquals(serialized.length(), assertInstallerRange(exactSources, bySource, supplied, runtime, target, serialized, 0));
        }
        var pending = JSON.createArrayNode();
        for (var obligation : installer.path("completeSourceRequired")) {
            String pointer = obligation.path("pointer").asText();
            String exact = exactSources.get(pointer);
            assertEquals(0, obligation.path("offset").asInt());
            assertEquals(exact.length(), obligation.path("next").asInt());
            for (var missing : DeploymentEvidencePages.missing(exact, 0, exact.length(),
                    bySource.getOrDefault(pointer, new TreeMap<>()))) {
                pending.addObject().put("pointer", pointer).put("offset", (int) missing.get("offset"))
                    .put("next", (int) missing.get("next"));
            }
        }
        if (pending.isEmpty()) {
            assertFalse(header.has("pendingRequired"));
            var facts = JSON.readTree(request.messages().getLast().text());
            var executable = facts.path("completeExecutableInput");
            for (var leaf : supplied.entrySet()) {
                String pointer = leaf.getKey();
                int slash = pointer.lastIndexOf('/');
                ((ObjectNode) facts.at(pointer.substring(0, slash))).put(pointer.substring(slash + 1), new String(leaf.getValue()));
            }
            String stdin = DeploymentInstallerEvidence.reconstruct(executable);
            DeploymentInstallerEvidence.verify(executable, stdin);
            assertTrue(facts.path("exactAction").asText().contains("stdinSha256=" + DeploymentEvidencePages.sha256(stdin)));
        } else assertEquals(pending, header.path("pendingRequired"));
        return bySource;
    }

    private static JsonNode installerRetainedHeader(String frame) throws IOException {
        int start = frame.indexOf('\n') + 1;
        return JSON.readTree(frame.substring(start, frame.indexOf('\n', start)));
    }

    private static void assertInstallerReturnedPage(AiRequest request, String snapshot, String pointer, int offset,
                                                     String expected) throws IOException {
        String frame = request.messages().stream().map(AiMessage::text)
            .filter(text -> text.startsWith("WTL-COMMAND-RETAINED-SOURCE-1\n")).findFirst().orElseThrow();
        var header = installerRetainedHeader(frame);
        assertEquals(snapshot, header.path("snapshotSha256").asText());
        var receipt = header.path("latestReads").get(0);
        assertFalse(receipt.path("rejected").asBoolean());
        assertEquals(pointer, receipt.path("pointer").asText());
        assertEquals(offset, receipt.path("offset").asInt());
        assertEquals(offset + expected.length(), receipt.path("next").asInt());
        int position = frame.indexOf('\n', frame.indexOf('\n') + 1) + 1;
        for (var range : header.path("ranges")) {
            int begin = range.path("offset").asInt(), end = range.path("next").asInt();
            if (pointer.equals(range.path("pointer").asText()) && begin <= offset && end >= offset + expected.length()) {
                assertEquals(expected, frame.substring(position + offset - begin, position + offset - begin + expected.length()));
                return;
            }
            position += end - begin;
        }
        fail("The original requested installer page is absent from the bounded response");
    }

    private static int assertInstallerRange(Map<String, String> exactSources, Map<String, TreeMap<Integer, Integer>> bySource,
            Map<String, char[]> supplied, String pointer, JsonNode range, String text, int position) {
        int offset = range.path("offset").asInt(), next = range.path("next").asInt();
        assertEquals(exactSources.get(pointer).substring(offset, next), text.substring(position, position + next - offset));
        var visible = bySource.computeIfAbsent(pointer, ignored -> new TreeMap<>());
        assertEquals(List.of(Map.of("offset", offset, "next", next, "text", exactSources.get(pointer).substring(offset, next))),
            DeploymentEvidencePages.missing(exactSources.get(pointer), offset, next, visible), "Source bytes must appear once");
        DeploymentEvidencePages.remember(visible, offset, next);
        text.getChars(position, position + next - offset, supplied.get(pointer), offset);
        return next - offset;
    }

    private static void assertInstallerReadReceipts(DeploymentCommandSourceEvidence evidence, JsonNode installer,
                                                    Map<String, String> exactSources) throws Exception {
        String snapshot = installer.path("snapshotSha256").asText();
        var example = installer.path("readExample");
        String pointer = example.path("pointer").asText();
        String source = exactSources.get(pointer);
        assertNotNull(source);
        assertEquals(snapshot, example.path("snapshotSha256").asText());
        assertEquals("/completeExecutableInput/sources/mirrorRuntime", pointer);
        assertEquals(0, example.path("offset").asInt());
        var reader = evidence.reader(8);
        var reply = mockResponses(new AiToolCall("canonical-read", "evidence", example.toString()),
            read("other-snapshot", "0".repeat(64), pointer, 0, 12),
            read("unknown-pointer", snapshot, "/private-placeholder", 0, 12),
            read("invalid-cursor", snapshot, pointer, source.length() + 1, 12),
            read("invalid-parameters", snapshot, pointer, 0, 8001));
        var results = reader.read(reply);
        assertFalse(results.getFirst().error(), results.getFirst().text());
        var expected = List.of("SNAPSHOT_MISMATCH", "POINTER_UNAVAILABLE", "CURSOR_INVALID", "ARGUMENTS_INVALID");
        for (int i = 1; i < results.size(); i++) {
            assertTrue(results.get(i).error());
            assertTrue(results.get(i).text().contains(expected.get(i - 1)), results.get(i).text());
        }
        var messages = new ArrayList<>(evidence.messages());
        reader.continueReview(messages, reply, results);
        String retained = messages.get(messages.size() - 2).text();
        assertFalse(retained.contains("private-placeholder"));
        var header = installerRetainedHeader(retained);
        var receipts = header.path("latestReads");
        assertEquals(expected, receipts.findValuesAsText("rejectionCode"));
        assertFalse(header.has("runtimeShellReconstructible"), "A staged recipe without its shell bytes cannot cover the runtime");
        var target = installer.path("runtimeShellReconstruction").path("target");
        int targetOffset = target.path("offset").asInt();
        assertTrue(header.path("pendingRequired").findValuesAsText("pointer").contains(pointer));
        reader.read(response(Optional.empty(), verdict("reject-incomplete-reads", "APPROVE")));
        assertEquals(Optional.of("READ_REJECTED"), reader.approvalEvidenceBlock());

        var explicitReader = evidence.reader(1);
        var initial = new ArrayList<>(evidence.messages());
        explicitReader.offerInitialSource(initial);
        assertTrue(installerRetainedHeader(initial.get(initial.size() - 2).text()).path("runtimeShellReconstructible").asBoolean());
        var direct = explicitReader.read(mockResponses(read("explicit-runtime-literal", snapshot, pointer, targetOffset, 8000))).getFirst();
        assertFalse(direct.error());
        String page = direct.text();
        assertTrue(page.startsWith(RAW_PAGE));
        assertEquals(source.substring(targetOffset, targetOffset + 8000), page.substring(page.indexOf('\n', RAW_PAGE.length()) + 1));
    }

    @Test
    void overlappingPagesDeliverAllAndOnlyMissingRangesFromTheSameSource() {
        String body = "abcd编译😀".repeat(400);
        var retained = new TreeMap<Integer, Integer>();
        assertEquals(body.substring(0, 800), page(body, retained, 0, 800).get("text"));
        assertEquals(body.substring(1200, 1600), page(body, retained, 1200, 400).get("text"));
        var overlap = page(body, retained, 400, 1600);
        assertFalse(overlap.containsKey("text"));
        assertEquals(List.of(Map.of("offset", 800, "next", 1200, "text", body.substring(800, 1200)),
            Map.of("offset", 1600, "next", 2000, "text", body.substring(1600, 2000))), overlap.get("newRanges"));
        assertEquals(List.of(), page(body, retained, 0, 2000).get("newRanges"));
        assertEquals(Map.of(0, 2000), retained);
    }

    private static Map<String, Object> page(String body, TreeMap<Integer, Integer> retained, int offset, int count) {
        return DeploymentEvidencePages.retainAndDescribe(body, DeploymentEvidencePages.page(body, offset, count), retained);
    }

    @Test
    void dependencyReviewRejectsAWrapperForAnotherTaskOrBudgetBeforeModelAndHost() throws Exception {
        try (var fixture = new MirroredInstallerTestFixture(); var other = new MirroredInstallerTestFixture();
             var original = request("dependencies", fixture)) {
            String body = DeploymentMirrorTasks.originalCommand(original.script());
            var protocol = new DeploymentFixedCommandProtocol(fixture.context);
            for (String wrapper : List.of(DeploymentMirrorTasks.preparationCommand(body, other.task, 120),
                DeploymentMirrorTasks.preparationCommand(body, fixture.task, 119))) {
                try (var request = new CommandRequest(wrapper, original.stdin(), original.timeout(), original.outputLimitBytes())) {
                    var intent = intent("dependencies", fixture, request);
                    assertThrows(IllegalArgumentException.class,
                        () -> protocol.inspect(intent, request, fixture.layout, fixture.task));
                }
            }
            assertTrue(fixture.model.requests.isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"prepare", "bind", "dependencies"})
    void responsesPageExactSourceAndAcceptEvidenceBatchesBeforeFreshHostAuthorization(String kind) throws Exception {
        try (var fixture = new MirroredInstallerTestFixture(); var assisted = new DeploymentNativeHelperPackageImpactTest.Assisted(fixture);
             var request = request(kind, fixture)) {
            var intent = intent(kind, fixture, request);
            var protocol = new DeploymentFixedCommandProtocol(fixture.context);
            var base = protocol.inspect(intent, request, fixture.layout, fixture.task);
            DeploymentCurrentCommandEvidence observed = kind.equals("prepare") ? assisted.scope : (action, command, task) -> {
                var current = new LinkedHashMap<String, Object>(assisted.scope.effectFacts(action, task));
                if (kind.equals("dependencies")) current.put("currentNativeBuildPrerequisites", Map.of("fixedTransaction",
                    DeploymentToolchainDependencies.inspect(action, command, fixture.layout)));
                return current;
            };
            Callable<OperationResult<ApprovalGrant>> review = kind.equals("prepare")
                ? () -> ((DeploymentCommandReview) assisted.scope.interaction()).approveCommand(intent, request, fixture.task)
                : () -> DeploymentFixedCommandApproval.approveWithEvidence(assisted.context, assisted.host, intent, request,
                    fixture.task, fixture.model.configurationRevision, "AUTOMATIC", false, observed);

            reply(fixture, verdict("denied", "DENY"));
            var denied = assertThrows(ApiException.class, review::call);
            assertEquals("DEPLOYMENT_COMMAND_REVIEW_DENIED", denied.problem().eventCode());
            assertEquals(0, assisted.hosts.get());
            var initial = fixture.model.requests.getFirst();
            byte[] wire = wire(initial, R20_SMALLEST_REMAINING_BYTES, Map.of());
            var projected = JSON.readTree(initial.messages().getLast().text());
            var expected = new LinkedHashMap<>(base);
            expected.put("currentCommandEvidence", observed.facts(intent, request, fixture.task));
            expected.put("authorizationPolicy", "AUTOMATIC");
            expected.put("effectSummary", intent.effectSummary());
            expected.put("presentationLocale", "en");
            expected.put("commandAuthorizationStage", Map.of("phase", "INDEPENDENT_REVIEW_BEFORE_HOST_GRANT",
                "hostGrantRequestedOnlyAfterApprove", true, "hostGrantIssuedByThisBoundary", false, "exactHostGrantRequiredBeforeDispatch", true));
            String original = DeploymentAiJson.facts(expected, 1048576);
            var evidence = DeploymentCommandSourceEvidence.project(original, intent, request, fixture.layout).orElseThrow();
            var mirror = kind.equals("dependencies") ? projected.at("/" + PHASE + "/mirrorRuntime") : projected.at("/mirrorTransport/runtimeSource");
            String mirrorPointer = mirror.path("sourcePointer").asText();
            assertEquals("EVIDENCE_PAGES", mirror.path("retrieval").asText());
            String mirrorSource = collect(evidence, mirror, PHASE);
            assertIdentity(mirror, mirrorSource);
            String mirrorAnchor = kind.equals("prepare") ? "def validate_url(" : "class MirrorPolicy:";
            assertTrue(mirrorSource.startsWith(mirrorAnchor, mirror.path("locations").path(mirrorAnchor).asInt(-1)));
            var sources = new LinkedHashMap<>(Map.of(mirrorPointer, mirrorSource));
            var shell = projected.at("/" + PHASE + "/mirrorBootstrapShell");
            assertEquals("EVIDENCE_PAGES", shell.path("retrieval").asText());
            String shellSource = collect(evidence, shell, PHASE);
            assertIdentity(shell, shellSource);
            sources.put(shell.path("sourcePointer").asText(), shellSource);
            var policy = projected.at("/" + PHASE + "/mirrorPolicySource");
            if (!kind.equals("dependencies")) {
                assertEquals("EVIDENCE_PAGES", policy.path("retrieval").asText());
                String policySource = collect(evidence, policy, PHASE);
                assertIdentity(policy, policySource);
                assertEquals(DeploymentMirrorTasks.policy(fixture.task), policySource);
                sources.put(policy.path("sourcePointer").asText(), policySource);
                if (kind.equals("prepare")) {
                    var publicPolicy = projected.at("/mirrorTransport/completePublicPolicy");
                    String complete = collect(evidence, publicPolicy, PHASE);
                    assertIdentity(publicPolicy, complete);
                    sources.put(publicPolicy.path("sourcePointer").asText(), complete);
                }
            } else {
                var frame = initial.messages().get(1 + policy.path("ordinal").intValue());
                String inlinePolicy = frame.text().substring(policy.path("headerCharacters").intValue());
                assertIdentity(policy, inlinePolicy);
                assertEquals(DeploymentMirrorTasks.policy(fixture.task), inlinePolicy);
                String snapshot = JSON.readTree(evidence.messages().getLast().text()).at("/" + PHASE + "/snapshotSha256").asText();
                var worker = projected.at("/" + PHASE + "/dependencyWorker");
                var alreadySupplied = evidence.reader(2).read(response(Optional.empty(),
                    read("inline-policy", snapshot, policy.path("sourcePointer").asText(), 0, Math.min(8000, inlinePolicy.length())),
                    read("inline-worker", snapshot, worker.path("sourcePointer").asText(), 0, 8000)));
                for (var reply : alreadySupplied) {
                    assertFalse(reply.error());
                    assertTrue(reply.text().startsWith(RAW_PAGE));
                    var page = pagePayload(reply.text()).path("value");
                    assertFalse(page.has("text"));
                    assertTrue(page.path("newRanges").isArray());
                    assertEquals(0, page.path("newRanges").size());
                }
            }
            if (kind.equals("bind")) {
                String helper = DeploymentManagedHelperBundle.renderScript(fixture.layout);
                var helperDescriptor = projected.at("/" + PHASE + "/installedHelperSource");
                assertIdentity(helperDescriptor, helper);
                var ranges = helperDescriptor.path("selectedRanges");
                assertTrue(ranges.size() > 0);
                var frame = projected.at("/" + PHASE + "/frames/0");
                assertEquals(helperDescriptor.path("selectedRangeFrame"), frame.path("sourcePointer"));
                String frameBody = initial.messages().get(initial.messages().size() - 2).text()
                    .substring(frame.path("headerCharacters").intValue());
                assertIdentity(frame, frameBody);
                for (var range : ranges) {
                    int offset = range.path("offset").intValue();
                    int next = range.path("next").intValue();
                    int start = range.path("startCharacter").intValue();
                    assertEquals(helper.substring(offset, next), frameBody.substring(start, start + next - offset));
                }
                assertTrue(frameBody.contains("def validated_binding"));
                assertTrue(frameBody.contains("def bind("));
                var selected = ranges.get(0);
                String readerSnapshot = JSON.readTree(evidence.messages().getLast().text()).at("/" + PHASE + "/snapshotSha256").asText();
                var supplied = evidence.reader(1).read(response(Optional.empty(), read("supplied",
                    readerSnapshot, helperDescriptor.path("sourcePointer").asText(),
                    selected.path("offset").intValue(), selected.path("next").intValue() - selected.path("offset").intValue()))).getFirst();
                assertFalse(supplied.error());
                var suppliedPage = pagePayload(supplied.text()).path("value");
                assertFalse(suppliedPage.has("text"));
                assertEquals(0, suppliedPage.path("newRanges").size());
            }
            var restored = restore(JSON.readTree(wire), sources);
            assertEquals(JSON.readTree(original), restored);
            assertEquals(intent.exactAction(), restored.path("exactAction").asText());
            if (kind.equals("dependencies"))
                assertArrayEquals(request.stdin(), restored.path("completeExecutableInput").asText().getBytes(StandardCharsets.UTF_8));
            assertEquals(AiPurpose.APPROVAL, initial.purpose());
            assertEquals(65536, request.outputLimitBytes());
            assertEquals(1048576, initial.maxRequestBytes());
            var baselineMessages = List.of(initial.messages().getFirst(), new AiMessage(AiRole.USER,
                DeploymentAiJson.facts(expected, 1048576), List.of()));
            int baseline = wire(copy(initial, baselineMessages, initial.maxRequestBytes()), initial.maxRequestBytes(), Map.of()).length;
            assertTrue(baseline - wire.length > 320000, "Responses encoding should page the fixed mirror source and omit duplicate bytes");
            System.out.printf("TOOLCHAIN_REVIEW_WIRE kind=%s originalFactsCharacters=%d projectedFactsCharacters=%d baselineBytes=%d initialBytes=%d ceiling=%d%n",
                kind, restored.toString().length(), initial.messages().getLast().text().length(), baseline, wire.length, R20_SMALLEST_REMAINING_BYTES);

            String snapshot = projected.at("/" + PHASE + "/snapshotSha256").asText();
            String sourcePointer = kind.equals("dependencies") ? "/completeExecutableInput" : "/" + PHASE + "/installedHelperSource";
            var continuations = new LinkedHashMap<UUID, String>();
            reply(fixture, continuations, 24000, read("stale", "0".repeat(64), "/exactAction", 0));
            reply(fixture, continuations, 6000, read("exact", snapshot, "/exactAction", 0),
                read("source", snapshot, sourcePointer, 0), read("mirror", snapshot, mirrorPointer, 0));
            var reads = new ArrayList<AiToolCall>();
            for (int index = 0; index < 6; index++)
                reads.add(read("range-" + index, snapshot, sourcePointer, index * 8000, 8000));
            reply(fixture, continuations, 6000, reads.toArray(AiToolCall[]::new));
            reply(fixture, continuations, 6000, read("repeat-0", snapshot, sourcePointer, 0, 8000),
                read("repeat-1", snapshot, sourcePointer, 6000, 8000), read("repeat-2", snapshot, sourcePointer, 14000, 8000));
            reply(fixture, verdict("approved", "APPROVE"));
            assertEquals(TaskResult.SUCCEEDED, review.call().outcome());
            assertEquals(List.of(intent), assisted.grantedIntents);
            assertEquals(6, fixture.model.requests.size());
            assertEquals(initial.messages(), fixture.model.requests.get(1).messages(), "Every review starts with only the complete current facts");
            var finalRequest = fixture.model.requests.getLast();
            var pages = finalRequest.messages().stream().flatMap(message -> message.toolResult().stream()).toList();
            assertEquals(13, pages.size());
            assertTrue(pages.get(0).error());
            assertFalse(pages.get(1).error());
            assertFalse(pages.get(2).error());
            assertPage(pages.get(1), snapshot, intent.exactAction());
            String source = kind.equals("dependencies") ? restored.path("completeExecutableInput").asText()
                : DeploymentManagedHelperBundle.renderScript(fixture.layout);
            assertPageRanges(source, pagePayload(pages.get(2).text()).path("value"));
            assertPage(pages.get(3), snapshot, mirrorSource);
            assertEquals("repeat-2", pages.getLast().callId());
            for (var page : pages.subList(10, 13)) {
                var value = pagePayload(page.text()).path("value");
                assertFalse(value.has("text"));
                assertEquals(0, value.path("newRanges").size());
            }
            byte[] continued = wire(finalRequest, R20_SMALLEST_REMAINING_BYTES, continuations);
            var wireInput = JSON.readTree(continued).path("input");
            assertEquals(13, wireInput.findValuesAsText("type").stream().filter("function_call_output"::equals).count());
            System.out.printf("TOOLCHAIN_REVIEW_CONTINUATION kind=%s actualBytes=%d ceiling=%d repeatedSourceCharactersOmitted=24000%n", kind,
                continued.length, R20_SMALLEST_REMAINING_BYTES);

            reply(fixture, read("mixed-evidence", snapshot, mirrorPointer, 0), verdict("mixed-approval", "APPROVE"));
            var mixed = assertThrows(ApiException.class, review::call);
            assertEquals("DEPLOYMENT_COMMAND_REVIEW_INVALID", mixed.problem().eventCode());
            assertEquals(List.of(intent), assisted.grantedIntents);
            var bounded = evidence.reader(2);
            String evidenceSnapshot = JSON.readTree(evidence.messages().getLast().text()).at("/" + PHASE + "/snapshotSha256").asText();
            assertEquals(2, bounded.read(response(Optional.empty(), read("a", evidenceSnapshot, mirrorPointer, 0),
                read("b", evidenceSnapshot, mirrorPointer, 120))).size());
            assertThrows(IOException.class, () -> bounded.read(response(Optional.empty(), read("c", evidenceSnapshot, mirrorPointer, 240))));
            var unique = evidence.reader(16);
            var firstRead = read("reused", evidenceSnapshot, mirrorPointer, 0);
            assertEquals(1, unique.read(response(Optional.empty(), firstRead)).size());
            assertThrows(IOException.class, () -> unique.read(response(Optional.empty(), firstRead)));
            var excessive = new AiToolCall[9];
            for (int index = 0; index < excessive.length; index++)
                excessive[index] = read("excessive-" + index, evidenceSnapshot, mirrorPointer, index * 120);
            if (kind.equals("bind")) {
                assertTrue(unique.read(response(Optional.empty(), excessive)).stream().allMatch(AiToolResult::error));
            } else assertThrows(IOException.class, () -> unique.read(response(Optional.empty(), excessive)));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"csv-inspector", "survey-scoring"})
    void mirroredBindingRetainsR22EvidenceTrafficWithinObservedByteCeilings(String scenario) throws Exception {
        boolean csv = scenario.equals("csv-inspector");
        int ceiling = csv ? 296761 : 334891;
        int[][] offsets = csv
            ? new int[][]{{0, 56000, 76000, 104000, 112000, 144000, 158000, 198000},
                {8000, 16000, 24000, 32000, 40000, 48000, 64000, 84000},
                {72000, 92000, 100000, 120000, 128000, 136000, 152000, 166000}}
            : new int[][]{{198000}, {194000, 236000, 142000}, {543000, 201000, 236000}, {146000, 188000, 216000},
                {183000}, {160811}, {168811}, {176811}};
        int[] encryptedBytes = csv ? new int[]{17400, 8500, 4300}
            : new int[]{8950, 6600, 29000, 12300, 2450, 21100, 2000, 1900};
        try (var fixture = new MirroredInstallerTestFixture(); var assisted = new DeploymentNativeHelperPackageImpactTest.Assisted(fixture);
             var request = request("bind", fixture)) {
            var intent = intent("bind", fixture, request);
            Callable<OperationResult<ApprovalGrant>> review = () -> DeploymentFixedCommandApproval.approveWithEvidence(
                assisted.context, assisted.host, intent, request, fixture.task, fixture.model.configurationRevision,
                "AUTOMATIC", false, (action, command, task) -> assisted.scope.effectFacts(action, task));
            reply(fixture, verdict("initial-denial", "DENY"));
            assertEquals("DEPLOYMENT_COMMAND_REVIEW_DENIED", assertThrows(ApiException.class, review::call).problem().eventCode());
            var initial = fixture.model.requests.getFirst();
            var facts = JSON.readTree(initial.messages().getLast().text());
            String snapshot = facts.at("/" + PHASE + "/snapshotSha256").asText();
            String helper = facts.at("/" + PHASE + "/installedHelperSource/sourcePointer").asText();
            String mirror = facts.at("/mirrorTransport/runtimeSource/sourcePointer").asText();
            var continuations = new LinkedHashMap<UUID, String>();
            int callCount = 0;
            for (int round = 0; round < offsets.length; round++) {
                var calls = new ArrayList<AiToolCall>();
                for (int offset : offsets[round]) {
                    int limit = !csv && offset == 183000 ? 10000 : !csv && offset == 142000 ? 6000 : 8000;
                    calls.add(read("r22-page-" + callCount++, snapshot, csv && offset != 198000 ? mirror : helper, offset, limit));
                }
                reply(fixture, continuations, encryptedBytes[round], calls.toArray(AiToolCall[]::new));
            }
            reply(fixture, verdict("final-denial", "DENY"));
            assertEquals("DEPLOYMENT_COMMAND_REVIEW_DENIED", assertThrows(ApiException.class, review::call).problem().eventCode());
            var last = fixture.model.requests.getLast();
            byte[] encoded;
            try {
                encoded = wire(last, ceiling, continuations);
            } catch (ModelRequestBudgetException tooLarge) {
                throw new AssertionError(scenario + " encoded " + tooLarge.encodedBytes() + " > " + ceiling + " bytes", tooLarge);
            }
            assertThrows(IllegalArgumentException.class, () -> wire(last, encoded.length - 1, continuations));
            var input = JSON.readTree(encoded).path("input");
            var types = input.findValuesAsText("type");
            assertEquals(offsets.length, types.stream().filter("reasoning"::equals).count());
            assertEquals(callCount, types.stream().filter("function_call"::equals).count());
            assertEquals(callCount, types.stream().filter("function_call_output"::equals).count());
            if (csv) {
                String runtime = DeploymentMirrorRuntime.runtimeSource();
                var pages = last.messages().stream().flatMap(message -> message.toolResult().stream())
                    .map(AiToolResult::text).toList();
                assertEquals(24, pages.size());
                boolean coveredOverlap = false;
                int mirrorPages = 0;
                for (String raw : pages) {
                    assertTrue(raw.startsWith(RAW_PAGE));
                    var payload = pagePayload(raw);
                    assertEquals(snapshot, payload.path("snapshotSha256").asText());
                    String pointer = payload.path("pointer").asText();
                    if (helper.equals(pointer)) {
                        assertPageRanges(DeploymentManagedHelperBundle.renderScript(fixture.layout), payload.path("value"));
                        continue;
                    }
                    assertEquals(mirror, pointer);
                    mirrorPages++;
                    var value = payload.path("value");
                    coveredOverlap |= value.has("newRanges");
                    assertPageRanges(runtime, value);
                }
                assertEquals(23, mirrorPages);
                assertTrue(coveredOverlap);
            }
            assertEquals(0, assisted.hosts.get());
            System.out.printf("BINDING_REVIEW_R22_SHAPE scenario=%s bytes=%d ceiling=%d calls=%d%n", scenario, encoded.length, ceiling, callCount);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"file-transfer", "asset-lending", "csv-inspector", "binary-inspector"})
    void r23LaterApprovalTrafficRetainsExactSourceAndFitsOriginalByteCeilings(String project) throws Exception {
        var trace = R23Trace.forProject(project);
        boolean dependencies = trace.ecosystem().equals("NATIVE_BUILD");
        String kind = dependencies ? "dependencies" : "prepare";
        try (var fixture = new MirroredInstallerTestFixture(); var assisted = new DeploymentNativeHelperPackageImpactTest.Assisted(fixture);
             var request = dependencies ? request(kind, fixture) : diagnosedPreparation(fixture, trace.ecosystem(), trace.branch())) {
            var intent = intent(kind, fixture, request);
            Callable<OperationResult<ApprovalGrant>> review = dependencies
                ? () -> DeploymentFixedCommandApproval.approveWithEvidence(assisted.context, assisted.host, intent, request,
                    fixture.task, fixture.model.configurationRevision, "AUTOMATIC", false,
                    (action, command, task) -> assisted.scope.effectFacts(action, task))
                : () -> ((DeploymentCommandReview) assisted.scope.interaction()).approveCommand(intent, request, fixture.task);
            reply(fixture, verdict("r23-initial-denial", "DENY"));
            assertEquals("DEPLOYMENT_COMMAND_REVIEW_DENIED", assertThrows(ApiException.class, review::call).problem().eventCode());
            var initial = fixture.model.requests.getFirst();
            var facts = JSON.readTree(initial.messages().getLast().text());
            assertEquals("deployment.toolchain." + kind + ".v1", facts.path("operation").asText());
            assertIdentity(facts.path("exactAction"), intent.exactAction());
            if (!dependencies) {
                assertArrayEquals(request.stdin(), facts.at("/toolchainDiagnostic/originalRequestUtf8").asText()
                    .getBytes(StandardCharsets.UTF_8));
                assertEquals(trace.ecosystem(), facts.at("/originalToolchainPreparation/ecosystem").asText());
            }
            String snapshot = facts.at("/" + PHASE + "/snapshotSha256").asText();
            String helper = facts.at("/" + PHASE + "/installedHelperSource/sourcePointer").asText();
            String mirror = facts.at(dependencies ? "/" + PHASE + "/mirrorRuntime/sourcePointer"
                : "/mirrorTransport/runtimeSource/sourcePointer").asText();
            var continuations = new LinkedHashMap<UUID, String>();
            int callCount = 0;
            int continuationBytes = 0;
            List<AiToolCall> latest = List.of();
            for (var batch : trace.batches()) {
                var calls = new ArrayList<AiToolCall>();
                for (var page : batch.pages()) {
                    calls.add(read("r23-page-" + callCount++, snapshot, page.helper() ? helper : mirror, page.offset(), page.limit()));
                }
                latest = List.copyOf(calls);
                replyAtTotalBytes(fixture, continuations, batch.continuationBytes() - continuationBytes,
                    dependencies ? null : "OPENAI_RESPONSES", calls.toArray(AiToolCall[]::new));
                continuationBytes = batch.continuationBytes();
            }
            reply(fixture, verdict("r23-final-denial", "DENY"));
            int first = fixture.model.requests.size();
            var bounded = wireBudgetContext(assisted.context, continuations, 0, () -> fixture.model.requests.size() - first == trace.batches().size()
                ? trace.ceiling() : 1048576);
            var result = assertThrows(ApiException.class, () -> DeploymentFixedCommandApproval.approveWithEvidence(
                bounded, assisted.host, intent, request, fixture.task, fixture.model.configurationRevision, "AUTOMATIC", false,
                dependencies ? (action, command, task) -> assisted.scope.effectFacts(action, task) : assisted.scope));
            assertEquals("DEPLOYMENT_COMMAND_REVIEW_DENIED", result.problem().eventCode());
            var last = fixture.model.requests.getLast();
            byte[] encoded;
            try {
                encoded = wire(last, trace.ceiling(), continuations);
            } catch (ModelRequestBudgetException tooLarge) {
                throw new AssertionError(project + " encoded " + tooLarge.encodedBytes() + " > " + trace.ceiling() + " bytes", tooLarge);
            }
            assertThrows(ModelRequestBudgetException.class, () -> wire(last, encoded.length - 1, continuations));
            assertEquals(65536, JSON.readTree(encoded).path("max_output_tokens").asInt());
            if (dependencies) {
                assertR23Continuations(encoded, continuations, callCount);
                assertR23Pages(last, snapshot, mirror, helper, fixture);
            } else {
                var sources = r24Sources(initial, facts, fixture);
                var visible = new LinkedHashMap<String, TreeMap<Integer, Integer>>();
                seedInlineSourceRanges(facts, visible);
                var asked = new LinkedHashMap<String, TreeMap<Integer, Integer>>();
                collectRequestedCoverage(asked, latest, sources);
                assertR24VisiblePages(encoded, snapshot, latest, true, visible, sources);
                assertR24Coverage(asked, visible);
            }
            assertEquals(0, assisted.hosts.get());
            System.out.printf("TOOLCHAIN_REVIEW_R23_SHAPE project=%s bytes=%d ceiling=%d calls=%d%n",
                project, encoded.length, trace.ceiling(), callCount);
        }
    }

    private static void assertR23Continuations(byte[] encoded, Map<UUID, String> continuations, int callCount) throws Exception {
        var reasoning = new ArrayList<JsonNode>();
        var calls = new ArrayList<JsonNode>();
        var outputs = new ArrayList<String>();
        for (var item : JSON.readTree(encoded).path("input")) {
            switch (item.path("type").asText()) {
                case "reasoning" -> reasoning.add(item);
                case "function_call" -> calls.add(item);
                case "function_call_output" -> outputs.add(item.path("call_id").asText());
            }
        }
        var expectedReasoning = new ArrayList<JsonNode>();
        var expectedCalls = new ArrayList<JsonNode>();
        for (String continuation : continuations.values()) {
            var items = JSON.readTree(continuation);
            expectedReasoning.add(items.get(0));
            for (int index = 1; index < items.size(); index++) expectedCalls.add(items.get(index));
        }
        assertEquals(expectedReasoning, reasoning);
        assertEquals(expectedCalls, calls);
        assertEquals(callCount, outputs.size());
        assertEquals(calls.stream().map(call -> call.path("call_id").asText()).toList(), outputs);
    }

    private static void assertR23Pages(AiRequest request, String snapshot, String mirror, String helper,
                                        MirroredInstallerTestFixture fixture) throws Exception {
        String runtime = DeploymentMirrorRuntime.runtimeSource();
        String helperSource = DeploymentManagedHelperBundle.renderScript(fixture.layout);
        for (var message : request.messages()) {
            if (message.toolResult().isEmpty()) continue;
            var page = message.toolResult().orElseThrow();
            assertFalse(page.error());
            assertTrue(page.text().startsWith(RAW_PAGE));
            var payload = pagePayload(page.text());
            assertEquals(snapshot, payload.path("snapshotSha256").asText());
            String pointer = payload.path("pointer").asText();
            assertTrue(pointer.equals(mirror) || pointer.equals(helper));
            String source = pointer.equals(mirror) ? runtime : helperSource;
            var value = payload.path("value");
            assertEquals(MirroredInstallerTestFixture.sha(source), value.path("sha256").asText());
            assertPageRanges(source, value);
        }
    }

    private record R23Trace(String ecosystem, String branch, int ceiling, List<R23Batch> batches) {
        static R23Trace forProject(String project) {
            // Original before-send ceilings, cumulative continuation bytes and native read shapes from the R23 safe projection.
            // Source pointer kinds were identifiable from the projected lengths 30, 38 and 46.
            return switch (project) {
                case "file-transfer" -> new R23Trace("GO", "1.26", 170548, List.of(
                    new R23Batch(8018, helper(143713), helper(208640), helper(215065), helper(224065), helper(226489),
                        helper(237448), mirror(193282), mirror(7163))));
                case "asset-lending" -> new R23Trace("DOTNET", "10", 306410, List.of(
                    new R23Batch(9136, mirror(109746), mirror(144973), mirror(193282), helper(143713), helper(198433),
                        helper(208640), helper(224065), helper(237448)),
                    new R23Batch(15543, helper(150666), helper(154995), helper(160811), helper(215065), mirror(7163), mirror(76140)),
                    new R23Batch(21412, mirror(3984)),
                    new R23Batch(31674, helper(168811), helper(176811), helper(182811), helper(224065), mirror(84140)),
                    new R23Batch(34169, helper(190811))));
                case "csv-inspector" -> new R23Trace("PYTHON", "3.14", 206514, List.of(
                    new R23Batch(7523, helper(214000), helper(236000), mirror(143000), mirror(192000), mirror(109000)),
                    new R23Batch(25068, helper(143500), helper(150000), helper(160000), helper(195000),
                        new R23Read(true, 237000, 4500), mirror(3800), mirror(76100))));
                case "binary-inspector" -> new R23Trace("NATIVE_BUILD", "", 310412, List.of(
                    new R23Batch(5917, mirror(144973), mirror(193282), mirror(113677)),
                    new R23Batch(10297, mirror(136000), new R23Read(false, 3984, 5000), new R23Read(false, 109746, 4000)),
                    new R23Batch(13679, mirror(90000)), new R23Batch(15954, new R23Read(false, 101000, 7000)),
                    new R23Batch(18633, new R23Read(false, 110000, 7000)), new R23Batch(21676, new R23Read(false, 117000, 7000))));
                default -> throw new IllegalArgumentException(project);
            };
        }

        private static R23Read helper(int offset) { return new R23Read(true, offset, 8000); }

        private static R23Read mirror(int offset) { return new R23Read(false, offset, 8000); }
    }

    private record R23Batch(int continuationBytes, R23Read... pages) { }

    private record R23Read(boolean helper, int offset, int limit) { }

    @ParameterizedTest
    @ValueSource(strings = {"asset-lending", "csv-inspector", "survey-scoring"})
    void r24ObservedToolchainTrafficFitsOriginalPhysicalSendBudgets(String project) throws Exception {
        try (var fixture = new MirroredInstallerTestFixture(); var assisted = new DeploymentNativeHelperPackageImpactTest.Assisted(fixture)) {
            for (var review : R24Review.forProject(project)) {
                try (var request = review.kind().equals("bind") ? request("bind", fixture)
                    : diagnosedPreparation(fixture, review.ecosystem(), review.branch())) {
                    replayR24ToolchainReview("R24", project, review, request, fixture, assisted);
                }
            }
            assertEquals(0, assisted.hosts.get(), "An independent denial never reaches the host approval boundary");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"task-board", "file-transfer", "asset-lending", "log-analyzer"})
    void r25ObservedToolchainTrafficFitsOriginalPhysicalSendBudgets(String project) throws Exception {
        try (var fixture = new MirroredInstallerTestFixture(); var assisted = new DeploymentNativeHelperPackageImpactTest.Assisted(fixture)) {
            for (var review : r25Reviews(project)) {
                try (var request = review.kind().equals("prepare")
                    ? diagnosedPreparation(fixture, review.ecosystem(), review.branch()) : request(review.kind(), fixture)) {
                    replayR24ToolchainReview("R25", project, review, request, fixture, assisted);
                }
            }
            assertEquals(0, assisted.hosts.get());
        }
    }

    private static List<R24Review> r25Reviews(String project) {
        // Original R25 projected coordinate, byte and cumulative usage metadata. Pointer lengths 0, 23 and 56
        // cannot be assigned an exact approved source from the redacted logs; replay them as missing pointers.
        return switch (project) {
                case "task-board" -> List.of(
                    new R24Review("prepare", "JAVA", "21", 35004, List.of(
                        new R24Turn(263001, 1048576, 0, 15140, 60788, "46/237843/5000 46/213426/4848 46/205050/1772 46/152290/2254 46/151025/971 46/155354/1118 46/161170/6000 46/156838/1600"),
                        new R24Turn(278141, 1048576, 10503, 22823, 98979, "30/0/8000 30/8000/5643 30/16170/8000 30/24170/8000 45/0/8000 45/13709/8000 43/0/8000 43/8000/1272"),
                        new R24Turn(300964, 1048576, 0, 34861, 144757, "46/169298/8000 46/177298/8000 46/182927/8000 46/186445/8000 46/195082/8000 30/13643/8000 30/32170/8000 45/21709/8000"),
                        new R24Turn(335825, 1048576, 0, 47013, 198192, "46/144032/6000 30/104547/6000 30/109746/8000 30/193300/2500"),
                        new R24Turn(382838, 1048576, 0, 52416, 220481, "46/205050/8000 46/237843/8000 46/180307/8000 30/3984/8000 30/16170/8000 30/144991/6000"),
                        new R24Turn(435254, 999210, 0, 58372, 236578, "30/40000/8000 30/72000/8000 30/102000/8000 30/117746/8000 30/150991/8000 45/8000/8000 45/29709/5565 46/188438/8000"),
                        new R24Turn(493626, 940838, 0, 69427, 285065, "56/0/8000"),
                        new R24Turn(563053, 871411, 0, 69750, 283917, "30/48000/8000 30/56000/8000 30/64000/8000 30/80000/8000 30/88000/8000 30/96000/8000 30/125746/8000 30/133746/8000"),
                        new R24Turn(632803, 801661, 0, 83042, 349331, "30/3984/8000 30/11984/8000 30/16170/8000 30/24170/8000 46/571338/8000 46/237843/8000 46/169298/8000 45/0/8000"),
                        new R24Turn(715845, 718619, 0, 83470, 350500, "43/0/8000 43/8000/2000 46/144032/8000 46/151025/8000 46/152290/8000 46/161170/8000 30/3984/8000 30/16170/8000"),
                        new R24Turn(799315, 635149, 0, 85188, 356671, "30/0/8000 30/8000/8000 30/140000/8000 30/193300/2500 46/151025/10000 46/161170/8000 46/156838/4000 46/152290/3000"),
                        new R24Turn(884503, 549961, 0, 85906, 360632, ""))),
                    new R24Review("bind", "JAVA", "21", 33873, List.of(
                        new R24Turn(970409, 464055, 0, 16643, 66106, "46/169298/2500 46/175869/3500 46/179096/1500 46/188186/1800 45/13709/1600 30/193300/3000"),
                        new R24Turn(987052, 447412, 11184, 22406, 93942, "46/205050/2400 46/238400/2600 46/145614/1400 46/144166/1000"),
                        new R24Turn(1009458, 425006, 0, 22549, 88992, "45/0/8000 45/8000/8000 45/16000/8000 45/24000/8000 45/32000/4000"),
                        new R24Turn(1032007, 402457, 0, 33045, 124684, ""))),
                    new R24Review("prepare", "NODE", "24", 37068, List.of(
                        new R24Turn(1132888, 301576, 0, 15645, 63018, "43/0/8000 43/8000/2000 46/205050/8000 46/213426/8000 46/237843/8000 46/241364/4000 46/151025/8000 46/182927/8000"),
                        new R24Turn(1148533, 285931, 8531, 29468, 126562, "46/155354/8000 46/195082/5000 46/156838/5000 46/161170/8000 46/186445/8000 45/13709/8000 30/3984/8000 30/16170/8000"),
                        new R24Turn(1178001, 256463, 0, 38920, 160676, "46/169000/8000 46/178000/8000 46/198000/8000 46/205050/8000 46/144032/8000 30/20095/8000 30/32625/8000 45/0/8000"),
                        new R24Turn(1216921, 217543, 0, 49718, 206696, "46/169000/8000 46/178000/8000 46/237843/4000 45/13709/8000 30/16170/8000 30/20095/8000 30/32625/8000 46/571338/1000"),
                        new R24Turn(1266639, 167825, 0, 0, 207794, ""))));
                case "file-transfer" -> List.of(
                    new R24Review("prepare", "GO", "1.24", 32833, List.of(
                        new R24Turn(143022, 1048576, 0, 14250, 58372, "46/237843/8000 46/241364/8000 0/213426/8000 46/205050/8000 46/152290/5000 46/151025/5000 46/155354/8000 46/156838/8000"),
                        new R24Turn(157272, 1048576, 6433, 24485, 102779, "46/213426/8000 46/182927/8000 46/186445/8000 46/195082/8000 46/203800/6000 46/144032/6000 46/194800/6000 43/0/8000"),
                        new R24Turn(181757, 1048576, 0, 33764, 138694, "30/3984/8000"),
                        new R24Turn(215521, 1048576, 0, 35302, 145763, "45/0/8000 45/12711/8000 30/13643/8000 30/16170/8000 30/20095/8000 30/146000/8000 46/144032/8000 46/161170/8000"),
                        new R24Turn(250823, 1048576, 0, 45621, 192275, "46/169170/5000"),
                        new R24Turn(296444, 1048576, 0, 48337, 196355, "46/174170/8000 46/186000/8000 46/194445/4000 30/20095/8000 30/28095/8000"),
                        new R24Turn(344781, 1048576, 0, 50529, 213596, ""))),
                    new R24Review("bind", "GO", "1.24", 31468, List.of(
                        new R24Turn(395310, 1039154, 0, 16232, 63493, "43/0/8000 45/0/8000 45/12000/8000 46/169298/8000 46/175869/8000 46/188186/4000 46/205050/4000 45/31536/3000"),
                        new R24Turn(411542, 1022922, 15537, 31284, 133138, "45/8000/4000 45/20000/8000 45/28000/4000 46/1400/3000 46/145614/4000 46/163000/7000 46/236800/6000 46/238600/4000"),
                        new R24Turn(442826, 991638, 0, 42119, 151909, "46/237842/7000 46/205050/5000 46/150000/3000"),
                        new R24Turn(484945, 949519, 0, 40741, 154182, "45/32000/2276 41/0/6282 30/193300/2445"),
                        new R24Turn(525686, 908778, 0, 41223, 156830, "46/144166/2500"),
                        new R24Turn(566909, 867555, 0, 41611, 158028, "46/237842/3000 46/168500/1800 46/241000/1000"),
                        new R24Turn(608520, 825944, 0, 41744, 158354, "56/0/8000"),
                        new R24Turn(650264, 784200, 0, 40434, 157930, "46/236000/8000"),
                        new R24Turn(690698, 743766, 0, 42557, 158839, "46/145000/8000 46/236000/8000 46/240000/4842"),
                        new R24Turn(733255, 701209, 0, 42243, 162700, "45/30000/8000"),
                        new R24Turn(775498, 658966, 0, 45579, 162371, ""))),
                    new R24Review("prepare", "NODE", "24", 34378, List.of(
                        new R24Turn(890078, 544386, 0, 15127, 60096, "46/237843/8000 46/241364/6000 46/144032/8000 46/571338/5000 46/151025/8000 46/155354/8000 45/12711/6000 43/0/6756"),
                        new R24Turn(905205, 529259, 10282, 28643, 119053, "46/163354/8000 46/182927/8000 46/190000/8000 46/205050/2500 46/213426/5500 30/3984/8000 30/16170/8000 30/20095/8000"),
                        new R24Turn(933848, 500616, 0, 38126, 159494, "46/171354/8000 46/188017/8000 46/196886/8000 46/151025/8000 30/3984/8000 30/28095/4530"),
                        new R24Turn(971974, 462490, 0, 42652, 179073, "30/11984/4200"),
                        new R24Turn(1014626, 419838, 0, 42958, 182467, "30/32625/8000 45/18711/6000 46/144032/8000"),
                        new R24Turn(1057584, 376880, 0, 48343, 197248, "46/204000/8000 46/207000/8000 46/218000/8000 46/226000/8000"),
                        new R24Turn(1105927, 328537, 0, 51999, 218748, "45/0/6000 30/102000/8000 30/110000/8000 30/142000/7000 30/152000/8000"),
                        new R24Turn(1157926, 276538, 0, 62426, 253002, "30/0/3984 30/40625/8000 30/48625/8000 30/56625/8000 30/64625/8000 30/72625/8000 30/80625/8000 30/88625/8000"),
                        new R24Turn(1220352, 214112, 0, 0, 314553, ""))));
                case "asset-lending" -> List.of(
                    new R24Review("prepare", "DOTNET", "10", 33323, List.of(
                        new R24Turn(131386, 1048576, 0, 14507, 58898, "46/237843/8000 46/241364/8000 46/213426/8000 46/215460/8000 46/205050/8000 46/152290/5000 46/151025/5000 46/155354/5000"),
                        new R24Turn(145893, 1048576, 7008, 25953, 109780, "45/0/8000 45/8000/8000 45/16000/8000 45/24000/8000 45/32000/8000 43/0/8000 30/3984/8000 30/13643/8000"),
                        new R24Turn(171846, 1048576, 0, 40809, 162569, "46/144032/5000 46/205050/3000 46/213426/5000 46/237843/6000 46/161170/12000 46/180307/10000 30/16170/16455 30/3984/8000"),
                        new R24Turn(212655, 1048576, 0, 42405, 167672, "46/161170/8000 46/158438/8000 46/169298/8000 46/182927/8000 46/186445/8000 30/20095/8000 30/21643/8000 30/11984/8000"),
                        new R24Turn(255060, 1048576, 0, 50758, 206899, "30/29643/8000 30/37643/8000 46/177298/8000 46/188017/8000"),
                        new R24Turn(305818, 1048576, 0, 54234, 229837, "23/0/8000"),
                        new R24Turn(360052, 1048576, 0, 57211, 237964, "23/8000/8000"),
                        new R24Turn(417263, 1017201, 0, 59103, 239367, "30/0/4000 30/104000/8000 30/112000/8000 30/144991/8000 30/193300/2866 30/45643/8000 30/53643/8000 30/61643/8000"),
                        new R24Turn(476366, 958098, 0, 73812, 297249, "46/149000/3000 46/571200/1500 30/3984/8000 30/11984/2500"),
                        new R24Turn(550178, 884286, 0, 73543, 299998, ""))),
                    new R24Review("bind", "DOTNET", "10", 32076, List.of(
                        new R24Turn(623721, 810743, 0, 17645, 64127, "41/0/6282 43/0/7699 45/0/8000 45/8000/8000 45/16000/8000 45/24000/8000 45/32000/2618 46/169298/400"),
                        new R24Turn(641366, 793098, 35715, 32914, 149024, "46/145614/1200 46/165000/3500 46/175869/3000 46/179096/900 46/188186/700 46/205050/1800 46/237842/5000 46/178743/400"),
                        new R24Turn(674280, 760184, 0, 36005, 127926, "46/168500/4000 46/242842/5000 46/187000/1800"),
                        new R24Turn(710285, 724179, 0, 35483, 137250, "46/0/5000 46/238760/5000 46/568000/4302"),
                        new R24Turn(745768, 688696, 0, 39289, 147088, ""))),
                    new R24Review("prepare", "NODE", "24", 34824, List.of(
                        new R24Turn(854996, 579468, 0, 14897, 60568, "43/0/8000 45/0/8000 45/13000/8000 45/20000/8000 45/31000/4500 46/213000/8000 46/237000/8000 46/241000/8000"),
                        new R24Turn(869893, 564571, 6796, 31673, 128382, "46/205000/2500 46/151000/3500 46/155000/5000 46/156000/4000 46/161000/8000 46/180000/6000 46/182000/7000 46/195000/3000"),
                        new R24Turn(901566, 532898, 0, 38527, 152323, "46/151000/8000 46/159000/8000 46/167000/8000 46/175000/8000 46/181000/8000 46/237000/8000 30/3984/8000 30/16170/8000"),
                        new R24Turn(940093, 494371, 0, 45117, 181295, "30/11984/8000 30/24170/8000 30/32170/8000 46/151000/8000 46/155000/8000 46/159000/8000 46/169000/8000 46/180000/8000"),
                        new R24Turn(985210, 449254, 0, 48421, 201749, "46/144032/8000 46/168000/8000 46/188000/8000 45/8000/5000 45/28000/3000 30/3984/8000 30/40170/8000"),
                        new R24Turn(1033631, 400833, 0, 55736, 231485, "30/108000/8000 30/140000/8000 30/180000/8000 30/188000/8000 46/198000/8000 46/221000/8000"),
                        new R24Turn(1089367, 345097, 0, 65650, 280017, "23/0/8000"),
                        new R24Turn(1155017, 279447, 0, 0, 287850, ""))));
                case "log-analyzer" -> List.of(
                    new R24Review("prepare", "RUST", "1.95", 31173, List.of(
                        new R24Turn(118075, 1048576, 0, 13747, 56649, "46/213426/8000 46/215460/8000 46/237843/8000 46/241364/8000 46/205050/8000 46/152290/8000 46/151025/8000 46/155354/8000"),
                        new R24Turn(131822, 1048576, 6388, 25539, 109973, "46/163354/8000 46/169298/8000 46/182927/8000 46/186445/8000 46/195082/8000 46/180307/8000 46/186000/8000 46/145000/8000"),
                        new R24Turn(157361, 1048576, 0, 35031, 145244, "43/0/8000 45/0/8000 45/8000/8000 45/16000/8000 45/24000/8000 46/144000/8000 46/213426/8000 46/215460/8000"),
                        new R24Turn(192392, 1048576, 0, 47715, 186430, "30/3984/8000 30/11924/8000 30/16170/8000 30/20095/8000 30/32000/4000 45/32000/2000 46/169000/8000 46/242000/4000"),
                        new R24Turn(240107, 1048576, 0, 53748, 216748, "30/28095/4530"),
                        new R24Turn(293855, 1048576, 0, 53830, 219569, "30/16170/8000 30/24170/8000 30/32170/5000 46/177298/4000"),
                        new R24Turn(347685, 1048576, 0, 55266, 224204, ""))),
                    new R24Review("bind", "RUST", "1.95", 29887, List.of(
                        new R24Turn(402951, 1031513, 0, 15852, 61740, "41/0/7000 46/145614/3000 46/169298/5000 46/179096/5000 46/175869/5000 46/188186/1500 45/0/8000 45/11924/8000"),
                        new R24Turn(418803, 1015661, 17009, 28850, 117554, "45/8000/8000 45/19924/8000 45/27924/6000 43/0/6000 46/205050/3000 46/238760/2200 46/145900/2500"),
                        new R24Turn(447653, 986811, 0, 33445, 129094, ""))),
                    new R24Review("dependencies", "NATIVE_BUILD", "", 31478, List.of(
                        new R24Turn(500098, 934366, 0, 35008, 145069, "38/144991/5000 38/109746/4000 45/11924/4500 45/13042/4500 45/30749/4500"),
                        new R24Turn(535106, 899358, 6653, 41301, 172137, "45/16424/5000 45/19589/5500 45/23000/5000 45/28000/5000 38/145000/4000"),
                        new R24Turn(576407, 858057, 0, 45303, 178519, "45/0/8000 45/8000/8000 38/3984/8000 38/144991/8000"),
                        new R24Turn(621710, 812754, 0, 53894, 202173, "38/0/4000"),
                        new R24Turn(675604, 758860, 0, 51722, 205806, "38/144991/8000 38/148638/8000 38/16170/8000 38/20095/8000 45/11924/8000 45/19900/8000 45/30749/2740"),
                        new R24Turn(727326, 707138, 0, 61265, 222697, "38/144991/5000 45/11924/8000 45/19900/8000"),
                        new R24Turn(788591, 645873, 0, 57346, 222076, "38/104547/4190"),
                        new R24Turn(845937, 588527, 0, 58340, 226115, ""))));
            default -> throw new IllegalArgumentException(project);
        };
    }

    @Test
    void r26FileTransferBindingTrafficFitsOriginalPhysicalSendBudgets() throws Exception {
        // Both original binds. The logs disclose only pointer lengths and cursors for these reads.
        var reviews = List.of(
            new R24Review("bind", "GO", "1.24.13", 31468, List.of(
                new R24Turn(390183, 1044281, 0, 16697, 64215, "43/0/8000 45/12600/4500 45/0/1800 46/169298/400 46/205050/1800 46/175869/3000 46/188186/700 41/0/6500"),
                new R24Turn(406880, 1027584, 20391, 24450, 108509, "45/1800/8000 45/9800/2800 45/17100/8000 45/25100/8000 45/33100/1176"),
                new R24Turn(431330, 1003134, 0, 31046, 116302, "45/0/8000"),
                new R24Turn(462376, 972088, 0, 32010, 115660, "46/179096/700 46/169298/650 46/237842/1600 46/195081/5000"),
                new R24Turn(494386, 940078, 0, 32874, 118597, "45/0/8000 45/8000/8000 45/16000/8000 45/24000/8000 45/32000/3000"),
                new R24Turn(527260, 907204, 0, 30767, 118549, "41/0/7000"),
                new R24Turn(558027, 876437, 0, 30054, 117906, "46/571337/1500 46/145614/1600 30/3984/1000 30/76140/1800"),
                new R24Turn(588081, 846383, 0, 33571, 123182, "46/236800/4500"),
                new R24Turn(621652, 812812, 0, 33570, 125782, ""))),
            new R24Review("bind", "NODE", "24.22.3", 32527, List.of(
                new R24Turn(850005, 584459, 0, 17059, 65368, "43/0/7000 45/0/8000 45/8000/8000 45/16000/8000 45/24000/8000 45/32000/3000 46/169298/400 46/188186/1200"),
                new R24Turn(867064, 567400, 20675, 22378, 104092, "45/8000/8000 45/16000/8000 45/24000/8000 45/32000/3000 46/169298/400 46/188186/1200 46/175869/3000 46/205050/1800"),
                new R24Turn(889442, 545022, 0, 33583, 118270, "46/179096/1200 46/238500/3000 46/199600/3500 46/168500/2500"),
                new R24Turn(923025, 511439, 0, 32571, 127672, "41/0/8000 30/144000/3000 46/241000/1600 46/199000/2400"),
                new R24Turn(955596, 478868, 0, 33439, 123808, "45/0/8000 46/237842/3276"),
                new R24Turn(989035, 445429, 0, 36864, 131790, "46/145614/8000 46/238760/4000 46/199619/3000"),
                new R24Turn(1025899, 408565, 0, 33289, 131002, "23/0/8000"),
                new R24Turn(1059188, 375276, 0, 33425, 130602, "23/8000/8000 41/0/8000 46/153614/8000 46/238760/8000 46/241118/4000 46/202619/2000 45/24000/8000 43/0/8000"),
                new R24Turn(1092613, 341851, 0, 33521, 132478, "23/0/8000 23/8000/8000 41/0/8000 46/169298/8000 46/179096/8000 46/188186/8000 46/205050/8000 54/3984/8000"),
                new R24Turn(1126134, 308330, 0, 33398, 131205, "45/0/8000 45/30000/4276 46/238760/6000 46/241118/5642 46/199619/3000"),
                new R24Turn(1159532, 274932, 0, 32476, 129862, "41/0/7000 43/0/7000 30/0/8000 30/144991/8000 46/177911/8000 46/238760/8000"),
                new R24Turn(1192008, 242456, 0, 33054, 130887, "46/169298/1000 46/188186/1000 46/205050/2500 46/1731/2200 46/237842/1900 46/241024/1500 46/177911/1000"),
                new R24Turn(1225062, 209402, 0, 31425, 130162, "23/0/8000"),
                new R24Turn(1256487, 177977, 0, 33090, 131408, "45/0/8000"),
                new R24Turn(1289577, 144887, 0, 30235, 124656, "46/199619/8000 46/246760/8000 46/238760/982 46/241118/1406 41/0/8000 43/0/7000 45/0/3000"),
                new R24Turn(1319812, 114652, 0, 0, 132629, ""))));
        try (var fixture = new MirroredInstallerTestFixture(); var assisted = new DeploymentNativeHelperPackageImpactTest.Assisted(fixture)) {
            for (var review : reviews) {
                try (var request = r26Binding(fixture, review.ecosystem(), review.branch())) {
                    replayR24ToolchainReview("R26", "file-transfer", review, request, fixture, assisted);
                }
            }
            assertEquals(0, assisted.hosts.get());
        }
    }

    @Test
    void r27FileTransferPreparationTrafficFitsWithHeadroomAndStillDenies() throws Exception {
        // The first File Transfer toolchain action follows the Go backend. Provider telemetry exposes
        // pointer width and cursors only. Run both interpretations of width 45, which is shared by
        // mirrorBootstrapShell and preparationSelection. Oversized limits are kept as observed.
        var review = new R24Review("prepare", "GO", "1.24", 36761, List.of(
            new R24Turn(353945, 1048576, 0, 20220, 85216, "43/0/8000 45/0/8000 30/3984/8000 30/13643/8000"),
            new R24Turn(374165, 1048576, 0, 28839, 126125, "30/21643/8000 45/12000/8000 46/162618/8000 46/184375/8000"),
            new R24Turn(403004, 1031460, 0, 33088, 134610, "45/8000/4000 45/20000/8000 45/28000/6276 30/3984/8000 30/16170/8000 30/24170/8000 46/181755/2620"),
            new R24Turn(436092, 998372, 0, 33084, 134018, "43/0/8000 45/0/8000 45/12000/8000 30/3984/8000 46/162618/8000 46/170746/4000"),
            new R24Turn(469176, 965288, 0, 35044, 138503, "45/28000/6276 30/3984/7000 30/16170/16455 46/189465/7070"),
            new R24Turn(504220, 930244, 0, 35080, 137718, "30/13643/8000 30/20095/8000 46/189634/8000 46/214874/4000"),
            new R24Turn(539300, 895164, 0, 33205, 135601, "45/8000/8000 45/16000/8000 30/28095/5000 45/24000/4000"),
            new R24Turn(572505, 861959, 0, 31945, 132278, "43/0/8000 45/0/8000 45/28000/6276 30/3984/8000 30/32625/5000"),
            new R24Turn(604450, 830014, 0, 34960, 138177, "45/16000/8000 30/16170/8000 30/11984/8000 46/162618/8000"),
            new R24Turn(639410, 795054, 0, 34681, 137967, "46/184375/5100 46/214874/1800 45/12711/3300 30/32625/6500"),
            new R24Turn(674091, 760373, 0, 31940, 132667, "43/0/8000 45/0/8000 30/3984/8000 30/8737/8000"),
            new R24Turn(706031, 728433, 0, 32921, 136206, "45/8000/8000 30/0/3984 30/11984/8000 30/26000/6625"),
            new R24Turn(738952, 695512, 0, 32742, 135390, "30/3984/4753 30/32625/8000 45/16000/8000 46/162618/8000"),
            new R24Turn(771694, 662770, 0, 32716, 136718, "43/0/8000 45/24000/8000 45/32000/3000 30/8737/3200"),
            new R24Turn(804410, 630054, 0, 33207, 134472, "45/0/8000 45/8000/8000 30/0/3984 30/8737/7500 30/16170/8000 30/24000/8625"),
            new R24Turn(837617, 596847, 0, 32963, 133557, "30/3984/4753 45/16000/8000 45/24000/8000"),
            new R24Turn(870580, 563884, 0, 34503, 136951, "45/0/8000 45/8000/8000 45/32000/2276 30/0/3984 30/13643/8000 30/21643/8000 43/0/6756"),
            new R24Turn(905083, 529381, 0, 31864, 135671, "45/8000/8000 45/16000/8000 45/24000/8000"),
            new R24Turn(936947, 497517, 0, 34836, 135094, "45/0/8000 45/32000/2276 46/214874/4000 46/162618/8000 30/3984/8000 30/21643/8000"),
            new R24Turn(971783, 462681, 0, 34297, 136633, "30/13643/8000 30/20095/8000 45/16000/8000 45/24000/8000"),
            new R24Turn(1006080, 428384, 0, 33627, 137086, "45/8000/8000 30/3984/8000 43/0/6756 30/32625/4000"),
            new R24Turn(1039707, 394757, 0, 31493, 135967, "45/0/8000"),
            new R24Turn(1071200, 363264, 0, 32909, 135613, "45/16000/8000 45/24000/8000 30/16000/8000 46/162618/8000"),
            new R24Turn(1104109, 330355, 0, 34617, 136444, "46/162618/8000 43/0/8000 45/0/8000 30/0/8000 30/8000/8000 30/24000/8000"),
            new R24Turn(1138726, 295738, 0, 32342, 135068, "45/12711/8000 30/16000/8000 30/32000/8000 46/168000/8000 46/170746/8000 46/214874/8000 46/259021/8000"),
            new R24Turn(1171068, 263396, 0, 31841, 134832, "30/3984/9000 43/0/8000 45/0/8000 46/162618/8150"),
            new R24Turn(1202909, 231555, 0, 32869, 135107, "46/162618/8000 46/184375/1600 46/187893/1572 46/191889/4641 45/8000/8000 30/3984/8000 30/20095/8000 30/28095/8000"),
            new R24Turn(1235778, 198686, 0, 32624, 136462, "43/0/8000 30/0/8000 30/12000/8000 45/0/8000"),
            new R24Turn(1268402, 166062, 0, 32038, 134145, "30/8000/4000 30/20000/8000 45/12000/5000 30/155000/3000"),
            new R24Turn(1300440, 134024, 0, 0, 134625, "")));
        for (boolean inlinePointer : List.of(false, true)) {
            try (var fixture = new MirroredInstallerTestFixture();
                 var assisted = new DeploymentNativeHelperPackageImpactTest.Assisted(fixture);
                 var request = diagnosedPreparation(fixture, review.ecosystem(), review.branch())) {
                replayToolchainReview("R27", "file-transfer", review, request, fixture, assisted, inlinePointer, 20000);
                assertEquals(0, assisted.hosts.get());
            }
        }
    }

    @Test
    void r26AssetLendingOversizedBindReadBatchGetsOneNoActionCorrection() throws Exception {
        try (var fixture = new MirroredInstallerTestFixture(); var command = r26Binding(fixture, "DOTNET", "10.0.300")) {
            var intent = intent("bind", fixture, command);
            var facts = new DeploymentFixedCommandProtocol(fixture.context).inspect(intent, command, fixture.layout, fixture.task);
            var evidence = DeploymentCommandSourceEvidence.project(DeploymentAiJson.facts(facts, 1048576), intent, command,
                fixture.layout).orElseThrow();
            var messages = new ArrayList<>(evidence.messages());
            var current = JSON.readTree(messages.getLast().text());
            String snapshot = current.at("/" + PHASE + "/snapshotSha256").asText();
            String helper = current.at("/" + PHASE + "/installedHelperSource/sourcePointer").asText();
            String mirror = current.at("/mirrorTransport/runtimeSource/sourcePointer").asText();
            String shell = current.at("/" + PHASE + "/mirrorBootstrapShell/sourcePointer").asText();
            var original = List.of(
                read("asset-0", snapshot, helper, 238760, 3200),
                read("asset-1", snapshot, helper, 179096, 529),
                read("asset-2", snapshot, helper, 169298, 323),
                read("asset-3", snapshot, helper, 175869, 2874),
                read("asset-4", snapshot, helper, 188186, 494),
                read("asset-5", snapshot, shell, 0, 5600),
                read("asset-6", snapshot, mirror, 144991, 3647),
                read("asset-7", snapshot, "/unresolvedAssetLendingSource", 0, 906),
                read("asset-8", snapshot, shell, 0, 1487));
            var reader = evidence.reader(32);
            for (int round = 0; round < 2; round++) {
                var response = mockResponses(read("previous-" + round, snapshot, mirror, round * 120, 120));
                reader.continueReview(messages, response, reader.read(response));
            }
            var response = mockResponses(original.toArray(AiToolCall[]::new));
            var rejected = reader.read(response);
            assertEquals(original.stream().map(AiToolCall::id).toList(), rejected.stream().map(AiToolResult::callId).toList());
            assertTrue(rejected.stream().allMatch(result -> result.error() && result.text().contains("no pages were read")));
            reader.continueReview(messages, response, rejected);
            String frame = messages.get(messages.size() - 2).text();
            int first = frame.indexOf('\n') + 1;
            var header = JSON.readTree(frame.substring(first, frame.indexOf('\n', first)));
            assertEquals("NO_PAGES_READ_MAX_EIGHT_PER_RESPONSE", header.path("latestBatchRejected").asText());
            assertEquals(9, header.path("latestReads").size());
            for (var receipt : header.path("latestReads")) {
                assertTrue(receipt.path("rejected").asBoolean());
                assertFalse(receipt.has("pointer"));
            }
            assertFalse(messages.stream().anyMatch(item -> item.continuation().isPresent() || item.toolResult().isPresent()));
            var corrected = reader.read(mockResponses(read("asset-correction", snapshot, shell, 0, 5600)));
            var page = pagePayload(corrected.getFirst().text());
            assertEquals(r24Shell(fixture).substring(0, 5600), page.at("/value/text").asText());
            var secondOversized = original.stream().map(call -> new AiToolCall("again-" + call.id(), call.name(), call.arguments()))
                .toArray(AiToolCall[]::new);
            assertThrows(IOException.class, () -> reader.read(mockResponses(secondOversized)));
            var mixed = new AiToolCall[]{read("new-page", snapshot, shell, 0, 5600), verdict("mixed-verdict", "APPROVE")};
            assertThrows(IOException.class, () -> evidence.reader(32).read(mockResponses(mixed)));
            assertThrows(IOException.class, () -> evidence.reader(8).read(response));
        }
    }

    private static CommandRequest r26Binding(MirroredInstallerTestFixture fixture, String ecosystem, String version) {
        String branch = version.substring(0, ecosystem.equals("GO") ? version.lastIndexOf('.') : version.indexOf('.'));
        String declaration = Base64.getEncoder().encodeToString(branch.getBytes(StandardCharsets.UTF_8));
        String canonical = "WTL-TOOLS-1\tcurrent\n" + ecosystem + "\tLANGUAGE_TARGET\t" + declaration + "\tZml4dHVyZQ==\tSERIES\t"
            + branch
            + "\t" + version + "\t" + fixture.layout.toolchains() + "/versions/" + ecosystem.toLowerCase(java.util.Locale.ROOT) + "-"
            + "c".repeat(64) + "\tMANAGED\t" + Base64.getEncoder().encodeToString("https://example.invalid/tool".getBytes(StandardCharsets.UTF_8))
            + "\t" + "d".repeat(64) + "\n";
        String literal = Base64.getEncoder().encodeToString(canonical.getBytes(StandardCharsets.UTF_8));
        String invocation = "'" + fixture.layout.helperPath() + "' prepare-toolchains 'bind' '" + literal + "'";
        String guarded = DeploymentManagedHelperBundle.verifiedCommand(invocation, fixture.layout);
        return new CommandRequest(DeploymentMirrorTasks.preparationCommand(guarded, fixture.task, 180),
            new byte[0], Duration.ofSeconds(180), 65536);
    }

    @Test
    void r25LogAnalyzerFirstBuildWireRestoresCompleteExecutableInputWithinOriginalBudget() throws Exception {
        try (var fixture = new MirroredInstallerTestFixture(); var assisted = new DeploymentNativeHelperPackageImpactTest.Assisted(fixture)) {
            String body = "# LogAnalyzer staged build fixture — 中文\nset -eu\n"
                + "cargo build --release --locked --manifest-path cli/Cargo.toml\n"
                + "cmake -S native -B .w2l/build -G Ninja\ncmake --build .w2l/build\n";
            String policy = DeploymentMirrorTasks.policy(fixture.task);
            int seconds = 120;
            String input = DeploymentMirrorTasks.withMaterials(DeploymentMirrorRuntime.wrapBuild(body, policy, seconds), fixture.task.id());
            var workspace = new gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentRemoteWorkspace(
                "log-analyzer", "a".repeat(64), fixture.layout);
            String collector = gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentBuildOutputProtocol.script(workspace,
                seconds, 16, 512, 8192, "ordinary", MirroredInstallerTestFixture.sha(input), "-", List.of(List.of("BUILD_OK")));
            try (var request = new CommandRequest(DeploymentManagedHelperBundle.verifiedCommand(collector, fixture.layout),
                input.getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(seconds + 15), 65536)) {
                var intent = RemoteActions.command(fixture.server, fixture.model.configurationRevision, request)
                    .withSemantics(new ActionSemantics("deployment.build.run.v1",
                        Set.of(new ActionTarget(fixture.server.id(), "deployment-build", workspace.candidateRoot()))));
                var protocol = new DeploymentFixedCommandProtocol(fixture.context);
                var facts = new LinkedHashMap<String, Object>(protocol.inspect(intent, request, fixture.layout, fixture.task));
                facts.put("currentCommandEvidence", assisted.scope.effectFacts(intent, fixture.task));
                var model = new DeploymentAiJson(assisted.context);
                reply(fixture, verdict("r25-log-analyzer-denial", "DENY"));
                assertEquals("DENY", model.askCommand("Inspect exact current build", facts, fixture.model.configurationRevision,
                    fixture.task, DeploymentReviewDecision::malformed, intent, request).path("decision").asText());
                var actual = fixture.model.requests.getLast();
                var messages = new ArrayList<>(actual.messages());
                var baseline = JSON.valueToTree(facts);
                int difference = 552447 - JSON.writeValueAsString(baseline).length();
                var projected = (ObjectNode) JSON.readTree(messages.getLast().text());
                // Keep the observed input scale without resending the duplicate runtime bytes.
                if (difference > 24) projected.put("replayFixturePadding", "x".repeat(difference - 24));
                messages.set(messages.size() - 1, new AiMessage(AiRole.USER, projected.toString(), List.of()));
                int bound = 1500000 - 975673 - 65536;
                byte[] encoded = wire(copy(actual, messages, bound), bound, Map.of());
                assertTrue(encoded.length < bound);
                var wireMessages = JSON.readTree(encoded).path("input");
                var manifest = projected.path("buildReviewEvidence");
                var sources = new LinkedHashMap<String, String>();
                for (var frame : manifest.path("frames")) {
                    var message = wireMessages.get(1 + frame.path("ordinal").asInt());
                    assertEquals("user", message.path("role").asText());
                    String text = message.at("/content/0/text").asText().substring(frame.path("headerCharacters").asInt());
                    assertIdentity(frame, text);
                    assertNull(sources.put(frame.path("sourcePointer").asText(), text));
                }
                var source = DeploymentCommandSourceEvidence.project(DeploymentAiJson.facts(facts, 1048576),
                    intent, request, fixture.layout).orElseThrow();
                var sharedRuntime = projected.at("/mirrorTransport/runtimeSource");
                assertEquals("EVIDENCE_PAGES", sharedRuntime.path("retrieval").asText());
                assertFalse(sources.containsKey(sharedRuntime.path("sourcePointer").asText()));
                for (var descriptor : List.of(sharedRuntime, manifest.path("mirrorBootstrapShell"), manifest.path("stagedMirrorPolicy"))) {
                    assertEquals("EVIDENCE_PAGES", descriptor.path("retrieval").asText());
                    String full = collect(source, descriptor, "buildReviewEvidence");
                    assertIdentity(descriptor, full);
                    assertNull(sources.put(descriptor.path("sourcePointer").asText(), full));
                }
                assertEquals(input, restoreValue(projected.path("completeExecutableInput"), sources));
                assertEquals(body, sources.get("/buildReviewEvidence/buildBody"));
                assertEquals(intent.exactAction(), projected.path("exactAction").asText());
                assertEquals(DeploymentMirrorRuntime.runtimeSource(), sources.get("/mirrorTransport/runtimeSource"));
                assertEquals(5, sources.size());
                assertEquals(0, assisted.hosts.get());
                System.out.printf("R25_BUILD_WIRE observed=975673 originalBytes=631868 encoded=%d bound=%d%n", encoded.length, bound);
                assertR30BuildWire(source, actual, projected, input, fixture);
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"171785, 0", "171785, 52900", "232077, 52900"})
    void r49CsvAnalyzerArchiveAndObservedReadsUseTheOriginalResponsesCeilings(int terminalCap, int factsFloor) throws Exception {
        String archiveSha = "971a4ba2fed28306717230d226fba0e61d98ef07069be0cc06ae2b72387427dc";
        String manifestSha = "b".repeat(64);
        var members = csvAnalyzerMembers();
        var archive = new DeploymentSourceArchiveReview.Evidence(archiveSha, manifestSha, "analyzer",
            members.size(), "COMPLETE", members, List.of());
        try (var fixture = new MirroredInstallerTestFixture();
             var assisted = new DeploymentNativeHelperPackageImpactTest.Assisted(fixture)) {
            var workspace = new DeploymentRemoteWorkspace("wtl-assisted-csv-inspector-3r7i80rxkc-analyzer", archiveSha, fixture.layout);
            var project = new DeploymentProjectFacts(Path.of("."), workspace.applicationId(), DeploymentProjectKind.PYTHON_SERVICE,
                DeploymentBuildToolType.PIP_LOCKED, List.of(), List.of(), List.of());
            var runtime = new DeploymentRuntimeDefinition.PythonService("3.12", "server.py",
                new DeploymentHealthCheck.Http(java.net.URI.create("http://127.0.0.1:46131/healthz"), 200, 10));
            var limits = new DeploymentBuildLimits(120, 32, 512, 65536, 128L * 1024 * 1024, false);
            String body = new PipBuildWriter().render(project, runtime, workspace, limits);
            String input = DeploymentMirrorTasks.withMaterials(
                DeploymentMirrorRuntime.wrapBuild(body, DeploymentMirrorTasks.policy(fixture.task), 120), fixture.task.id());
            String collector = DeploymentBuildOutputProtocol.script(workspace, 120, 32, 512, 65536, "ordinary",
                MirroredInstallerTestFixture.sha(input), "-", List.of(List.of("BUILD_OK")));
            try (var command = new CommandRequest(DeploymentManagedHelperBundle.verifiedCommand(collector, fixture.layout),
                input.getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(135), 65536)) {
                var action = RemoteActions.command(fixture.server, fixture.model.configurationRevision, command)
                    .withSemantics(new ActionSemantics("deployment.build.run.v1",
                        Set.of(new ActionTarget(fixture.server.id(), "deployment-build", workspace.candidateRoot()))));
                var original = new LinkedHashMap<>(new DeploymentFixedCommandProtocol(fixture.context)
                    .inspect(action, command, fixture.layout, fixture.task));
                var current = new LinkedHashMap<String, Object>(assisted.scope.effectFacts(action, fixture.task));
                current.put("currentBuildArchive", Map.of("archive", Map.of("archiveSha256", archiveSha,
                    "sourceManifestSha256", manifestSha, "relativeRoot", "analyzer", "fileCount", members.size())));
                current.put("currentBuildArchiveSource", archive);
                original.put("currentCommandEvidence", current);
                replayCsvAnalyzerBuild(fixture, assisted, action, command, original, members, body, terminalCap, factsFloor);
            }
        }
    }

    private static List<DeploymentSourceArchiveReview.Member> csvAnalyzerMembers() throws Exception {
        Path component = Path.of("").toAbsolutePath();
        while (component != null && !Files.isRegularFile(component.resolve("test/multi-language/success-csv-inspector/analyzer/server.py")))
            component = component.getParent();
        assertNotNull(component, "Repository CSV Inspector fixture is required");
        Path root = component.resolve("test/multi-language/success-csv-inspector/analyzer");
        var members = new ArrayList<DeploymentSourceArchiveReview.Member>();
        for (String name : List.of("analysis.py", "pyproject.toml", "requirements.in", "requirements.lock", "server.py",
            "windowstolinux-application.properties")) {
            String text = Files.readString(root.resolve(name), StandardCharsets.UTF_8);
            if (name.equals("windowstolinux-application.properties")) text = text.replace("18131", "46131");
            members.add(new DeploymentSourceArchiveReview.Member(name, MirroredInstallerTestFixture.sha(text),
                text.getBytes(StandardCharsets.UTF_8).length, text));
        }
        return List.copyOf(members);
    }

    private static void replayCsvAnalyzerBuild(MirroredInstallerTestFixture fixture, DeploymentNativeHelperPackageImpactTest.Assisted assisted,
                                               ActionIntent action, CommandRequest command, Map<String, Object> original,
                                               List<DeploymentSourceArchiveReview.Member> members, String body,
                                               int terminalCap, int factsFloor) throws Exception {
        var evidence = DeploymentCommandSourceEvidence.project(DeploymentAiJson.facts(original, 1048576),
            action, command, fixture.layout).orElseThrow();
        var projected = JSON.readTree(evidence.messages().getLast().text());
        var sources = new LinkedHashMap<Integer, Map.Entry<String, String>>();
        sources.put(30, Map.entry("/mirrorTransport/runtimeSource", DeploymentMirrorRuntime.runtimeSource()));
        var shell = projected.at("/buildReviewEvidence/mirrorBootstrapShell");
        sources.put(31, Map.entry(shell.path("sourcePointer").asText(), collect(evidence, shell, "buildReviewEvidence")));
        List<String> observedBatches = List.of("30/66000/8000", "30/74000/8000", "31/0/8000", "30/66910/8000",
            "30/64000/8000 30/68340/8000 30/82000/8000 30/198000/6000",
            "30/32781/8000 30/40781/8000 30/48781/8000 30/56781/8000 30/64781/8000 30/76296/8000");
        List<Integer> caps = List.of(499773, 449664, 397412, 344107, 288342, 232077, terminalCap);
        boolean withheldHistoricalEvidence = terminalCap == 171785 && factsFloor == 52900;
        var requested = new LinkedHashMap<String, TreeMap<Integer, Integer>>();
        var batches = new ArrayList<List<AiToolCall>>();
        var context = wireBudgetContext(assisted.context, Map.of(), factsFloor, () -> caps.get(fixture.model.requests.size()), sent -> {
            if (!batches.isEmpty()) return;
            try {
                String snapshot = JSON.readTree(sent.messages().getLast().text()).at("/buildReviewEvidence/snapshotSha256").asText();
                assertTrue(snapshot.matches("[a-f0-9]{64}"));
                for (int index = 0; index < observedBatches.size(); index++) {
                    var calls = new R24Turn(0, 0, 0, 0, 0, observedBatches.get(index)).calls(snapshot, sources, index);
                    batches.add(calls);
                    collectRequestedCoverage(requested, calls, sources);
                    fixture.model.replies.add(success(mockResponses(calls.toArray(AiToolCall[]::new))));
                }
                reply(fixture, verdict("r49-csv-fixture-verdict", withheldHistoricalEvidence ? "APPROVE" : "DENY"));
            } catch (Exception invalid) {
                throw new AssertionError("Cannot replay evidence against the actual request snapshot", invalid);
            }
        });
        // The replay supplies reconstructed command and archive facts, not an open source-selection workflow.
        String instructions = DeploymentReviewDecision.INSTRUCTION + DeploymentFixedCommandApproval.commandReviewInstructions(true)
            + "\n" + DeploymentAssistedScope.standardBuildReviewInstructions();
        Callable<JsonNode> review = () -> new DeploymentAiJson(context).askCommand(instructions, original,
            fixture.model.configurationRevision, fixture.task, DeploymentReviewDecision::malformed, action, command);
        if (withheldHistoricalEvidence) {
            var refused = assertThrows(DeploymentCommandReviewModel.Failure.class, review::call);
            var details = DeploymentCommandReviewModel.failureDiagnostics(refused);
            assertEquals("EVIDENCE_INCOMPLETE_VERDICT", details.get("reviewCode"));
            assertTrue(Set.of("REQUESTED_SOURCE_MISSING", "REQUIRED_SOURCE_MISSING").contains(details.get("reviewEvidenceBlock")));
        } else {
            assertEquals("DENY", review.call().path("decision").asText());
        }
        assertEquals(0, assisted.hosts.get());
        assertEquals(caps.size(), fixture.model.requests.size());
        var sent = fixture.model.requests.getLast();
        var manifest = JSON.readTree(sent.messages().getLast().text()).path("buildReviewEvidence");
        for (int index = 0; index < members.size(); index++) {
            var member = members.get(index);
            var descriptor = manifest.at("/projectArchive/members/" + index);
            assertEquals(member.path(), descriptor.path("path").asText());
            assertIdentity(descriptor, member.text());
            sources.put(40 + index, Map.entry(descriptor.path("sourcePointer").asText(), member.text()));
            addR24Range(requested, descriptor.path("sourcePointer").asText(), 0, member.text().length());
        }
        sources.put(60, Map.entry("/buildReviewEvidence/buildBody", body));
        byte[] encoded = wireWithR24FactsSize(sent, factsFloor, caps.getLast(), Map.of());
        System.out.printf("R53_CSV_ANALYZER_RESPONSES factsFloor=%d projectedFacts=%d replayFirst=%d replayTerminal=%d terminalCap=%d members=%d verdict=%s%n",
            factsFloor, sent.messages().getLast().text().length(),
            wireWithR24FactsSize(fixture.model.requests.getFirst(), factsFloor, caps.getFirst(), Map.of()).length,
            encoded.length, caps.getLast(), members.size(), withheldHistoricalEvidence ? "APPROVE_BLOCKED" : "DENY");
        var visible = new LinkedHashMap<String, TreeMap<Integer, Integer>>();
        assertR24VisiblePages(encoded, manifest.path("snapshotSha256").asText(), batches.getLast(), true, visible, sources);
        if (!withheldHistoricalEvidence) assertR24Coverage(requested, visible);
    }

    private static void assertR30BuildWire(DeploymentCommandSourceEvidence evidence, AiRequest request,
                                            ObjectNode projected, String input,
                                            MirroredInstallerTestFixture fixture) throws Exception {
        String snapshot = JSON.readTree(evidence.messages().getLast().text()).at("/buildReviewEvidence/snapshotSha256").asText();
        var stagedPolicy = projected.at("/buildReviewEvidence/stagedMirrorPolicy");
        String policy = DeploymentMirrorTasks.policy(fixture.task);
        for (var replay : List.of(Map.entry(352804, read("r30-file-transfer", snapshot,
                stagedPolicy.path("sourcePointer").asText(), 0, 8000)),
            Map.entry(322849, read("r30-csv-inspector", snapshot, "/completeExecutableInput", 8000, 4000)))) {
            var messages = new ArrayList<>(request.messages());
            messages.set(messages.size() - 1, evidence.messages().getLast());
            var reader = evidence.reader(8);
            var response = mockResponses(replay.getValue());
            var results = reader.read(response);
            assertFalse(results.getFirst().error());
            var page = pagePayload(results.getFirst().text());
            var source = replay.getKey() == 352804 ? policy : input;
            assertPageRanges(source, page.path("value"));
            reader.continueReview(messages, response, results);
            var facts = (ObjectNode) JSON.readTree(messages.getLast().text());
            int padding = (replay.getKey() == 352804 ? 40399 : 41266) - facts.toString().length() - 24;
            if (padding > 0) facts.put("replayFixturePadding", "x".repeat(padding));
            messages.set(messages.size() - 1, new AiMessage(AiRole.USER, facts.toString(), List.of()));
            byte[] actual = wire(copy(request, messages, replay.getKey()), replay.getKey(), Map.of());
            assertTrue(actual.length < replay.getKey());
            System.out.printf("R30_BUILD_REPLAY encoded=%d originalCap=%d%n", actual.length, replay.getKey());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"task-board", "log-analyzer", "binary-inspector"})
    void r30ToolchainTerminalReadFramesUseOriginalByteCeilings(String project) throws Exception {
        boolean prepare = project.equals("task-board");
        String kind = prepare ? "prepare" : "dependencies";
        try (var fixture = new MirroredInstallerTestFixture();
             var request = prepare ? diagnosedPreparation(fixture, "JAVA", "21") : request(kind, fixture);
             var assisted = new DeploymentNativeHelperPackageImpactTest.Assisted(fixture)) {
            var intent = intent(kind, fixture, request);
            var protocol = new DeploymentFixedCommandProtocol(fixture.context);
            var facts = new LinkedHashMap<String, Object>(protocol.inspect(intent, request, fixture.layout, fixture.task));
            facts.put("currentCommandEvidence", prepare ? assisted.scope.facts(intent, request, fixture.task)
                : assisted.scope.effectFacts(intent, fixture.task));
            var evidence = DeploymentCommandSourceEvidence.project(DeploymentAiJson.facts(facts, 1048576),
                intent, request, fixture.layout).orElseThrow();
            var model = new DeploymentAiJson(assisted.context);
            reply(fixture, verdict("r30-review-deny", "DENY"));
            assertEquals("DENY", model.askCommand("Inspect exact current request", facts, fixture.model.configurationRevision,
                fixture.task, DeploymentReviewDecision::malformed, intent, request).path("decision").asText());
            var initial = fixture.model.requests.getFirst();
            var projected = JSON.readTree(evidence.messages().getLast().text());
            String snapshot = projected.at("/" + PHASE + "/snapshotSha256").asText();
            var sources = r24Sources(initial, projected, fixture);
            sources.put("/exactAction".length(), Map.entry("/exactAction", intent.exactAction()));
            String lastCoordinates = switch (project) {
                case "task-board" -> "45/0/8000 45/8000/8000 43/0/8000 30/104000/6000 30/109000/6000";
                case "log-analyzer" -> "12/8000/8000 12/48000/8000 12/288000/5600 45/8000/8000 45/24000/8000 38/0/4000 38/149962/4000 45/34500/3659";
                case "binary-inspector" -> "38/149962/8000 38/153609/8000 45/28503/6657 45/8000/3924";
                default -> throw new IllegalArgumentException(project);
            };
            var last = new R24Turn(0, 0, 0, 0, 0, lastCoordinates).calls(snapshot, sources, 1);
            var prior = new R24Turn(0, 0, 0, 0, 0, (prepare ? "30" : "38") + "/16170/8000")
                .calls(snapshot, sources, 0);
            var messages = new ArrayList<>(initial.messages());
            messages.set(messages.size() - 1, evidence.messages().getLast());
            var reader = evidence.reader(24);
            var before = mockResponses(prior.toArray(AiToolCall[]::new));
            reader.continueReview(messages, before, reader.read(before));
            var finalRead = mockResponses(last.toArray(AiToolCall[]::new));
            reader.continueReview(messages, finalRead, reader.read(finalRead));
            int bound = prepare ? 97963 : project.equals("log-analyzer") ? 181821 : 196606;
            int factsSize = prepare ? 28435 : project.equals("log-analyzer") ? 31569 : 31536;
            int full = wireWithR24FactsSize(copy(initial, messages, 1048576), factsSize, 1048576, Map.of()).length;
            if (full > bound) {
                assertEquals(full, assertThrows(ModelRequestBudgetException.class,
                    () -> wireWithR24FactsSize(copy(initial, messages, 1048576), factsSize, bound, Map.of())).encodedBytes());
                var touched = new Problem(ProblemCode.INVALID_INPUT, "AI_REQUEST_BUDGET", "No pre-send proof",
                    Map.of("maximumRequestBytes", Integer.toString(bound), "encodedRequestBytes", Integer.toString(full),
                        "generationAttemptCount", "1"), Optional.empty());
                var unchanged = List.copyOf(messages);
                assertFalse(reader.repackBeforeSend(messages, touched));
                assertEquals(unchanged, messages);
                assertTrue(reader.repackBeforeSend(messages, exactBudgetProblem(full, bound)));
            }
            byte[] encoded = wireWithR24FactsSize(copy(initial, messages, 1048576), factsSize, bound, Map.of());
            var visible = new LinkedHashMap<String, TreeMap<Integer, Integer>>();
            seedInlineSourceRanges(projected, visible);
            assertR24VisiblePages(encoded, snapshot, last, true, visible, sources);
            assertR30DeferredPages(reader, messages, snapshot, last, sources, visible);
            assertEquals(0, assisted.hosts.get());
            System.out.printf("R30_TOOLCHAIN_REPLAY project=%s fullEncoded=%d packedEncoded=%d originalCap=%d%n",
                project, full, encoded.length, bound);
        }
    }

    @Test
    void r31GoBuildUsesExactWriterAndTerminalReadBudget() throws Exception {
        try (var fixture = new MirroredInstallerTestFixture(); var assisted = new DeploymentNativeHelperPackageImpactTest.Assisted(fixture)) {
            var workspace = new DeploymentRemoteWorkspace("file-transfer-backend", "a".repeat(64), fixture.layout);
            var facts = new DeploymentProjectFacts(Path.of("."), workspace.applicationId(), DeploymentProjectKind.GO_SERVICE,
                DeploymentBuildToolType.GO_MODULE, List.of(), List.of(), List.of());
            var runtime = new DeploymentRuntimeDefinition.GoService("1.24.0", "file-transfer", "main.go",
                new DeploymentHealthCheck.Tcp(8080, 10, 2));
            var limits = new DeploymentBuildLimits(120, 32, 512, 65536, 128L * 1024 * 1024, false);
            String body = new GoBuildWriter().render(facts, runtime, workspace, limits);
            String policy = DeploymentMirrorTasks.policy(fixture.task);
            String input = DeploymentMirrorTasks.withMaterials(DeploymentMirrorRuntime.wrapBuild(body, policy, 120), fixture.task.id());
            String collector = DeploymentBuildOutputProtocol.script(workspace, 120, 32, 512, 65536, "ordinary",
                MirroredInstallerTestFixture.sha(input), "-", List.of(List.of("BUILD_OK")));
            try (var command = new CommandRequest(DeploymentManagedHelperBundle.verifiedCommand(collector, fixture.layout),
                input.getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(135), 65536)) {
                var intent = RemoteActions.command(fixture.server, fixture.model.configurationRevision, command)
                    .withSemantics(new ActionSemantics("deployment.build.run.v1",
                        Set.of(new ActionTarget(fixture.server.id(), "deployment-build", workspace.candidateRoot()))));
                var original = new LinkedHashMap<String, Object>(
                    new DeploymentFixedCommandProtocol(fixture.context).inspect(intent, command, fixture.layout, fixture.task));
                original.put("currentCommandEvidence", assisted.scope.effectFacts(intent, fixture.task));
                var evidence = DeploymentCommandSourceEvidence.project(DeploymentAiJson.facts(original, 1048576),
                    intent, command, fixture.layout).orElseThrow();
                var initial = r31DeniedApproval(fixture, assisted, original, intent, command);
                var projected = JSON.readTree(evidence.messages().getLast().text());
                var manifest = projected.path("buildReviewEvidence");
                var sources = new LinkedHashMap<String, String>();
                var runtimeSource = projected.at("/mirrorTransport/runtimeSource");
                for (var descriptor : List.of(runtimeSource, manifest.path("mirrorBootstrapShell"), manifest.path("stagedMirrorPolicy"))) {
                    String full = collect(evidence, descriptor, "buildReviewEvidence");
                    assertIdentity(descriptor, full);
                    sources.put(descriptor.path("sourcePointer").asText(), full);
                }
                var bodyFrame = manifest.path("buildBody");
                String inlineBody = evidence.messages().get(bodyFrame.path("ordinal").asInt()).text()
                    .substring(bodyFrame.path("headerCharacters").asInt());
                assertEquals(body, inlineBody);
                sources.put(bodyFrame.path("sourcePointer").asText(), inlineBody);
                var selected = manifest.path("selectedRuntimeRanges");
                assertTrue(selected.findValuesAsText("section").contains("goDependencyEnvironment"));
                assertTrue(selected.findValuesAsText("section").contains("goUnsupportedLockedFallback"));
                assertTrue(selected.findValuesAsText("section").contains("goRepositoryLifecycle"));
                assertTrue(selected.findValuesAsText("section").contains("class MirrorTransferLedger:"));
                assertTrue(selected.findValuesAsText("section").contains("mirrorRuntimeLauncher"));
                assertEquals(input, restoreValue(projected.path("completeExecutableInput"), sources));
                assertEquals(policy, sources.get(manifest.path("stagedMirrorPolicy").path("sourcePointer").asText()));
                var pointers = Map.of(30, Map.entry(runtimeSource.path("sourcePointer").asText(), DeploymentMirrorRuntime.runtimeSource()));
                assertR31Terminal(evidence, initial, projected, "buildReviewEvidence", pointers,
                    "30/64000/8000 30/68000/8000 30/16000/8000 30/24000/8000",
                    "30/32000/8000 30/56000/8000 30/68000/8000 30/76000/8000 30/196000/5000", 42221, 185460, "file-transfer");
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"task-board", "asset-lending", "log-analyzer", "binary-inspector"})
    void r31ToolchainTerminalReadsFitHistoricalPhysicalSendCeilings(String project) throws Exception {
        boolean nativeDependencies = project.equals("log-analyzer") || project.equals("binary-inspector");
        boolean java = project.equals("task-board");
        String phase = nativeDependencies ? "dependencies" : "prepare";
        try (var fixture = new MirroredInstallerTestFixture(); var assisted = new DeploymentNativeHelperPackageImpactTest.Assisted(fixture);
             var command = nativeDependencies ? request(phase, fixture) : diagnosedPreparation(fixture, java ? "JAVA" : "NODE", java ? "21" : "24")) {
            var intent = intent(phase, fixture, command);
            var original = new LinkedHashMap<String, Object>(
                new DeploymentFixedCommandProtocol(fixture.context).inspect(intent, command, fixture.layout, fixture.task));
            var commandEvidence = new LinkedHashMap<String, Object>(assisted.scope.effectFacts(intent, fixture.task));
            if (nativeDependencies) commandEvidence.put("currentNativeBuildPrerequisites", Map.of(
                "observedGap", Map.of("nativeBuildPrerequisitesMissing", true, "packageManager", "apt", "distro", "DEBIAN"),
                "currentToolchainPlan", Map.of("runtimeKind", "RustService"), "fixedTransaction", original.get("toolchainDependencies")));
            original.put("currentCommandEvidence", commandEvidence);
            var evidence = DeploymentCommandSourceEvidence.project(DeploymentAiJson.facts(original, 1048576),
                intent, command, fixture.layout).orElseThrow();
            var initial = r31DeniedApproval(fixture, assisted, original, intent, command);
            var projected = JSON.readTree(evidence.messages().getLast().text());
            var sources = r24Sources(initial, projected, fixture);
            if (nativeDependencies) {
                var manifest = projected.path(PHASE);
                var descriptor = manifest.path("dependencyWorker");
                String worker = collect(evidence, descriptor, PHASE);
                assertIdentity(descriptor, worker);
                addReplaySource(sources, descriptor, worker);
                var workerSources = Map.of(descriptor.path("sourcePointer").asText(), worker);
                assertArrayEquals(command.stdin(), restoreValue(projected.path("completeExecutableInput"), workerSources)
                    .getBytes(StandardCharsets.UTF_8));
                var actualShell = manifest.path("dependencySystemShell");
                String shell = collect(evidence, actualShell, PHASE);
                String runtime = DeploymentMirrorRuntime.runtimeSource();
                assertEquals(shell, JSON.readTree(runtime.substring(actualShell.path("literalOffset").asInt(),
                    actualShell.path("literalNext").asInt())).textValue());
                assertTrue(shell.contains("wtl_apt_install_frozen() {"));
                var selected = manifest.path("selectedAptRanges");
                var authentication = PreparationSourceRange.aptDependencyWorker(worker).stream()
                    .filter(range -> range.section().equals("dependencyAptAuthentication")).findFirst().orElseThrow();
                var requiredCoverage = new LinkedHashMap<String, TreeMap<Integer, Integer>>();
                for (var range : manifest.path("requiredAptRanges"))
                    if (range.path("pointer").equals(descriptor.path("sourcePointer")))
                        addR24Range(requiredCoverage, range.path("pointer").asText(), range.path("offset").asInt(), range.path("next").asInt());
                var requiredAuthentication = requiredCoverage.get(descriptor.path("sourcePointer").asText()).floorEntry(authentication.offset());
                assertNotNull(requiredAuthentication);
                assertTrue(requiredAuthentication.getValue() >= authentication.next());
                assertTrue(selected.findValuesAsText("section").contains("def dependency_supervise("));
                assertTrue(selected.findValuesAsText("section").contains("aptSignedUpdateToFrozenInstall"));
                assertTrue(selected.findValuesAsText("section").contains("aptSecurityAndFrozenTransaction"));
                assertTrue(projected.at("/dependencyPublicPolicy/points/APT_DEBIAN").isArray());
                assertTrue(projected.at("/dependencyPublicPolicy/points/DNF_CENTOS_STREAM").isArray());
            }
            switch (project) {
                case "task-board" -> assertR31Terminal(evidence, initial, projected, PHASE, sources,
                    "46/180000/8000 46/172000/8000 30/13643/2527 30/24170/8000 30/32170/8000 30/0/3984 43/8000/1272 46/185000/8000",
                    "30/3984/8000", 28435, 75544, project);
                case "asset-lending" -> assertR31Terminal(evidence, initial, projected, PHASE, sources,
                    "30/19984/4000", "30/3984/8000", 29880, 81438, project);
                case "log-analyzer" -> assertR31Terminal(evidence, initial, projected, PHASE, sources,
                    "45/8000/8000 45/28000/8000 45/36000/2159",
                    "45/16000/8000 45/20000/8000 45/24000/8000 38/200716/5000 38/76140/8000 38/108000/6000",
                    32341, 141310, project);
                case "binary-inspector" -> assertR31Terminal(evidence, initial, projected, PHASE, sources,
                    "45/30000/8000", "45/0/8000", 32311, 144865, project);
                default -> throw new AssertionError(project);
            }
        }
    }

    private static AiRequest r31DeniedApproval(MirroredInstallerTestFixture fixture, DeploymentNativeHelperPackageImpactTest.Assisted assisted,
                                               Map<String, Object> original, ActionIntent intent, CommandRequest command) throws Exception {
        reply(fixture, verdict("r31-independent-denial", "DENY"));
        var model = new DeploymentAiJson(assisted.context);
        assertEquals("DENY", model.askCommand("Inspect exact current request", original, fixture.model.configurationRevision,
            fixture.task, DeploymentReviewDecision::malformed, intent, command).path("decision").asText());
        assertEquals(0, assisted.hosts.get());
        var sent = fixture.model.requests.getFirst();
        // The manual historical replays create a fresh Reader at the fixed baseline, without an initial supplement.
        return copy(sent, sent.messages().stream().filter(message -> !nodeInitialSupplement(message)).toList(), sent.maxRequestBytes());
    }

    @ParameterizedTest
    @ValueSource(strings = {"R32", "R33", "R49"})
    void aptReviewIncludesCompleteSupervisorAtObservedBudgets(String round) throws Exception {
        try (var fixture = new MirroredInstallerTestFixture();
             var command = request("dependencies", fixture);
             var assisted = new DeploymentNativeHelperPackageImpactTest.Assisted(fixture)) {
            var intent = intent("dependencies", fixture, command);
            var protocol = new DeploymentFixedCommandProtocol(fixture.context);
            var original = new LinkedHashMap<String, Object>(protocol.inspect(intent, command, fixture.layout, fixture.task));
            var facts = new LinkedHashMap<String, Object>(assisted.scope.effectFacts(intent, fixture.task));
            facts.put("currentNativeBuildPrerequisites", Map.of(
                "observedGap", Map.of("nativeBuildPrerequisitesMissing", true, "packageManager", "apt", "distro", "UBUNTU"),
                "currentToolchainPlan", Map.of("runtimeKind", "RustService"), "fixedTransaction", original.get("toolchainDependencies")));
            original.put("currentCommandEvidence", facts);
            var evidence = DeploymentCommandSourceEvidence.project(DeploymentAiJson.facts(original, 1048576),
                intent, command, fixture.layout).orElseThrow();
            var initial = r31DeniedApproval(fixture, assisted, original, intent, command);
            var projected = JSON.readTree(evidence.messages().getLast().text());
            var manifest = projected.path(PHASE);
            String snapshot = manifest.path("snapshotSha256").asText();
            var workerDescriptor = manifest.path("dependencyWorker");
            String worker = collect(evidence, workerDescriptor, PHASE);
            assertIdentity(workerDescriptor, worker);
            var systemShell = manifest.path("dependencySystemShell");
            String shell = collect(evidence, systemShell, PHASE);
            assertIdentity(systemShell, shell);
            assertArrayEquals(command.stdin(), restoreValue(projected.path("completeExecutableInput"),
                Map.of(workerDescriptor.path("sourcePointer").asText(), worker)).getBytes(StandardCharsets.UTF_8));
            var aptFrame = manifest.path("dependencyAptSelection");
            String selectionFrame = evidence.messages().get(aptFrame.path("ordinal").asInt()).text();
            String selected = selectionFrame.substring(aptFrame.path("headerCharacters").asInt());
            var coverage = new LinkedHashMap<String, TreeMap<Integer, Integer>>();
            String workerPointer = workerDescriptor.path("sourcePointer").asText();
            String shellPointer = systemShell.path("sourcePointer").asText();
            var exactSources = Map.of(workerPointer, worker, shellPointer, shell);
            for (var section : manifest.path("selectedAptRanges")) {
                String pointer = section.path("sourcePointer").asText();
                int offset = section.path("offset").asInt(), next = section.path("next").asInt();
                int start = section.path("startCharacter").asInt();
                if (exactSources.containsKey(pointer))
                    assertEquals(exactSources.get(pointer).substring(offset, next), selected.substring(start, start + next - offset));
                addR24Range(coverage, pointer, offset, next);
            }
            var sourceAuthentication = new LinkedHashMap<String, TreeMap<Integer, Integer>>();
            for (String anchor : List.of("def dependency_url(", "def dependency_key(")) {
                var body = PreparationSourceRange.python(worker, anchor);
                addR24Range(sourceAuthentication, workerPointer, body.offset(), body.next());
            }
            int uriStart = shell.indexOf("wtl_apt_source_security() {\n");
            int uriEnd = shell.indexOf("\nwtl_apt_sandbox_identity() {", uriStart);
            assertTrue(uriStart >= 0 && uriEnd > uriStart);
            addR24Range(sourceAuthentication, shellPointer, uriStart, uriEnd);
            assertR24Coverage(sourceAuthentication, coverage);
            var required = manifest.path("requiredAptRanges");
            assertTrue(required.isArray() && !required.isEmpty());
            var allAptEvidence = new LinkedHashMap<String, TreeMap<Integer, Integer>>();
            coverage.forEach((pointer, spans) -> allAptEvidence.put(pointer, new TreeMap<>(spans)));
            for (var section : required)
                addR24Range(allAptEvidence, section.path("pointer").asText(), section.path("offset").asInt(), section.path("next").asInt());
            var supervisor = PreparationSourceRange.python(worker, "def dependency_supervise(");
            assertTrue(coverage.get(workerPointer).floorEntry(supervisor.offset()).getValue() >= supervisor.next());
            var decoder = PreparationSourceRange.python(worker, "def dependency_text(");
            assertR24Coverage(Map.of(workerPointer, new TreeMap<>(Map.of(decoder.offset(), decoder.next()))), allAptEvidence);
            for (String function : List.of("dependency_bytes", "dependency_registered", "dependency_apt_hook_owners",
                "dependency_apt_binaries", "dependency_apt_options", "dependency_validate_dpkg", "dependency_validate_apt",
                "dependency_supervise", "dependency_static_identity", "dependency_stop_owned", "dependency_manifest",
                "dependency_observe_startup", "dependency_events_read", "dependency_open_core_events", "dependency_validate_ownership",
                "dependency_retain_ownership", "dependency_ack_record", "dependency_validate_ack", "dependency_result")) {
                String anchor = "def " + function + "(";
                var body = PreparationSourceRange.python(worker, anchor);
                assertEquals(body.offset(), workerDescriptor.path("locations").path(anchor).asInt(-1));
                var span = allAptEvidence.get(workerPointer).floorEntry(body.offset());
                assertNotNull(span, function);
                assertTrue(span.getValue() >= body.next(), function);
            }
            for (String marker : List.of("PRE_ACK_RUNNING_IDENTITY", "proof = dependency_completion_proof(",
                "failureCleanup", "process.wait(timeout=1)", "dependency_write(base / 'receipt.json'"))
                assertTrue(selected.contains(marker), marker);
            String owners = "\nHOOK_OWNERS = ";
            int ownerOffset = worker.indexOf(owners);
            assertTrue(ownerOffset >= 0);
            assertEquals(ownerOffset, worker.lastIndexOf(owners));
            assertEquals(ownerOffset, workerDescriptor.path("locations").path(owners).asInt(-1));
            for (String anchor : List.of("HOOK_OWNERS = frozenset(", "UBUNTU_COMMAND_NOT_FOUND_HOOK_OWNERS = ",
                    "APT_BINARY_PATHS = ", "def dependency_validate_dpkg()")) {
                int offset = worker.indexOf(anchor);
                assertTrue(offset >= 0, anchor);
                assertTrue(allAptEvidence.get(workerPointer).floorEntry(offset).getValue() >= offset + anchor.length(), anchor);
            }
            var sources = r24Sources(initial, projected, fixture);
            addReplaySource(sources, workerDescriptor, worker);
            addReplaySource(sources, systemShell, shell);
            String coordinates = switch (round) {
                case "R32" -> "46/0/8000 46/8000/8000 46/16000/8000 46/24000/8000 46/32000/2019 41/53618/8000 41/59371/8000 41/68426/8000";
                case "R33" -> "46/0/8000 46/8000/8000 46/16000/8000 46/24000/8000 45/11000/8000 45/19000/8000 45/27000/8000 45/34000/4159";
                // Binary Inspector's first eight reads skipped both identity guards and the shell pointer.
                case "R49" -> "41/13199/580 41/18100/1505 41/24509/968 41/29684/1056 41/49486/751 41/50316/8000 41/58316/1055 41/61669/8000";
                default -> throw new AssertionError(round);
            };
            var calls = new R24Turn(0, 0, 0, 0, 0, coordinates).calls(snapshot, sources, 0);
            var messages = new ArrayList<>(initial.messages());
            int selectionIndex = 1 + aptFrame.path("ordinal").asInt();
            assertTrue(messages.get(selectionIndex).text().endsWith(selected), "The product request must include the exact APT selection");
            // Use the same snapshot in the fixture frame header, current facts, and replayed tool calls.
            messages.set(selectionIndex, new AiMessage(AiRole.USER, selectionFrame, List.of()));
            messages.set(messages.size() - 1, evidence.messages().getLast());
            boolean r33 = round.equals("R33"), r49 = round.equals("R49");
            int initialCap = r49 ? 988706 : r33 ? 1031205 : 1048576;
            int cap = r49 ? 955702 : r33 ? 999301 : 1048576;
            int factsSize = r49 ? 41855 : r33 ? 40923 : 39876;
            byte[] firstEncoded = wireWithR24FactsSize(copy(initial, messages, initialCap), factsSize, initialCap, Map.of());
            assertTrue(JSON.readTree(firstEncoded).path("input").findValuesAsText("text").contains(selectionFrame));
            var reader = evidence.reader(16);
            Map<UUID, String> continuations = new LinkedHashMap<>();
            var nativeCalls = calls.toArray(AiToolCall[]::new);
            if (r33) replyAtTotalBytes(fixture, continuations, 11319, "OPENAI_RESPONSES", nativeCalls);
            else retainReply(fixture, continuations, nativeOutput("", nativeCalls), "OPENAI_RESPONSES", nativeCalls);
            var reads = fixture.model.replies.remove().value().orElseThrow();
            var results = reader.read(reads);
            assertTrue(results.stream().noneMatch(AiToolResult::error));
            reader.continueReview(messages, reads, results);
            byte[] encoded = wireWithR24FactsSize(copy(initial, messages, cap), factsSize, cap, continuations);
            assertTrue(JSON.readTree(encoded).path("input").findValuesAsText("text").contains(selectionFrame));
            seedInlineSourceRanges(projected, coverage);
            assertR24VisiblePages(encoded, snapshot, calls, true, coverage, sources);
            var requested = new LinkedHashMap<String, TreeMap<Integer, Integer>>();
            collectRequestedCoverage(requested, calls, sources);
            assertR24Coverage(requested, coverage);
            System.out.printf("%s_APT_REPLAY fixedEncoded=%d continuationEncoded=%d cap=%d%n", round, firstEncoded.length, encoded.length, cap);
        }
    }

    private static void assertR31Terminal(DeploymentCommandSourceEvidence evidence, AiRequest initial, JsonNode projected,
                                         String phase, Map<Integer, Map.Entry<String, String>> sources, String prior,
                                         String last, int originalFactsSize, int bound, String project) throws Exception {
        String snapshot = projected.at("/" + phase + "/snapshotSha256").asText();
        var messages = new ArrayList<>(initial.messages());
        messages.set(messages.size() - 1, evidence.messages().getLast());
        var budgetedFacts = (ObjectNode) projected.deepCopy();
        int extra = originalFactsSize - budgetedFacts.toString().length() - 24;
        if (extra > 0) budgetedFacts.put("replayFixturePadding", "x".repeat(extra));
        var fixedMessages = new ArrayList<>(messages);
        fixedMessages.set(fixedMessages.size() - 1, new AiMessage(AiRole.USER, budgetedFacts.toString(), List.of()));
        int fixed = wire(copy(initial, fixedMessages, 1048576), bound, Map.of()).length;
        var reader = evidence.reader(24);
        var priorRead = mockResponses(new R24Turn(0, 0, 0, 0, 0, prior).calls(snapshot, sources, 0).toArray(AiToolCall[]::new));
        reader.continueReview(messages, priorRead, reader.read(priorRead));
        var calls = new R24Turn(0, 0, 0, 0, 0, last).calls(snapshot, sources, 1);
        var finalRead = mockResponses(calls.toArray(AiToolCall[]::new));
        reader.continueReview(messages, finalRead, reader.read(finalRead));
        messages.set(messages.size() - 1, fixedMessages.getLast());
        int full = wire(copy(initial, messages, 1048576), 1048576, Map.of()).length;
        if (full > bound) {
            assertTrue(reader.repackBeforeSend(messages, exactBudgetProblem(full, bound)),
                () -> "Cannot repack " + project + ": " + full + " > " + bound);
        }
        byte[] encoded = wire(copy(initial, messages, 1048576), bound, Map.of());
        var visible = new LinkedHashMap<String, TreeMap<Integer, Integer>>();
        seedInlineSourceRanges(projected, visible);
        for (String sections : List.of("selectedRuntimeRanges", "selectedAptRanges")) {
            for (var range : projected.path(phase).path(sections))
                addR24Range(visible, range.path("sourcePointer").asText(), range.path("offset").asInt(), range.path("next").asInt());
        }
        assertR24VisiblePages(encoded, snapshot, calls, true, visible, sources);
        assertR30DeferredPages(reader, messages, snapshot, calls, sources, visible);
        System.out.printf("R31_TERMINAL_REPLAY project=%s fixedEncoded=%d fullEncoded=%d packedEncoded=%d originalCap=%d%n",
            project, fixed, full, encoded.length, bound);
    }

    private static Problem exactBudgetProblem(int encoded, int bound) {
        return new Problem(ProblemCode.INVALID_INPUT, "AI_REQUEST_BUDGET", "Encoded request exceeds the local limit",
            Map.of("maximumRequestBytes", Integer.toString(bound), "encodedRequestBytes", Integer.toString(encoded)), Optional.empty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVE", "DENY", "INVALID", "LOCAL_LIMIT"})
    void r36FileTransferTraceGetsOneIndependentVerdictWithUnchangedVisibleSource(String result) throws Exception {
        try (var fixture = new MirroredInstallerTestFixture();
             var assisted = new DeploymentNativeHelperPackageImpactTest.Assisted(fixture);
             var request = diagnosedPreparation(fixture, "NODE", "24")) {
            var intent = intent("prepare", fixture, request);
            var facts = new DeploymentFixedCommandProtocol(fixture.context).inspect(intent, request, fixture.layout, fixture.task);
            var initial = r31DeniedApproval(fixture, assisted, facts, intent, request);
            String snapshot = JSON.readTree(initial.messages().getLast().text()).at("/" + PHASE + "/snapshotSha256").asText();
            JsonNode trace;
            try (var input = getClass().getResourceAsStream("r36-file-transfer-prepare-trace.json")) {
                assertNotNull(input);
                trace = JSON.readTree(input);
            }
            var frames = trace.path("frames");
            int pages = 0;
            for (var frame : frames) {
                var calls = new ArrayList<AiToolCall>();
                for (var row : frame.path("reads"))
                    calls.add(read("r36-observed-" + pages++, snapshot, row.get(0).asText(), row.get(1).asInt(), row.get(2).asInt()));
                fixture.model.replies.add(success(mockResponses(calls.toArray(AiToolCall[]::new))));
            }
            assertEquals(124, pages);
            var submission = switch (result) {
                case "APPROVE" -> new AiToolCall("r37-independent-decision", "submit_result",
                    "{\"decision\":\"APPROVE\",\"requiresUserConfirmation\":true,\"reason\":\"Controlled approval\"}");
                case "DENY" -> verdict("r37-independent-decision", result);
                default -> new AiToolCall("r37-malformed-verdict", "submit_result", "{}");
            };
            reply(fixture, submission);
            int start = fixture.model.requests.size();
            var finalAttempt = new java.util.concurrent.atomic.AtomicInteger();
            int bound = trace.path("correctionBound").asInt();
            int factsSize = trace.path("factsCharacters").asInt();
            var bounded = wireBudgetContext(assisted.context, Map.of(), factsSize, () -> {
                int index = fixture.model.requests.size() - start;
                if (index < frames.size()) return frames.get(index).path("bound").asInt();
                finalAttempt.incrementAndGet();
                return result.equals("LOCAL_LIMIT") ? 32000 : bound;
            });
            var model = new DeploymentAiJson(bounded, 3);
            Callable<JsonNode> review = () -> model.askCommand("Inspect exact current request", facts, fixture.model.configurationRevision,
                fixture.task, DeploymentReviewDecision::malformed, intent, request);
            if (result.equals("LOCAL_LIMIT")) {
                var refused = assertThrows(ApiException.class, review::call);
                assertEquals("AI_REQUEST_BUDGET", refused.problem().eventCode());
                assertEquals(start + frames.size(), fixture.model.requests.size());
            } else {
                if (result.equals("INVALID")) assertThrows(IOException.class, review::call);
                else {
                    var decision = review.call();
                    assertEquals(result, decision.path("decision").asText());
                    assertEquals(result.equals("APPROVE"), decision.path("requiresUserConfirmation").asBoolean());
                }
                assertEquals(start + frames.size() + 1, fixture.model.requests.size());
                var finalRequest = fixture.model.requests.getLast();
                var preceding = fixture.model.requests.get(fixture.model.requests.size() - 2);
                assertEquals(Optional.of("submit_result"), finalRequest.requiredToolName());
                assertEquals(List.of("submit_result"), finalRequest.tools().stream().map(AiToolDefinition::name).toList());
                assertEquals(preceding.messages().subList(1, preceding.messages().size()),
                    finalRequest.messages().subList(1, finalRequest.messages().size()));
                assertEquals(preceding.timeout(), finalRequest.timeout());
                assertEquals(preceding.maxOutputTokens(), finalRequest.maxOutputTokens());
                assertEquals(0, finalRequest.generationRecoveryAttempts());
                byte[] encoded = wireWithR24FactsSize(finalRequest, factsSize, bound, Map.of());
                assertTrue(encoded.length <= bound);
                int before = wireWithR24FactsSize(preceding, factsSize, 1048576, Map.of()).length;
                int projectedOriginal = frames.get(frames.size() - 1).path("sent").asInt() + encoded.length - before;
                assertTrue(projectedOriginal <= bound, "The fixed framing delta must also fit the original measured request");
                System.out.printf("R37_FILE_TRANSFER_CORRECTION originalFrames=%d pages=%d result=%s bytes=%d originalShapeBytes=%d cap=%d%n",
                    frames.size(), pages, result, encoded.length, projectedOriginal, bound);
            }
            assertEquals(1, finalAttempt.get());
            assertEquals(0, assisted.hosts.get(), "A verdict alone is never a host grant");
        }
    }

    @Test
    void repeatedAptPagesCannotOfferVerdictOnlyWhileMandatorySourceIsMissing() throws Exception {
        try (var fixture = new MirroredInstallerTestFixture();
             var assisted = new DeploymentNativeHelperPackageImpactTest.Assisted(fixture);
             var command = request("dependencies", fixture)) {
            var action = intent("dependencies", fixture, command);
            var facts = new LinkedHashMap<String, Object>(
                new DeploymentFixedCommandProtocol(fixture.context).inspect(action, command, fixture.layout, fixture.task));
            var effects = new LinkedHashMap<String, Object>(assisted.scope.effectFacts(action, fixture.task));
            effects.put("currentNativeBuildPrerequisites", Map.of(
                "observedGap", Map.of("nativeBuildPrerequisitesMissing", true, "packageManager", "apt", "distro", "UBUNTU"),
                "currentToolchainPlan", Map.of("runtimeKind", "RustService"), "fixedTransaction", facts.get("toolchainDependencies")));
            facts.put("currentCommandEvidence", effects);
            var initial = r31DeniedApproval(fixture, assisted, facts, action, command);
            var manifest = JSON.readTree(initial.messages().getLast().text()).path(PHASE);
            assertFalse(manifest.path("requiredAptRanges").isEmpty());
            assertTrue(manifest.path("selectedAptRanges").findValuesAsText("section").containsAll(List.of(
                "def dependency_mirror_environment(", "def dependency_static_identity(", "def dependency_validate_ownership(",
                "def dependency_events_read(", "def dependency_ack_record(", "def dependency_result(",
                "dependencyAptBinaryPaths", "def dependency_protected(")));
            String snapshot = manifest.path("snapshotSha256").asText();
            String inline = manifest.path("dependencyAptSelection").path("sourcePointer").asText();
            for (int i = 0; i < 3; i++)
                fixture.model.replies.add(success(mockResponses(read("apt-inline-" + i, snapshot, inline, 0, 64))));
            var model = new DeploymentAiJson(assisted.context);
            var stalled = assertThrows(DeploymentCommandReviewModel.Failure.class, () -> model.askCommand(
                "Inspect exact current request", facts, fixture.model.configurationRevision, fixture.task,
                DeploymentReviewDecision::malformed, action, command));
            assertTrue(stalled.evidenceReadMadeNoProgress());
            assertEquals(4, fixture.model.requests.size());
            assertTrue(fixture.model.requests.stream().noneMatch(request -> request.requiredToolName().isPresent()));
            assertEquals(0, assisted.hosts.get());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"task-board", "file-transfer", "asset-lending", "log-analyzer", "binary-inspector"})
    void r35ObservedApprovalCursorsRetainExactEvidenceWithinTheRecordedWireBounds(String project) throws Exception {
        JsonNode trace;
        try (var input = getClass().getResourceAsStream("r35-approval-source-reads.json")) {
            assertNotNull(input);
            trace = JSON.readTree(input);
        }
        var rows = trace.path("projects").findValuesAsText("project");
        var original = trace.path("projects").get(rows.indexOf(project));
        boolean binding = original.path("operation").asText().contains(".bind.");
        String phase = binding ? "bind" : "prepare";
        try (var fixture = new MirroredInstallerTestFixture();
             var assisted = new DeploymentNativeHelperPackageImpactTest.Assisted(fixture);
             var command = switch (project) {
                 case "task-board" -> diagnosedPreparation(fixture, "JAVA", "21");
                 case "file-transfer" -> diagnosedPreparation(fixture, "NODE", "24");
                 case "asset-lending" -> r26Binding(fixture, "DOTNET", "10.0.300");
                 case "log-analyzer" -> diagnosedPreparation(fixture, "RUST", "1.95");
                 case "binary-inspector" -> r26Binding(fixture, "RUST", "1.95.0");
                 default -> throw new AssertionError(project);
             }) {
            var action = intent(phase, fixture, command);
            var facts = new DeploymentFixedCommandProtocol(fixture.context).inspect(action, command, fixture.layout, fixture.task);
            var evidence = DeploymentCommandSourceEvidence.project(DeploymentAiJson.facts(facts, 1048576), action, command,
                fixture.layout).orElseThrow();
            var initial = r31DeniedApproval(fixture, assisted, facts, action, command);
            var messages = new ArrayList<>(initial.messages());
            messages.set(messages.size() - 1, evidence.messages().getLast());
            var projected = JSON.readTree(messages.getLast().text());
            var manifest = projected.path(PHASE);
            String snapshot = manifest.path("snapshotSha256").asText();
            var sources = replaySources(fixture, evidence, projected);
            var reader = evidence.reader(256);
            var continuations = new LinkedHashMap<UUID, String>();
            var pending = new LinkedHashMap<String, TreeMap<Integer, Integer>>();
            int physicalReads = 0, peak = 0, retainedFrames = 0, rejectedRequests = 0;
            boolean repeatLimit = false;
            boolean finalBatchRejectedCursor = false;
            int minimum = original.path("firstFactsCharacters").asInt();
            int bound = 0;
            for (var frame : original.path("frames")) {
                bound = frame.path("bound").asInt();
                byte[] encoded = wireWithR24FactsSize(copy(initial, messages, bound), minimum, bound, continuations);
                peak = Math.max(peak, encoded.length);
                var visible = replayRetainedRanges(encoded, snapshot, sources);
                if (visible.isPresent()) {
                    retainedFrames++;
                    assertReplayRangesPresent(pending, visible.orElseThrow(), projected);
                }
                var calls = new ArrayList<AiToolCall>();
                for (var range : frame.path("reads")) {
                    String pointer = range.get(0).asText();
                    calls.add(read("r35-" + physicalReads++, snapshot, pointer, range.get(1).asInt(), range.get(2).asInt()));
                }
                var batch = calls.toArray(AiToolCall[]::new);
                retainReply(fixture, continuations, nativeOutput("", batch), "OPENAI_RESPONSES", batch);
                var request = fixture.model.replies.remove().value().orElseThrow();
                try {
                    var results = reader.read(request);
                    finalBatchRejectedCursor = results.stream().anyMatch(AiToolResult::error);
                    for (int index = 0; index < results.size(); index++) {
                        if (results.get(index).error()) {
                            rejectedRequests++;
                            continue;
                        }
                        var page = pagePayload(results.get(index).text());
                        String pointer = page.path("pointer").asText();
                        String body = sources.get(pointer);
                        var value = page.path("value");
                        assertNotNull(body, pointer);
                        assertPageRanges(body, value);
                        if (value.path("offset").asInt() < value.path("next").asInt())
                            addR24Range(pending, pointer, value.path("offset").asInt(), value.path("next").asInt());
                    }
                    reader.continueReview(messages, request, results);
                } catch (DeploymentCommandReviewModel.Failure stopped) {
                    var diagnostic = DeploymentCommandReviewModel.failureDiagnostics(stopped);
                    assertEquals("EVIDENCE_NO_PROGRESS", diagnostic.get("reviewCode"), project);
                    assertEquals("0", diagnostic.get("reviewNewCharacters"), project);
                    assertEquals("2", diagnostic.get("reviewRepeatedBatches"), project);
                    assertFalse(reader.hasUnfulfilledApprovalEvidence(), "The rejected fixed replay had already received its requested bytes");
                    repeatLimit = true;
                    break;
                }
            }
            if (!repeatLimit) {
                byte[] encoded = wireWithR24FactsSize(copy(initial, messages, bound), minimum, bound, continuations);
                var finalVisible = replayRetainedRanges(encoded, snapshot, sources).orElseThrow();
                assertReplayRangesPresent(pending, finalVisible, projected);
                reader.read(response(Optional.empty(), verdict("r36-candidate-verdict", "APPROVE")));
                assertEquals(finalBatchRejectedCursor, reader.hasUnfulfilledApprovalEvidence(),
                    "A cursor outside the current fixture still requires correction after all valid ranges are visible");
            }
            assertTrue(retainedFrames > 0);
            assertEquals(0, assisted.hosts.get());
            System.out.printf("R36_OBSERVED_BROWSE project=%s cursors=%d rejectedCursors=%d retainedFrames=%d peak=%d repeatLimit=%b%n",
                project, physicalReads, rejectedRequests, retainedFrames, peak, repeatLimit);
        }
    }

    private static Map<String, String> replaySources(MirroredInstallerTestFixture fixture,
                                                     DeploymentCommandSourceEvidence evidence, JsonNode projected) throws Exception {
        var sources = new LinkedHashMap<String, String>();
        var manifest = projected.path(PHASE);
        for (String name : List.of("mirrorBootstrapShell", "mirrorPolicySource", "installedHelperSource",
            "preparationSelection", "nodePreparationSelection")) {
            var descriptor = manifest.path(name);
            if (!descriptor.isObject()) continue;
            String source = switch (name) {
                case "mirrorBootstrapShell" -> r24Shell(fixture);
                case "mirrorPolicySource" -> DeploymentMirrorTasks.policy(fixture.task);
                case "installedHelperSource" -> DeploymentManagedHelperBundle.renderScript(fixture.layout);
                default -> descriptor.has("ordinal") ? evidence.messages().get(descriptor.path("ordinal").asInt()).text()
                    .substring(descriptor.path("headerCharacters").asInt()) : collect(evidence, descriptor, PHASE);
            };
            assertIdentity(descriptor, source);
            sources.put(descriptor.path("sourcePointer").asText(), source);
        }
        var runtime = manifest.has("mirrorRuntime") ? manifest.path("mirrorRuntime") : projected.at("/mirrorTransport/runtimeSource");
        String body = DeploymentMirrorRuntime.runtimeSource();
        assertIdentity(runtime, body);
        sources.put(runtime.path("sourcePointer").asText(), body);
        return sources;
    }

    private static Optional<Map<String, TreeMap<Integer, Integer>>> replayRetainedRanges(byte[] encoded, String snapshot,
        Map<String, String> sources) throws Exception {
        String marker = "WTL-COMMAND-RETAINED-SOURCE-1\n";
        for (var item : JSON.readTree(encoded).path("input")) {
            String text = item.at("/content/0/text").asText();
            if (!text.startsWith(marker)) continue;
            int last = text.indexOf('\n', marker.length());
            var header = JSON.readTree(text.substring(marker.length(), last));
            assertEquals(snapshot, header.path("snapshotSha256").asText());
            var ranges = new LinkedHashMap<String, TreeMap<Integer, Integer>>();
            int start = last + 1;
            for (var row : header.path("ranges")) {
                String pointer = row.path("pointer").asText();
                int offset = row.path("offset").asInt(), next = row.path("next").asInt();
                assertNotNull(sources.get(pointer), pointer);
                int end = start + next - offset;
                String actual = text.substring(start, end);
                assertEquals(sources.get(pointer).substring(offset, next), actual, pointer);
                var prior = ranges.getOrDefault(pointer, new TreeMap<Integer, Integer>());
                var uncovered = DeploymentEvidencePages.missing(sources.get(pointer), offset, next, prior);
                assertTrue(uncovered.size() == 1 && uncovered.getFirst().get("text").equals(actual),
                    "Retained ranges must not repeat raw source bytes");
                addR24Range(ranges, pointer, offset, next);
                start = end;
            }
            assertEquals(text.length(), start);
            return Optional.of(ranges);
        }
        return Optional.empty();
    }

    private static void assertReplayRangesPresent(Map<String, TreeMap<Integer, Integer>> requested,
                                                  Map<String, TreeMap<Integer, Integer>> visible, JsonNode projected) {
        seedInlineSourceRanges(projected, visible);
        var node = projected.path(PHASE);
        for (String descriptor : List.of("preparationSelection", "nodePreparationSelection", "bindingSelection")) {
            var frame = node.path(descriptor);
            if (frame.has("ordinal"))
                addR24Range(visible, frame.path("sourcePointer").asText(), 0, frame.path("characters").asInt());
        }
        for (String name : List.of("selectedNodeRanges", "selectedBindingRanges")) {
            for (var range : node.path(name))
                addR24Range(visible, range.path("sourcePointer").asText(), range.path("offset").asInt(), range.path("next").asInt());
        }
        assertR24Coverage(requested, visible);
    }

    @ParameterizedTest
    @ValueSource(strings = {"NATIVE_BUILD", "NATIVE_BUILD_APT_3"})
    void aptRequiredEvidenceMustReachTheIndependentReviewerBeforeApproval(String profile) throws Exception {
        try (var fixture = new MirroredInstallerTestFixture();
             var assisted = new DeploymentNativeHelperPackageImpactTest.Assisted(fixture);
             var command = dependencyRequest(fixture, profile)) {
            var action = RemoteActions.command(fixture.server, "a".repeat(64), command).withSemantics(new ActionSemantics(
                DeploymentToolchainDependencies.OPERATION,
                DeploymentToolchainDependencies.dependencyTargets(fixture.server, profile, "b".repeat(32), fixture.layout)));
            var facts = new LinkedHashMap<String, Object>(
                new DeploymentFixedCommandProtocol(fixture.context).inspect(action, command, fixture.layout, fixture.task));
            var effects = new LinkedHashMap<String, Object>(assisted.scope.effectFacts(action, fixture.task));
            effects.put("currentNativeBuildPrerequisites", Map.of(
                "observedGap", Map.of("nativeBuildPrerequisitesMissing", true, "packageManager", "apt", "distro", "UBUNTU"),
                "currentToolchainPlan", Map.of("runtimeKind", "RustService"), "fixedTransaction", facts.get("toolchainDependencies")));
            facts.put("currentCommandEvidence", effects);
            var model = new DeploymentAiJson(assisted.context);
            reply(fixture, verdict("apt-early-approval", "APPROVE"));
            var early = assertThrows(DeploymentCommandReviewModel.Failure.class, () -> model.askCommand("Inspect exact current request", facts,
                fixture.model.configurationRevision, fixture.task, DeploymentReviewDecision::malformed, action, command));
            assertEquals("EVIDENCE_INCOMPLETE_VERDICT", DeploymentCommandReviewModel.failureDiagnostics(early).get("reviewCode"));
            assertEquals("REQUIRED_SOURCE_MISSING", DeploymentCommandReviewModel.failureDiagnostics(early).get("reviewEvidenceBlock"));
            assertEquals(0, assisted.hosts.get());

            var initial = fixture.model.requests.getFirst();
            var evidence = DeploymentCommandSourceEvidence.project(DeploymentAiJson.facts(facts, 1048576), action, command,
                fixture.layout).orElseThrow();
            var initialFacts = JSON.readTree(initial.messages().getLast().text());
            var manifest = initialFacts.path(PHASE);
            if (DeploymentToolchainDependencies.isScopedNativeAptProfile(profile)) {
                assertEquals(JSON.valueToTree(List.of("cmake", "ninja-build")),
                    initialFacts.at("/toolchainDependencies/nativeTransaction/requestedPackages/APT"));
                assertTrue(initialFacts.at("/toolchainDependencies/nativeTransaction/requestedPackages/DNF").isMissingNode());
                assertFalse(initialFacts.path("dependencyPublicPolicy").path("points").properties().stream()
                    .anyMatch(entry -> entry.getKey().startsWith("DNF_")));
                assertTrue(manifest.path("selectedAptRanges").findValuesAsText("section").contains("scopedNativeAptPackagePolicy"));
                assertTrue(initial.messages().stream().anyMatch(message -> message.text().contains("def dependency_native_apt_scope(")));
            }
            var sources = new LinkedHashMap<Integer, Map.Entry<String, String>>();
            for (String source : List.of("dependencyWorker", "dependencySystemShell", "mirrorRuntime")) {
                var descriptor = manifest.path(source);
                String text = collect(evidence, descriptor, PHASE);
                assertIdentity(descriptor, text);
                addReplaySource(sources, descriptor, text);
            }
            var missing = manifest.path("requiredAptRanges");
            assertTrue(missing.isArray() && !missing.isEmpty());
            var initialWorkingSet = initial.messages().stream()
                .filter(DeploymentToolchainReviewEvidenceTest::nodeInitialSupplement).findFirst().orElseThrow().text();
            assertTrue(initialWorkingSet.contains("def validate_url("));
            assertTrue(initialWorkingSet.contains("wtl_apt_update() {"));
            var present = new LinkedHashMap<String, TreeMap<Integer, Integer>>();
            String initialHeader = initialWorkingSet.lines().skip(1).findFirst().orElseThrow();
            for (var range : JSON.readTree(initialHeader).path("ranges"))
                addR24Range(present, range.path("pointer").asText(), range.path("offset").asInt(), range.path("next").asInt());
            String snapshot = manifest.path("snapshotSha256").asText();
            int approvalFixtureCap = 180000;
            int recordedFactsCharacters = 43294;
            int fixed = wireWithR24FactsSize(initial, recordedFactsCharacters, approvalFixtureCap, Map.of()).length;
            var visible = new LinkedHashMap<String, TreeMap<Integer, Integer>>();
            int largest = fixed;
            int pages = 0;
            for (var required : missing) {
                String pointer = required.path("pointer").asText();
                int next = required.path("next").asInt();
                for (int offset = required.path("offset").asInt(); offset < next; offset += 4000) {
                    int end = Math.min(offset + 4000, next);
                    var existing = present.getOrDefault(pointer, new TreeMap<>()).floorEntry(offset);
                    if (existing != null && existing.getValue() >= end) continue;
                    var call = read("apt-required-" + pages++, snapshot, pointer, offset, end - offset);
                    fixture.model.replies.add(new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(mockResponses(call)), Optional.empty()));
                }
            }
            reply(fixture, verdict("apt-all-required-delivered", "APPROVE"));
            assertEquals("APPROVE", model.askCommand("Inspect exact current request", facts, fixture.model.configurationRevision,
                fixture.task, DeploymentReviewDecision::malformed, action, command).path("decision").asText());
            assertEquals(pages + 2, fixture.model.requests.size());
            for (var request : fixture.model.requests.subList(2, fixture.model.requests.size())) {
                byte[] encoded = wireWithR24FactsSize(request, recordedFactsCharacters, approvalFixtureCap, Map.of());
                largest = Math.max(largest, encoded.length);
                String frame = request.messages().get(request.messages().size() - 2).text();
                int separator = frame.indexOf('\n', frame.indexOf('\n') + 1);
                var header = JSON.readTree(frame.substring(frame.indexOf('\n') + 1, separator));
                int start = separator + 1;
                for (var range : header.path("ranges")) {
                    int end = start + range.path("next").asInt() - range.path("offset").asInt();
                    assertR24SourceRange(visible, sources, range.path("pointer").asText(), range, frame.substring(start, end));
                    start = end;
                }
                assertEquals(frame.length(), start);
            }
            for (var required : missing) {
                var observed = visible.get(required.path("pointer").asText()).floorEntry(required.path("offset").asInt());
                assertNotNull(observed);
                assertTrue(observed.getValue() >= required.path("next").asInt(), required::toString);
            }
            assertEquals(0, assisted.hosts.get());
            var finalRequest = fixture.model.requests.getLast();
            var finalBytes = wireWithR24FactsSize(finalRequest, recordedFactsCharacters, approvalFixtureCap, Map.of());
            var exactSources = new LinkedHashMap<String, String>();
            sources.values().forEach(source -> exactSources.put(source.getKey(), source.getValue()));
            var finalVisible = replayRetainedRanges(finalBytes, snapshot, exactSources).orElseThrow();
            var allRequired = new LinkedHashMap<String, TreeMap<Integer, Integer>>();
            for (var range : missing)
                addR24Range(allRequired, range.path("pointer").asText(), range.path("offset").asInt(), range.path("next").asInt());
            assertR24Coverage(allRequired, finalVisible);
            System.out.printf("NATIVE_APT_REQUIRED_EVIDENCE profile=%s fixed=%d largest=%d fixtureCap=%d pages=%d%n",
                profile, fixed, largest, approvalFixtureCap, pages);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"task-board", "file-transfer", "asset-lending", "log-analyzer", "binary-inspector"})
    void r34TerminalWindowHypothesesFitOriginalCeilingsAndGateDeferredSource(String project) throws Exception {
        try (var fixture = new MirroredInstallerTestFixture();
             var assisted = new DeploymentNativeHelperPackageImpactTest.Assisted(fixture);
             var command = switch (project) {
                 case "task-board", "file-transfer" -> diagnosedPreparation(fixture, "NODE", "24");
                 case "asset-lending" -> r26Binding(fixture, "NODE", "24.22.3");
                 case "log-analyzer" -> request("dependencies", fixture);
                 case "binary-inspector" -> r35BinaryBuildFixture(fixture);
                 default -> throw new AssertionError(project);
             }) {
            var action = switch (project) {
                case "task-board", "file-transfer" -> intent("prepare", fixture, command);
                case "asset-lending" -> intent("bind", fixture, command);
                case "log-analyzer" -> intent("dependencies", fixture, command);
                default -> RemoteActions.command(fixture.server, fixture.model.configurationRevision, command)
                    .withSemantics(new ActionSemantics("deployment.build.run.v1", Set.of(new ActionTarget(
                        fixture.server.id(), "deployment-build", new DeploymentRemoteWorkspace(
                            "binary-inspector", "a".repeat(64), fixture.layout).candidateRoot()))));
            };
            var protocol = new DeploymentFixedCommandProtocol(fixture.context);
            var original = new LinkedHashMap<String, Object>(protocol.inspect(action, command, fixture.layout, fixture.task));
            var effects = new LinkedHashMap<String, Object>(assisted.scope.effectFacts(action, fixture.task));
            if (project.equals("log-analyzer")) effects.put("currentNativeBuildPrerequisites", Map.of(
                "observedGap", Map.of("nativeBuildPrerequisitesMissing", true, "packageManager", "apt", "distro", "UBUNTU"),
                "currentToolchainPlan", Map.of("runtimeKind", "RustService"), "fixedTransaction", original.get("toolchainDependencies")));
            original.put("currentCommandEvidence", effects);
            var evidence = DeploymentCommandSourceEvidence.project(DeploymentAiJson.facts(original, 1048576),
                action, command, fixture.layout).orElseThrow();
            var initial = r31DeniedApproval(fixture, assisted, original, action, command);
            var current = (ObjectNode) JSON.readTree(evidence.messages().getLast().text());
            String phase = project.equals("binary-inspector") ? "buildReviewEvidence" : PHASE;
            String snapshot = current.at("/" + phase + "/snapshotSha256").asText();
            var file = current.path(phase);
            var sources = new LinkedHashMap<Integer, Map.Entry<String, String>>();
            if (!project.equals("log-analyzer"))
                addReplaySource(sources, current.at("/mirrorTransport/runtimeSource"), DeploymentMirrorRuntime.runtimeSource());
            if (!project.equals("binary-inspector")) {
                var shell = file.path("mirrorBootstrapShell");
                addReplaySource(sources, shell, r24Shell(fixture));
            }
            if (file.has("installedHelperSource"))
                addReplaySource(sources, file.path("installedHelperSource"), DeploymentManagedHelperBundle.renderScript(fixture.layout));
            if (project.equals("log-analyzer")) {
                addReplaySource(sources, file.path("dependencySystemShell"), collect(evidence, file.path("dependencySystemShell"), phase));
                addReplaySource(sources, file.path("mirrorRuntime"), DeploymentMirrorRuntime.runtimeSource());
            }
            // R34 retained only pointer widths. The matching public pointers here are fixture hypotheses.
            String coordinates = switch (project) {
                case "task-board" -> "46/157345/1500 46/158829/1500 46/174357/1500 46/180844/1200 46/192754/2200 46/302144/2000 30/20095/2800 30/32625/1000";
                case "file-transfer" -> "45/0/8000 46/302144/8000 46/218801/8000 46/157345/8000 46/174357/8000 45/0/8000";
                case "asset-lending" -> "45/0/8000 45/8000/8000 45/16000/8000 45/24000/8000 45/34677/4611";
                case "log-analyzer" -> "46/0/8000 46/12689/8000 46/18678/8000 38/3984/8000 38/76140/8000 38/104547/5000";
                default -> "30/60000/8000";
            };
            int bound = switch (project) {
                case "task-board" -> 40941;
                case "file-transfer" -> 44277;
                case "asset-lending" -> 52403;
                case "log-analyzer" -> 116007;
                default -> 151770;
            };
            var reader = evidence.reader(24);
            var messages = new ArrayList<>(initial.messages());
            messages.set(messages.size() - 1, evidence.messages().getLast());
            var start = mockResponses(read("r35-prior", snapshot, sources.values().iterator().next().getKey(), 0, 120));
            reader.continueReview(messages, start, reader.read(start));
            var calls = new R24Turn(0, 0, 0, 0, 0, coordinates).calls(snapshot, sources, 1);
            var requested = mockResponses(calls.toArray(AiToolCall[]::new));
            var results = reader.read(requested);
            assertTrue(results.stream().noneMatch(AiToolResult::error), project);
            reader.continueReview(messages, requested, results);
            int full = wire(copy(initial, messages, 1048576), 1048576, Map.of()).length;
            boolean packed = full <= bound || reader.repackBeforeSend(messages, exactBudgetProblem(full, bound));
            assertTrue(packed, () -> project + " cannot fit its observed pre-send ceiling: " + full + " > " + bound);
            byte[] encoded = wire(copy(initial, messages, bound), bound, Map.of());
            var confirmed = new LinkedHashMap<String, TreeMap<Integer, Integer>>();
            seedInlineSourceRanges(current, confirmed);
            var frame = current.at("/" + phase + "/frames");
            for (var inline : frame)
                addR24Range(confirmed, inline.path("sourcePointer").asText(), 0, inline.path("characters").asInt());
            for (String name : List.of("selectedRuntimeRanges", "selectedAptRanges"))
                for (var range : file.path(name))
                    addR24Range(confirmed, range.path("sourcePointer").asText(), range.path("offset").asInt(), range.path("next").asInt());
            assertR24VisiblePages(encoded, snapshot, calls, true, confirmed, sources);
            reader.read(response(Optional.empty(), verdict("r35-terminal-approval", "APPROVE")));
            String retained = messages.get(messages.size() - 2).text();
            assertEquals(retained.contains("\"requestedNext\"") || retained.contains("\"pendingRequired\""),
                reader.hasUnfulfilledApprovalEvidence(), project);
            System.out.printf("R35_TERMINAL_SHAPE project=%s full=%d packed=%d cap=%d deferred=%s%n",
                project, full, encoded.length, bound, reader.hasUnfulfilledApprovalEvidence());
            assertEquals(0, assisted.hosts.get());
        }
    }

    private static CommandRequest r35BinaryBuildFixture(MirroredInstallerTestFixture fixture) throws Exception {
        String body = "set -eu\nwtl_dependency CARGO_REGISTRY cargo fetch --locked\ncargo build --locked --release\n";
        int seconds = 120;
        String policy = DeploymentMirrorTasks.policy(fixture.task);
        String input = DeploymentMirrorTasks.withMaterials(DeploymentMirrorRuntime.wrapBuild(body, policy, seconds), fixture.task.id());
        var workspace = new DeploymentRemoteWorkspace("binary-inspector", "a".repeat(64), fixture.layout);
        String collector = DeploymentBuildOutputProtocol.script(workspace, seconds, 16, 512, 8192, "ordinary",
            MirroredInstallerTestFixture.sha(input), "-", List.of(List.of("BUILD_OK")));
        return new CommandRequest(DeploymentManagedHelperBundle.verifiedCommand(collector, fixture.layout),
            input.getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(seconds + 15), 65536);
    }

    @ParameterizedTest
    @ValueSource(strings = {"asset-lending:mirrorBootstrapShell", "asset-lending:preparationSelection",
        "task-board:mirrorBootstrapShell", "task-board:preparationSelection",
        "file-transfer:mirrorBootstrapShell", "file-transfer:preparationSelection"})
    void nodePrepareRealApprovalFitsRecordedTerminalBudgetAndRejectsUndeliveredRanges(String scenario) throws Exception {
        var fields = scenario.split(":");
        String project = fields[0], interpretation = fields[1];
        var replay = switch (project) {
            case "asset-lending" -> new NodeReplay(30034, 704212, 91407, 69681,
                "30/20095/8000", "30/20989/7106 43/0/8000 45/0/6400");
            case "task-board" -> new NodeReplay(28527, 585744, 78228, 59294,
                "46/170800/3500 46/190000/3900 46/181000/1800 30/7000/6000 30/16000/4800",
                "46/215449/4848 46/295847/2600 46/207073/1772 46/188436/1573 45/13709/2300 45/0/2800 43/0/7000 46/146023/6000");
            case "file-transfer" -> new NodeReplay(28016, 276077, 78020, 60018,
                "43/0/8000", "46/295847/1800 46/146023/3000 30/7163/1800 45/12711/3200");
            default -> throw new AssertionError(project);
        };
        try (var fixture = new MirroredInstallerTestFixture();
             var command = diagnosedPreparation(fixture, "NODE", "24");
             var assisted = new DeploymentNativeHelperPackageImpactTest.Assisted(fixture)) {
            var intent = intent("prepare", fixture, command);
            var sentBytes = new ArrayList<Integer>();
            var refusedBytes = new ArrayList<Integer>();
            var sources = new LinkedHashMap<Integer, Map.Entry<String, String>>();
            var mappedCoverageComplete = new java.util.concurrent.atomic.AtomicBoolean();
            var current = new DeploymentFixedCommandProtocol(fixture.context).inspect(intent, command, fixture.layout, fixture.task);
            var evidence = DeploymentCommandSourceEvidence.project(DeploymentAiJson.facts(current, 1048576),
                intent, command, fixture.layout).orElseThrow();
            var paged = JSON.readTree(evidence.messages().getLast().text()).at("/" + PHASE + "/" + interpretation);
            String width45Source = collect(evidence, paged, PHASE);
            assertIdentity(paged, width45Source);
            var service = (gold.debug.windowstolinux.appapi.ai.AiService) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {gold.debug.windowstolinux.appapi.ai.AiService.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("call")) {
                        try {
                            return method.invoke(assisted.context.ai(), args);
                        } catch (java.lang.reflect.InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    }
                    var received = (AiRequest) args[0];
                    var projected = (ObjectNode) JSON.readTree(received.messages().getLast().text());
                    var manifest = projected.path(PHASE);
                    String snapshot = manifest.path("snapshotSha256").asText();
                    var wireMessages = new ArrayList<>(received.messages());
                    // The live log captures total facts size, not the original per-field metadata.
                    int extra = replay.factsCharacters() - projected.toString().length() - 24;
                    if (extra > 0) projected.put("replayFixturePadding", "x".repeat(extra));
                    wireMessages.set(wireMessages.size() - 1, new AiMessage(AiRole.USER, projected.toString(), List.of()));
                    int step = sentBytes.size();
                    int maximum = switch (step) {
                        case 0 -> replay.initialCap();
                        case 1 -> replay.priorCap();
                        default -> replay.lastCap();
                    };
                    byte[] encoded;
                    try {
                        encoded = wire(copy(received, wireMessages, maximum), maximum, Map.of());
                    } catch (ModelRequestBudgetException exceeded) {
                        refusedBytes.add(exceeded.encodedBytes());
                        throw new ApiException(exactBudgetProblem(exceeded.encodedBytes(), maximum));
                    }
                    sentBytes.add(encoded.length);
                    if (step == 0) {
                        var baseline = wireMessages.stream().filter(message -> !nodeInitialSupplement(message)).toList();
                        int baselineBytes = wire(copy(received, baseline, maximum), maximum, Map.of()).length;
                        assertTrue(baselineBytes < replay.lastCap(), () -> "Fixed initial request: " + baselineBytes);
                        addReplaySource(sources, projected.at("/mirrorTransport/runtimeSource"), DeploymentMirrorRuntime.runtimeSource());
                        addReplaySource(sources, manifest.path("mirrorPolicySource"), DeploymentMirrorTasks.policy(fixture.task));
                        addReplaySource(sources, manifest.path("installedHelperSource"), DeploymentManagedHelperBundle.renderScript(fixture.layout));
                        var width45 = manifest.path(interpretation);
                        assertEquals(45, width45.path("sourcePointer").asText().length());
                        addReplaySource(sources, width45, width45Source);
                        var prior = new R24Turn(0, 0, 0, 0, 0, replay.prior()).calls(snapshot, sources, 0);
                        return success(mockResponses(prior.toArray(AiToolCall[]::new)));
                    }
                    if (step == 1) {
                        var last = new R24Turn(0, 0, 0, 0, 0, replay.last()).calls(snapshot, sources, 1);
                        return success(mockResponses(last.toArray(AiToolCall[]::new)));
                    }
                    String frame = received.messages().get(received.messages().size() - 2).text();
                    int beginning = frame.indexOf('\n') + 1;
                    var header = JSON.readTree(frame.substring(beginning, frame.indexOf('\n', beginning)));
                    if (interpretation.equals("preparationSelection")) {
                        var canonical = new LinkedHashMap<String, String>();
                        sources.values().forEach(source -> canonical.put(source.getKey(), source.getValue()));
                        canonical.put(manifest.at("/mirrorBootstrapShell/sourcePointer").asText(), r24Shell(fixture));
                        var view = manifest.path("preparationSelection");
                        var visible = replayRetainedRanges(encoded, snapshot, canonical).orElseThrow();
                        assertFalse(visible.containsKey(view.path("sourcePointer").asText()));
                        verifyNodeInlineHasNoRetainedDuplicates(projected, visible, canonical);
                        var asked = new LinkedHashMap<String, TreeMap<Integer, Integer>>();
                        var calls = new ArrayList<>(new R24Turn(0, 0, 0, 0, 0, replay.prior()).calls(snapshot, sources, 0));
                        calls.addAll(new R24Turn(0, 0, 0, 0, 0, replay.last()).calls(snapshot, sources, 1));
                        for (var call : calls) {
                            var query = DeploymentAiJson.arguments(call);
                            String pointer = query.path("pointer").asText();
                            int offset = query.path("offset").asInt();
                            int end = (int) DeploymentEvidencePages.page(canonical.get(pointer), offset, query.path("limit").asInt()).get("next");
                            addNodeRequestedRanges(asked, view, pointer, offset, end);
                        }
                        int missing = missingNodeCharacters(asked, visible, canonical);
                        mappedCoverageComplete.set(missing == 0);
                        if (missing > 0) assertTrue(header.has("deferredRanges") || header.has("previouslyDeferred"));
                        return success(response(Optional.empty(), verdict("r33-mapped-independent-approval", "APPROVE")));
                    }
                    assertFalse(header.path("deferredRanges").isMissingNode());
                    var asked = new LinkedHashMap<String, TreeMap<Integer, Integer>>();
                    var latest = new R24Turn(0, 0, 0, 0, 0, replay.last()).calls(snapshot, sources, 1);
                    collectRequestedCoverage(asked, latest, sources);
                    var visible = new LinkedHashMap<String, TreeMap<Integer, Integer>>();
                    seedInlineSourceRanges(projected, visible);
                    assertR24VisiblePages(encoded, snapshot, latest, true, visible, sources);
                    int pending = 0;
                    for (var receipt : header.path("latestReads")) {
                        if (!receipt.has("requestedNext")) continue;
                        pending++;
                        String pointer = receipt.path("pointer").asText();
                        String original = sources.get(pointer.length()).getValue();
                        String hash = sourceHash(header, receipt);
                        if (hash.isEmpty()) hash = projected.at(pointer).path("sha256").asText();
                        assertEquals(MirroredInstallerTestFixture.sha(original), hash);
                        addR24Range(visible, pointer, receipt.path("next").asInt(), receipt.path("requestedNext").asInt());
                    }
                    assertTrue(pending > 0);
                    assertR24Coverage(asked, visible);
                    return success(response(Optional.empty(), verdict("r33-unjustified-approval", "APPROVE")));
                });
            var context = (gold.debug.windowstolinux.appapi.mod.ModContext) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {gold.debug.windowstolinux.appapi.mod.ModContext.class}, (proxy, method, args) -> {
                    if (method.getName().equals("ai")) return service;
                    try {
                        return method.invoke(assisted.context, args);
                    } catch (java.lang.reflect.InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
            try {
                var result = DeploymentFixedCommandApproval.approveWithEvidence(context, assisted.host, intent, command, fixture.task,
                    fixture.model.configurationRevision, "AUTOMATIC", false, assisted.scope);
                assertTrue(mappedCoverageComplete.get(), "Incomplete original source must not reach the host");
                assertEquals(TaskResult.SUCCEEDED, result.outcome());
                assertEquals(List.of(intent), assisted.grantedIntents);
            } catch (ApiException rejected) {
                assertFalse(mappedCoverageComplete.get());
                assertEquals("DEPLOYMENT_COMMAND_REVIEW_INVALID", rejected.problem().eventCode(),
                    () -> "sent=" + sentBytes + " refused=" + refusedBytes + " context=" + rejected.problem().context()
                        + " cause=" + rejected.getCause());
                assertEquals("EVIDENCE_INCOMPLETE_VERDICT", rejected.problem().context().get("reviewCode"));
                assertEquals(0, assisted.hosts.get());
            }
            assertEquals(3, sentBytes.size());
            if (!interpretation.equals("preparationSelection")) assertFalse(refusedBytes.isEmpty());
            assertTrue(refusedBytes.size() <= 2);
            if (!refusedBytes.isEmpty()) assertTrue(refusedBytes.getLast() > replay.lastCap());
            assertTrue(sentBytes.getLast() <= replay.lastCap());
            System.out.printf("NODE_TERMINAL_REPLAY project=%s width45=%s fixedEncoded=%d priorEncoded=%d fullEncoded=%d sentEncoded=%d cap=%d%n",
                project, interpretation, sentBytes.getFirst(), sentBytes.get(1),
                refusedBytes.isEmpty() ? sentBytes.getLast() : refusedBytes.getLast(), sentBytes.getLast(), replay.lastCap());
        }
    }

    private record NodeReplay(int factsCharacters, int initialCap, int priorCap, int lastCap, String prior, String last) { }

    private static String sourceHash(JsonNode header, JsonNode receipt) {
        return receipt.has("sourceSha256") ? receipt.path("sourceSha256").asText()
            : header.path("sourceSha256ByPointer").path(receipt.path("pointer").asText()).asText();
    }

    @ParameterizedTest
    @ValueSource(strings = {"DENY", "APPROVE", "APPROVE_AFTER_READ"})
    void onlyExactPreSendEncodingRefusalRepacksOneUnsentApproval(String decision) throws Exception {
        try (var fixture = new MirroredInstallerTestFixture(); var request = diagnosedPreparation(fixture, "JAVA", "21")) {
            var intent = intent("prepare", fixture, request);
            var facts = new DeploymentFixedCommandProtocol(fixture.context).inspect(intent, request, fixture.layout, fixture.task);
            var calls = new ArrayList<AiRequest>();
            var sent = new ArrayList<AiRequest>();
            var service = (gold.debug.windowstolinux.appapi.ai.AiService) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {gold.debug.windowstolinux.appapi.ai.AiService.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("call")) throw new AssertionError(method.getName());
                    var received = (AiRequest) args[0];
                    calls.add(received);
                    if (calls.size() == 1) {
                        sent.add(received);
                        var current = JSON.readTree(received.messages().getLast().text());
                        String snapshot = current.at("/" + PHASE + "/snapshotSha256").asText();
                        return success(mockResponses(read("read-actual-action", snapshot, "/exactAction", 8000, 8000)));
                    }
                    int full = wire(received, 1048576, Map.of()).length;
                    int ceiling = calls.size() == 2 ? full - 4000 : wire(calls.get(1), 1048576, Map.of()).length - 4000;
                    try {
                        wire(received, ceiling, Map.of());
                    } catch (ModelRequestBudgetException exceeded) {
                        throw new ApiException(exactBudgetProblem(exceeded.encodedBytes(), ceiling));
                    }
                    sent.add(received);
                    if (decision.equals("APPROVE_AFTER_READ") && calls.size() == 3) {
                        String frame = received.messages().get(received.messages().size() - 2).text();
                        int start = frame.indexOf('\n') + 1;
                        var header = JSON.readTree(frame.substring(start, frame.indexOf('\n', start)));
                        var range = header.path("latestReads").get(0);
                        return success(mockResponses(read("read-deferred-action", header.path("snapshotSha256").asText(),
                            range.path("pointer").asText(), range.path("next").asInt(),
                            range.path("requestedNext").asInt() - range.path("next").asInt())));
                    }
                    return success(response(Optional.empty(), verdict("r31-independent-verdict",
                        decision.equals("DENY") ? "DENY" : "APPROVE")));
                });
            var context = (gold.debug.windowstolinux.appapi.mod.ModContext) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {gold.debug.windowstolinux.appapi.mod.ModContext.class}, (proxy, method, args) ->
                    method.getName().equals("ai") ? service : method.invoke(fixture.context, args));
            var model = new DeploymentAiJson(context);
            Callable<JsonNode> ask = () -> model.askCommand("Inspect original request", facts, fixture.model.configurationRevision,
                fixture.task, DeploymentReviewDecision::malformed, intent, request);
            if (decision.equals("DENY")) assertEquals("DENY", ask.call().path("decision").asText());
            else assertEquals("EVIDENCE_INCOMPLETE_VERDICT", DeploymentCommandReviewModel.failureDiagnostics(
                assertThrows(DeploymentCommandReviewModel.Failure.class, ask::call)).get("reviewCode"));
            assertEquals(decision.equals("APPROVE_AFTER_READ") ? 5 : 3, calls.size());
            assertEquals(decision.equals("APPROVE_AFTER_READ") ? List.of(calls.getFirst(), calls.get(2), calls.getLast())
                : List.of(calls.getFirst(), calls.getLast()), sent);
            assertEquals(calls.get(1).tools(), calls.get(2).tools());
            assertEquals(calls.get(1).messages().getLast(), calls.get(2).messages().getLast());
            assertTrue(calls.get(2).messages().stream().anyMatch(message -> message.text().contains("\"requestedNext\":")));
            if (decision.equals("APPROVE_AFTER_READ"))
                assertTrue(calls.getLast().messages().stream().anyMatch(message -> message.text().contains("\"previouslyDeferred\":")));
        }
    }

    private static OperationResult<AiResponse> success(AiResponse response) {
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(response), Optional.empty());
    }

    private static void assertR30DeferredPages(DeploymentCommandSourceEvidence.Reader reader, List<AiMessage> messages,
                                                String snapshot, List<AiToolCall> calls,
                                                Map<Integer, Map.Entry<String, String>> sources,
                                                Map<String, TreeMap<Integer, Integer>> visible) throws Exception {
        String frame = messages.get(messages.size() - 2).text();
        int first = frame.indexOf('\n') + 1;
        var header = JSON.readTree(frame.substring(first, frame.indexOf('\n', first)));
        var asked = new LinkedHashMap<String, TreeMap<Integer, Integer>>();
        collectRequestedCoverage(asked, calls, sources);
        int deferred = 0;
        for (var receipt : header.path("latestReads")) {
            if (!receipt.has("requestedNext")) continue;
            String pointer = receipt.path("pointer").asText();
            int start = receipt.path("next").asInt(), end = receipt.path("requestedNext").asInt();
            String source = sources.get(pointer.length()).getValue();
            assertEquals(MirroredInstallerTestFixture.sha(source), sourceHash(header, receipt));
            var later = read("r31-deferred-" + deferred++, snapshot, pointer, start, end - start);
            var value = pagePayload(reader.read(mockResponses(later)).getFirst().text()).path("value");
            assertEquals(start, value.path("offset").asInt());
            assertEquals(end, value.path("next").asInt());
            assertPageRanges(source, value);
            addR24Range(visible, pointer, start, end);
        }
        if (header.has("deferredRanges")) assertTrue(deferred > 0);
        assertR24Coverage(asked, visible);
    }

    @Test
    void responsesStopsAnEvidenceCycleAfterAnExactReread() throws Exception {
        try (var fixture = new MirroredInstallerTestFixture(); var command = request("bind", fixture)) {
            var intent = intent("bind", fixture, command);
            var facts = new DeploymentFixedCommandProtocol(fixture.context).inspect(intent, command, fixture.layout, fixture.task);
            var evidence = DeploymentCommandSourceEvidence.project(DeploymentAiJson.facts(facts, 1048576), intent, command,
                fixture.layout).orElseThrow();
            String pointer = "/mirrorTransport/runtimeSource";
            String source = DeploymentMirrorRuntime.runtimeSource();
            var messages = new ArrayList<>(evidence.messages());
            String snapshot = JSON.readTree(messages.getLast().text()).at("/" + PHASE + "/snapshotSha256").asText();
            var reader = evidence.reader(32);
            for (int round = 0; round < 3; round++) {
                var response = mockResponses(read("page-" + round, snapshot, pointer, round * 8000, 8000));
                reader.continueReview(messages, response, reader.read(response));
            }
            var next = mockResponses(read("page-3", snapshot, pointer, 24000, 8000));
            reader.continueReview(messages, next, reader.read(next));
            var reread = mockResponses(read("first-reread", snapshot, pointer, 0, 8000));
            reader.continueReview(messages, reread, reader.read(reread));
            String frame = messages.get(messages.size() - 2).text();
            assertTrue(frame.contains("\"latestBatchRepeat\""));
            assertTrue(frame.contains(source.substring(0, 8000)), "One exact reread remains available");
            var cycle = assertThrows(DeploymentCommandReviewModel.Failure.class, () ->
                reader.read(mockResponses(read("cycle", snapshot, pointer, 8000, 8000))));
            var diagnostic = DeploymentCommandReviewModel.failureDiagnostics(cycle);
            assertEquals("EVIDENCE_NO_PROGRESS", diagnostic.get("reviewCode"));
            assertEquals("0", diagnostic.get("reviewNewCharacters"));
            assertEquals("2", diagnostic.get("reviewRepeatedBatches"));
        }
    }

    @Test
    void agedOutEvidenceIsExplicitlyOmittedAndCanBeReadAgainExactly() throws Exception {
        try (var fixture = new MirroredInstallerTestFixture(); var command = request("bind", fixture)) {
            var intent = intent("bind", fixture, command);
            var facts = new DeploymentFixedCommandProtocol(fixture.context).inspect(intent, command, fixture.layout, fixture.task);
            var source = DeploymentCommandSourceEvidence.project(DeploymentAiJson.facts(facts, 1048576), intent, command,
                fixture.layout).orElseThrow();
            var messages = new ArrayList<>(source.messages());
            var current = JSON.readTree(messages.getLast().text());
            String snapshot = current.at("/" + PHASE + "/snapshotSha256").asText();
            String pointer = current.at("/mirrorTransport/runtimeSource/sourcePointer").asText();
            String runtime = DeploymentMirrorRuntime.runtimeSource();
            var reader = source.reader(32);
            for (int round = 0; round < 3; round++) {
                var calls = new AiToolCall[8];
                for (int index = 0; index < calls.length; index++)
                    calls[index] = read("age-" + round + "-" + index, snapshot, pointer, (round * 8 + index) * 8000, 8000);
                var reply = mockResponses(calls);
                reader.continueReview(messages, reply, reader.read(reply));
            }
            int oldFrameBytes = JSON.writeValueAsBytes(messages.get(messages.size() - 2).text()).length;
            assertTrue(reader.repackBeforeSend(messages, exactBudgetProblem(oldFrameBytes + 90000, oldFrameBytes - 7000)));
            String retained = messages.get(messages.size() - 2).text();
            int start = retained.indexOf('\n') + 1;
            var header = JSON.readTree(retained.substring(start, retained.indexOf('\n', start)));
            assertTrue(header.path("omittedRanges").asInt() > 0);
            assertFalse(messages.stream().anyMatch(message -> message.continuation().isPresent() || message.toolResult().isPresent()));
            var missing = header.path("previouslyDeferred").get(0);
            assertNotNull(missing, "Older requests must receive exact continuation cursors after compaction");
            int offset = missing.path("offset").asInt(), next = missing.path("next").asInt();
            var fresh = read("age-reread", snapshot, pointer, offset, next - offset);
            var results = reader.read(mockResponses(fresh));
            assertEquals(runtime.substring(offset, next), pagePayload(results.getFirst().text()).at("/value/text").textValue());
            assertTrue(reader.hasUnfulfilledApprovalEvidence(), "Previously sent but currently omitted source blocks approval");
            assertThrows(IOException.class, () -> reader.read(mockResponses(fresh)), "Rebasing cannot erase native call identity history");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"asset-lending", "csv-inspector", "survey-scoring", "log-analyzer", "directory-diff"})
    void r28PreparationReplaysObservedReadShapesWithOriginalWireCeilings(String project) throws Exception {
        var review = switch (project) {
            case "asset-lending" -> new R28Preparation("DOTNET", "10", 33656, 106547, "43/0/8000 45/13053/3000 46/192432/1892");
            case "csv-inspector" -> new R28Preparation("PHP", "8.3", 36879, 133904, "30/3984/8000 30/11984/3000 45/0/8000 45/13590/1200");
            case "survey-scoring" -> new R28Preparation("RUBY", "3.3", 37922, 177935,
                "30/8737/8000 30/13643/8000 30/76140/8000 30/104547/8000 30/109746/8000 45/16190/8000 43/0/8000");
            case "log-analyzer" -> new R28Preparation("RUST", "1.95", 33119, 95146,
                "30/13643/4000 30/16170/8000 45/11924/3000 46/184918/5000 46/263775/3200 46/188436/3000");
            case "directory-diff" -> new R28Preparation("PYTHON", "3.12", 35225, 149190,
                "30/3984/8000 30/8737/4000 30/16170/8000 30/24170/8000 29/0/6000 43/0/6000 45/0/8000");
            default -> throw new IllegalArgumentException(project);
        };
        try (var fixture = new MirroredInstallerTestFixture();
             var command = diagnosedPreparation(fixture, review.ecosystem(), review.branch());
             var assisted = new DeploymentNativeHelperPackageImpactTest.Assisted(fixture)) {
            var intent = intent("prepare", fixture, command);
            var protocol = new DeploymentFixedCommandProtocol(fixture.context);
            var current = protocol.inspect(intent, command, fixture.layout, fixture.task);
            var source = DeploymentCommandSourceEvidence.project(DeploymentAiJson.facts(current, 1048576), intent, command,
                fixture.layout).orElseThrow();
            var facts = JSON.readTree(source.messages().getLast().text());
            var selection = facts.at("/" + PHASE + "/preparationSelection");
            String body = source.messages().get(selection.path("ordinal").asInt()).text()
                .substring(selection.path("headerCharacters").asInt());
            if (review.ecosystem().equals("RUBY")) {
                var release = facts.at("/" + PHASE + "/selectedPreparationRanges").findParents("section").stream()
                    .filter(range -> range.path("section").asText().equals("selectedRelease"))
                    .findFirst().orElseThrow();
                assertTrue(release.path("next").asInt() - release.path("offset").asInt() < 2048);
            }
            for (String marker : List.of("class MirrorRedirect(", "policy.validate_url(endpoint, policy.maps(endpoint, original))",
                    "do_handshake()", "digest.hexdigest().lower() != expected.lower()", "os.replace(temporary, target)"))
                assertTrue(body.contains(marker), marker);
            Callable<OperationResult<ApprovalGrant>> approve = () -> DeploymentFixedCommandApproval.approveWithEvidence(
                assisted.context, assisted.host, intent, command, fixture.task, fixture.model.configurationRevision,
                "AUTOMATIC", false, assisted.scope);
            reply(fixture, verdict("r28-no-host-grant", "DENY"));
            assertReviewDenied(approve);
            var actual = fixture.model.requests.getFirst();
            var approvalFacts = JSON.readTree(actual.messages().getLast().text());
            String snapshot = approvalFacts.at("/" + PHASE + "/snapshotSha256").asText();
            var sources = r24Sources(actual, approvalFacts, fixture);
            var batch = new R24Turn(0, 0, 0, 0, 0, review.lastReads()).calls(snapshot, sources, 1);
            var continuations = new LinkedHashMap<UUID, String>();
            // Read-request arguments in R28 expose widths and coordinates, not their complete pointer strings.
            // Fixture pointers of unique known widths retain the old ranges; unknown widths are rejected by the same tool.
            replyAtTotalBytes(fixture, continuations, 6000, "OPENAI_RESPONSES",
                new AiToolCall("r28-prior-page", "evidence", batch.getFirst().arguments()));
            replyAtTotalBytes(fixture, continuations, 6000, "OPENAI_RESPONSES", batch.toArray(AiToolCall[]::new));
            reply(fixture, verdict("r28-late-deny", "DENY"));
            assertReviewDenied(approve);
            assertEquals(0, assisted.hosts.get());
            byte[] encoded = wireWithR24FactsSize(actual, review.factsCharacters(), 1048576, Map.of());
            var latest = fixture.model.requests.getLast();
            var visible = new LinkedHashMap<String, TreeMap<Integer, Integer>>();
            seedInlineSourceRanges(approvalFacts, visible);
            var asked = new LinkedHashMap<String, TreeMap<Integer, Integer>>();
            collectRequestedCoverage(asked, batch, sources);
            byte[] late = wireWithR24FactsSize(latest, review.factsCharacters(), 1048576, Map.of());
            assertR24VisiblePages(late, snapshot, batch, true, visible, sources);
            assertR24Coverage(asked, visible);
            var firstReadRequest = fixture.model.requests.get(2);
            byte[] firstRead = wireWithR24FactsSize(firstReadRequest, review.factsCharacters(), 1048576, Map.of());
            var firstReadVisible = new LinkedHashMap<String, TreeMap<Integer, Integer>>();
            seedInlineSourceRanges(approvalFacts, firstReadVisible);
            var firstReadCalls = List.of(new AiToolCall("r28-prior-page", "evidence", batch.getFirst().arguments()));
            assertR24VisiblePages(firstRead, snapshot, firstReadCalls, true, firstReadVisible, sources);
            if (late.length <= review.cap())
                assertEquals(late.length, wireWithR24FactsSize(latest, review.factsCharacters(), review.cap(), Map.of()).length);
            else
                assertEquals(late.length, assertThrows(ModelRequestBudgetException.class,
                    () -> wireWithR24FactsSize(latest, review.factsCharacters(), review.cap(), Map.of())).encodedBytes());
            System.out.printf("R28_REPREPARE_WIRE project=%s ecosystem=%s freshEncoded=%d lateEncoded=%d recordedLateCap=%d%n",
                project, review.ecosystem(), encoded.length, late.length, review.cap());
        }
    }

    private record R28Preparation(String ecosystem, String branch, int factsCharacters, int cap, String lastReads) { }

    @Test
    void nodeInitialSupplementCanBeRemovedBeforeAnySourceIsCountedAsVisible() throws Exception {
        try (var fixture = new MirroredInstallerTestFixture();
             var command = diagnosedPreparation(fixture, "NODE", "24");
             var assisted = new DeploymentNativeHelperPackageImpactTest.Assisted(fixture)) {
            var action = intent("prepare", fixture, command);
            var original = new LinkedHashMap<String, Object>(
                new DeploymentFixedCommandProtocol(fixture.context).inspect(action, command, fixture.layout, fixture.task));
            original.put("currentCommandEvidence", assisted.scope.effectFacts(action, fixture.task));
            var evidence = DeploymentCommandSourceEvidence.project(DeploymentAiJson.facts(original, 1048576),
                action, command, fixture.layout).orElseThrow();
            reply(fixture, verdict("node-supplement-denial", "DENY"));
            assertEquals("DENY", new DeploymentAiJson(assisted.context).askCommand("Inspect exact current request",
                original, fixture.model.configurationRevision, fixture.task, DeploymentReviewDecision::malformed, action, command)
                .path("decision").asText());
            var initial = fixture.model.requests.getFirst();
            var reader = evidence.reader(8);
            var messages = new ArrayList<>(initial.messages().stream().filter(message -> !nodeInitialSupplement(message)).toList());
            messages.set(messages.size() - 1, evidence.messages().getLast());
            int baseBytes = wire(copy(initial, messages, 1048576), 1048576, Map.of()).length;
            reader.offerInitialSource(messages);
            var supplement = messages.stream().filter(DeploymentToolchainReviewEvidenceTest::nodeInitialSupplement).findFirst().orElseThrow();
            var header = JSON.readTree(supplement.text().lines().skip(1).findFirst().orElseThrow());
            assertFalse(header.has("latestReads"));
            var range = header.path("ranges").get(0);
            int fullBytes = wire(copy(initial, messages, 1048576), 1048576, Map.of()).length;
            assertTrue(fullBytes > baseBytes);
            assertTrue(reader.repackBeforeSend(messages, exactBudgetProblem(fullBytes, baseBytes)));
            assertFalse(messages.stream().anyMatch(DeploymentToolchainReviewEvidenceTest::nodeInitialSupplement));
            assertEquals(baseBytes, wire(copy(initial, messages, baseBytes), baseBytes, Map.of()).length);
            int offset = range.path("offset").asInt(), next = Math.min(range.path("next").asInt(), offset + 80);
            String pointer = range.path("pointer").asText();
            var response = mockResponses(read("first-real-read", header.path("snapshotSha256").asText(), pointer,
                offset, next - offset));
            var page = reader.read(response).getFirst();
            assertFalse(page.error());
            assertTrue(page.text().endsWith(supplement.text().split("\n", 3)[2].substring(0, next - offset)));
            assertEquals(0, assisted.hosts.get());
        }
    }

    private static boolean nodeInitialSupplement(AiMessage message) {
        return nodeInitialSupplement(message.text());
    }

    private static boolean nodeInitialSupplement(String text) {
        return text.startsWith("WTL-COMMAND-RETAINED-SOURCE-1\n")
            && text.lines().skip(1).findFirst().orElse("").contains("\"initialSupplement\":true");
    }

    @Test
    void r29AssetLendingNodePreparationStartsWithExactReachedEvidenceInsideOriginalCeiling() throws Exception {
        try (var fixture = new MirroredInstallerTestFixture();
             var command = diagnosedPreparation(fixture, "NODE", "24");
             var assisted = new DeploymentNativeHelperPackageImpactTest.Assisted(fixture)) {
            var intent = intent("prepare", fixture, command);
            Callable<OperationResult<ApprovalGrant>> approve = () -> DeploymentFixedCommandApproval.approveWithEvidence(
                assisted.context, assisted.host, intent, command, fixture.task, fixture.model.configurationRevision,
                "AUTOMATIC", false, assisted.scope);
            reply(fixture, verdict("r29-node-deny", "DENY"));
            assertReviewDenied(approve);
            var initial = fixture.model.requests.getFirst();
            var facts = JSON.readTree(initial.messages().getLast().text());
            var selection = facts.at("/" + PHASE + "/nodePreparationSelection");
            String frame = initial.messages().get(1 + selection.path("ordinal").asInt()).text()
                .substring(selection.path("headerCharacters").asInt());
            String supplement = initial.messages().get(initial.messages().size() - 2).text();
            assertTrue(supplement.contains("\"initialSupplement\":true"));
            assertEquals(6, facts.at("/" + PHASE + "/selectedPreparationRanges").size());
            for (String marker : List.of("SHASUMS256.txt", "linux-x64.tar.gz", "def checksum_text(",
                    "exec(compile(payload,", "do_handshake()", "class MirrorRedirect(",
                    "digest.hexdigest().lower() != expected.lower()", "os.replace(temporary, target)"))
                assertTrue(frame.contains(marker) || supplement.contains(marker), marker);
            var helperRanges = facts.at("/" + PHASE + "/installedHelperSource/namedRanges");
            assertTrue(supplement.contains("data.extractall(root, members=members)"));
            assertTrue(helperRanges.path("nodeSystemOwnership").isArray());
            assertTrue(helperRanges.path("def verify_system(").isArray());
            assertTrue(facts.at("/mirrorTransport/runtimeSource/sourcePointer").isTextual());
            assertFalse(frame.contains("wtl_run_mirror_runtime() {"));
            assertFalse(frame.contains("source_dependencies(layout)"), "Node's archive facts do not repeat the unrelated source-build branch");
            assertEquals("NODE", facts.at("/originalToolchainPreparation/ecosystem").asText());
            var points = new HashSet<String>();
            facts.at("/mirrorTransport/frozenPolicy/points").fieldNames().forEachRemaining(points::add);
            assertEquals(Set.of("NODE_DISTRIBUTION", "NODE_METADATA"), points);
            String snapshot = facts.at("/" + PHASE + "/snapshotSha256").asText();
            var sources = r24Sources(initial, facts, fixture);
            var prior = new R24Turn(0, 0, 0, 0, 0, "30/3984/8000 30/8737/6000 30/16170/8000 45/0/8000 45/13053/4000")
                .calls(snapshot, sources, 0);
            var finalBatch = new R24Turn(0, 0, 0, 0, 0, "30/3984/8000 30/20095/8000 30/76140/8000 45/13053/4000")
                .calls(snapshot, sources, 1);
            var continuations = new LinkedHashMap<UUID, String>();
            replyAtTotalBytes(fixture, continuations, 6000, "OPENAI_RESPONSES", prior.toArray(AiToolCall[]::new));
            replyAtTotalBytes(fixture, continuations, 6000, "OPENAI_RESPONSES", finalBatch.toArray(AiToolCall[]::new));
            var repeated = new R24Turn(0, 0, 0, 0, 0, "30/3984/8000 30/20095/8000 30/76140/8000 45/13053/4000")
                .calls(snapshot, sources, 2);
            replyAtTotalBytes(fixture, continuations, 6000, "OPENAI_RESPONSES", repeated.toArray(AiToolCall[]::new));
            reply(fixture, verdict("r29-node-final-deny", "DENY"));
            assertReviewDenied(approve);
            assertEquals(0, assisted.hosts.get());
            int fresh = wireWithR24FactsSize(initial, 33687, 881058, Map.of()).length;
            var latest = fixture.model.requests.get(fixture.model.requests.size() - 2);
            byte[] late = wireWithR24FactsSize(latest, 33687, 1048576, Map.of());
            var visible = new LinkedHashMap<String, TreeMap<Integer, Integer>>();
            seedInlineSourceRanges(facts, visible);
            var asked = new LinkedHashMap<String, TreeMap<Integer, Integer>>();
            collectRequestedCoverage(asked, finalBatch, sources);
            assertR24VisiblePages(late, snapshot, finalBatch, true, visible, sources);
            assertR24Coverage(asked, visible);
            var repeatRequest = fixture.model.requests.getLast();
            assertTrue(repeatRequest.messages().stream().anyMatch(message -> message.text().contains("\"latestBatchRepeat\"")));
            byte[] repeatWire = wireWithR24FactsSize(repeatRequest, 33687, 1048576, Map.of());
            var repeatedVisible = new LinkedHashMap<String, TreeMap<Integer, Integer>>();
            seedInlineSourceRanges(facts, repeatedVisible);
            assertR24VisiblePages(repeatWire, snapshot, repeated, true, repeatedVisible, sources);
            assertR24Coverage(asked, repeatedVisible);
            assertEquals(late.length, assertThrows(ModelRequestBudgetException.class,
                () -> wireWithR24FactsSize(latest, 33687, late.length - 1, Map.of())).encodedBytes());
            System.out.printf("R29_NODE_PREPARE_REPLAY freshEncoded=%d originalStartCap=881058 lateEncoded=%d repeatedEncoded=%d originalLateCap=100228 oldLateEncoded=129656%n",
                fresh, late.length, repeatWire.length);
        }
    }

    @Test
    void r41AssetLendingNodePublicCursorsUseExactOriginalSourcesUnderRecordedCeilings() throws Exception {
        // R41 Asset Lending stdout 1224–1510. Its single redacted 24-character pointer is omitted.
        String trace = """
            513444 H/331072/8000 H/190350/5000 H/194772/4000 H/185439/5000 H/227851/6000 R/21373/3000 R/26492/3000 R/29957/3000
            489937 S/0/8000 S/8000/8000 S/16000/8000 S/24000/8000 S/32000/8000 P/0/8000
            413964 H/159195/900 H/160679/1500 H/167252/1200 H/177258/850 H/190350/900 H/185439/1400 R/21373/1300 R/26492/1100
            374324 R/20251/8000 R/28251/4530 H/202631/4500 H/204279/6500
            335343 V/0/8000
            293903 V/0/8000 H/167252/1116 H/155670/1175 H/164596/718 R/21373/281 R/26492/420 R/29957/610
            250287 R/7163/2400 R/8737/2400 R/12734/3200 R/16326/4000 R/32781/1800 H/198772/4000
            205883 V/0/8000
            160870 V/0/8000 V/8000/8000 V/16000/8000 V/24000/8000 V/32000/4000
            125393 R/30567/4014 S/0/8000 S/8000/8000 S/16000/8000 S/24000/8000 S/32000/7324 P/0/7720
            92696 H/193439/8000 H/201439/8000 H/209439/1340 H/227851/6000 H/331072/8000 R/7163/3974 R/12734/3200 R/16326/5047
            70577 H/159195/900 H/160679/1500 H/168368/84 H/177258/850 H/185439/8000 R/21654/4838 R/26912/3045 R/30567/4014
            53152 H/193439/8000 H/201439/8000 H/209439/1340 H/227851/6000 H/331072/8000 R/7163/3974 R/12734/3200 R/16326/5047
            """;
        try (var fixture = new MirroredInstallerTestFixture(DownloadPoint.APT_UBUNTU, DownloadPoint.NODE_METADATA,
                 DownloadPoint.NODE_DISTRIBUTION, DownloadPoint.NODE_PACKAGE, DownloadPoint.DOTNET_METADATA, DownloadPoint.DOTNET_SDK, DownloadPoint.NUGET);
             var assisted = new DeploymentNativeHelperPackageImpactTest.Assisted(fixture);
             var command = diagnosedPreparation(fixture, "NODE", "24")) {
            var intent = intent("prepare", fixture, command);
            var original = new LinkedHashMap<String, Object>(
                new DeploymentFixedCommandProtocol(fixture.context).inspect(intent, command, fixture.layout, fixture.task));
            original.put("currentCommandEvidence", assisted.scope.effectFacts(intent, fixture.task));
            var evidence = DeploymentCommandSourceEvidence.project(DeploymentAiJson.facts(original, 1048576), intent,
                command, fixture.layout).orElseThrow();
            var initial = r31DeniedApproval(fixture, assisted, original, intent, command);
            var messages = new ArrayList<>(initial.messages());
            messages.set(messages.size() - 1, evidence.messages().getLast());
            var facts = JSON.readTree(evidence.messages().getLast().text());
            var manifest = facts.path(PHASE);
            var descriptor = manifest.path("preparationSelection");
            String snapshot = manifest.path("snapshotSha256").asText(), view = descriptor.path("sourcePointer").asText();
            var pointers = Map.of("H", manifest.at("/installedHelperSource/sourcePointer").asText(), "R", "/mirrorTransport/runtimeSource",
                "S", manifest.at("/mirrorBootstrapShell/sourcePointer").asText(), "P", manifest.at("/mirrorPolicySource/sourcePointer").asText(),
                "V", view);
            String originalView = collect(evidence, descriptor, PHASE);
            var sources = Map.of(pointers.get("H"), DeploymentManagedHelperBundle.renderScript(fixture.layout),
                pointers.get("R"), DeploymentMirrorRuntime.runtimeSource(), pointers.get("S"), r24Shell(fixture),
                pointers.get("P"), DeploymentMirrorTasks.policy(fixture.task), view, originalView);
            assertNodePreparationView(descriptor, sources);
            var requested = new LinkedHashMap<String, TreeMap<Integer, Integer>>();
            var requestedByPointer = new LinkedHashMap<String, TreeMap<Integer, Integer>>();
            var reader = evidence.reader(80);
            int fixed = wireWithR24FactsSize(copy(initial, messages, 530200), 25143, 530200, Map.of()).length;
            reader.offerInitialSource(messages);
            int first = wireWithR24FactsSize(copy(initial, messages, 530200), 25143, 530200, Map.of()).length;
            int reads = 0;
            int successful = 0;
            int lastBytes = first;
            int terminalCap = 530200;
            boolean unsent = false;
            Map<String, TreeMap<Integer, Integer>> visible = Map.of();
            for (String row : trace.lines().toList()) {
                String[] fields = row.strip().split(" ");
                int cap = Integer.parseInt(fields[0]);
                var calls = new ArrayList<AiToolCall>();
                for (int i = 1; i < fields.length; i++) {
                    var cursor = fields[i].split("/");
                    String pointer = pointers.get(cursor[0]);
                    int offset = Integer.parseInt(cursor[1]), limit = Integer.parseInt(cursor[2]);
                    calls.add(read("r41-node-public-" + ++reads, snapshot, pointer, offset, limit));
                    int next = (int) DeploymentEvidencePages.page(sources.get(pointer), offset, limit).get("next");
                    if (offset < next) addR24Range(requestedByPointer, pointer, offset, next);
                    addNodeRequestedRanges(requested, descriptor, pointer, offset, next);
                }
                var response = mockResponses(calls.toArray(AiToolCall[]::new));
                var results = reader.read(response);
                assertTrue(results.stream().noneMatch(AiToolResult::error));
                reader.continueReview(messages, response, results);
                terminalCap = cap;
                byte[] encoded = wireWithR24FactsSize(copy(initial, messages, 1048576), 25143, 1048576, Map.of());
                int before = encoded.length;
                if (before > cap) {
                    if (!reader.repackBeforeSend(messages, exactBudgetProblem(before, cap))) {
                        unsent = true;
                        System.out.printf("R43_NODE_PUBLIC_REPLAY cap=%d prefit=%d sent=false calls=%d%n", cap, before, calls.size());
                        break;
                    }
                    encoded = wireWithR24FactsSize(copy(initial, messages, cap), 25143, cap, Map.of());
                }
                lastBytes = encoded.length;
                visible = replayRetainedRanges(encoded, snapshot, sources).orElseThrow();
                assertFalse(visible.containsKey(view), "The view may occur in read receipts, never as duplicate raw text");
                verifyNodeInlineHasNoRetainedDuplicates(facts, visible, sources);
                System.out.printf("R43_NODE_PUBLIC_REPLAY cap=%d prefit=%d encoded=%d calls=%d missingCharacters=%d%n",
                    cap, before, lastBytes, calls.size(), missingNodeCharacters(requested, visible, sources));
                successful++;
            }
            assertEquals(77, reads);
            assertTrue(successful >= 12);
            if (!unsent) reader.read(response(Optional.empty(), verdict("r41-node-public-final", "APPROVE")));
            int missing = missingNodeCharacters(requested, visible, sources);
            assertTrue(missing > 0, "The last historical ceiling cannot cover this whole transcript");
            assertEquals(Optional.of("REQUESTED_SOURCE_MISSING"), reader.approvalEvidenceBlock());
            assertEquals(0, assisted.hosts.get());
            int originalCoverage = nodeCoverageLength(requestedByPointer);
            int canonicalCoverage = nodeCoverageLength(requested);
            assertTrue(canonicalCoverage < originalCoverage, "The recorded view overlaps actual original source requests");
            System.out.printf("R43_NODE_PUBLIC_TERMINAL fixed=%d first=%d accepted=%d lastEncoded=%d terminalCap=%d lastUnsent=%b"
                + " calls=%d pointerCoverage=%d canonicalCoverage=%d missingCharacters=%d verdictBlock=%s%n", fixed, first,
                successful, lastBytes, terminalCap, unsent, reads, originalCoverage, canonicalCoverage, missing,
                reader.approvalEvidenceBlock().orElse("NONE"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVE", "DENY", "INCOMPLETE"})
    void nativeNodeViewAndDirectReadShareExactCoverageBeforeIndependentHostGrant(String verdict) throws Exception {
        try (var fixture = new MirroredInstallerTestFixture(DownloadPoint.APT_UBUNTU, DownloadPoint.NODE_METADATA,
                 DownloadPoint.NODE_DISTRIBUTION, DownloadPoint.NODE_PACKAGE);
             var assisted = new DeploymentNativeHelperPackageImpactTest.Assisted(fixture);
             var request = diagnosedPreparation(fixture, "NODE", "24")) {
            var intent = intent("prepare", fixture, request);
            int cap = verdict.equals("INCOMPLETE") ? 70577 : 160870;
            var continuations = new LinkedHashMap<UUID, String>();
            var asked = new ArrayList<AiToolCall>();
            var bounded = wireBudgetContext(assisted.context, continuations, 0, () -> cap, delivered -> {
                try {
                    if (!fixture.model.requests.isEmpty()) {
                        reply(fixture, verdict("node-view-verdict", verdict.equals("INCOMPLETE") ? "APPROVE" : verdict));
                        return;
                    }
                    var manifest = JSON.readTree(delivered.messages().getLast().text()).path(PHASE);
                    String snapshot = manifest.path("snapshotSha256").asText();
                    var view = manifest.path("preparationSelection");
                    String shell = manifest.at("/mirrorBootstrapShell/sourcePointer").asText();
                    int offset = 0;
                    for (var part : view.path("sourceMap")) {
                        if (!shell.equals(nodeViewPartSource(view, part))) continue;
                        offset = part.path("offset").asInt();
                        asked.add(read("node-view-across-delimiter", snapshot, view.path("sourcePointer").asText(), offset - 128, 1500));
                        asked.add(read("node-view-direct-overlap", snapshot, shell, part.path("sourceOffset").asInt(), 500));
                        break;
                    }
                    assertTrue(offset > 128);
                    if (verdict.equals("INCOMPLETE")) {
                        String helper = manifest.at("/installedHelperSource/sourcePointer").asText();
                        for (int i = 0; i < 6; i++) asked.add(read("node-view-pressure-" + i, snapshot, helper, 400000 + i * 8000, 8000));
                    }
                    replyAtTotalBytes(fixture, continuations, 6000, "OPENAI_RESPONSES", asked.toArray(AiToolCall[]::new));
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            });
            Callable<OperationResult<ApprovalGrant>> approve = () -> DeploymentFixedCommandApproval.approveWithEvidence(
                bounded, assisted.host, intent, request, fixture.task, fixture.model.configurationRevision,
                "AUTOMATIC", false, assisted.scope);
            if (verdict.equals("APPROVE")) {
                assertEquals(TaskResult.SUCCEEDED, approve.call().outcome());
                assertEquals(List.of(intent), assisted.grantedIntents);
            } else {
                var blocked = assertThrows(ApiException.class, approve::call);
                if (verdict.equals("DENY")) assertEquals("DEPLOYMENT_COMMAND_REVIEW_DENIED", blocked.problem().eventCode());
                else {
                    assertEquals("EVIDENCE_INCOMPLETE_VERDICT", blocked.problem().context().get("reviewCode"));
                    assertEquals("REQUESTED_SOURCE_MISSING", blocked.problem().context().get("reviewEvidenceBlock"));
                }
                assertEquals(0, assisted.hosts.get());
            }
            assertEquals(2, fixture.model.requests.size());
            var last = fixture.model.requests.getLast();
            var facts = JSON.readTree(last.messages().getLast().text());
            var manifest = facts.path(PHASE);
            var view = manifest.path("preparationSelection");
            String snapshot = manifest.path("snapshotSha256").asText();
            var sources = Map.of(manifest.at("/installedHelperSource/sourcePointer").asText(),
                DeploymentManagedHelperBundle.renderScript(fixture.layout), "/mirrorTransport/runtimeSource",
                DeploymentMirrorRuntime.runtimeSource(), manifest.at("/mirrorBootstrapShell/sourcePointer").asText(), r24Shell(fixture));
            byte[] encoded = wire(last, cap, Map.of());
            var visible = replayRetainedRanges(encoded, snapshot, sources).orElseThrow();
            verifyNodeInlineHasNoRetainedDuplicates(facts, visible, sources);
            var requested = new LinkedHashMap<String, TreeMap<Integer, Integer>>();
            var retained = last.messages().get(last.messages().size() - 2).text();
            var receipt = JSON.readTree(retained.lines().skip(1).findFirst().orElseThrow()).path("latestReads");
            for (int i = 0; i < asked.size(); i++) {
                var input = DeploymentAiJson.arguments(asked.get(i));
                var row = receipt.get(i);
                assertEquals(input.path("pointer"), row.path("pointer"));
                assertEquals(input.path("offset"), row.path("offset"));
                assertFalse(row.path("rejected").asBoolean());
                int offset = input.path("offset").asInt();
                addNodeRequestedRanges(requested, view, input.path("pointer").asText(), offset, offset + input.path("limit").asInt());
            }
            int missing = missingNodeCharacters(requested, visible, sources);
            if (verdict.equals("INCOMPLETE")) assertTrue(missing > 0);
            else assertEquals(0, missing);
        }
    }

    private static void assertNodePreparationView(JsonNode descriptor, Map<String, String> sources) throws Exception {
        String oldPointer = descriptor.path("sourcePointer").asText();
        var restored = new StringBuilder();
        for (var part : descriptor.path("sourceMap")) {
            int offset = part.path("offset").asInt(), next = part.path("next").asInt();
            assertEquals(restored.length(), offset);
            switch (part.path("kind").asText()) {
                case "LINE_FEED" -> {
                    assertEquals(1, next - offset);
                    assertFalse(part.has("source"));
                    restored.append('\n');
                }
                case "SOURCE_TEXT" -> {
                    int original = part.path("sourceOffset").asInt();
                    restored.append(sources.get(nodeViewPartSource(descriptor, part)), original, original + next - offset);
                }
                default -> fail("Unexpected view reconstruction part");
            }
            assertEquals(next, restored.length());
        }
        assertEquals(sources.get(oldPointer), restored.toString());
        assertIdentity(descriptor, restored.toString());
    }

    private static void addNodeRequestedRanges(Map<String, TreeMap<Integer, Integer>> coverage, JsonNode descriptor,
                                               String pointer, int offset, int next) {
        if (!pointer.equals(descriptor.path("sourcePointer").asText())) {
            if (offset < next) addR24Range(coverage, pointer, offset, next);
            return;
        }
        for (var part : descriptor.path("sourceMap")) {
            int start = Math.max(offset, part.path("offset").asInt()), end = Math.min(next, part.path("next").asInt());
            if (start >= end || !part.path("kind").asText().equals("SOURCE_TEXT")) continue;
            int translation = part.path("sourceOffset").asInt() - part.path("offset").asInt();
            addR24Range(coverage, nodeViewPartSource(descriptor, part), start + translation, end + translation);
        }
    }

    private static String nodeViewPartSource(JsonNode descriptor, JsonNode part) {
        return descriptor.path("sourcePointers").path(part.path("source").asInt(-1)).asText();
    }

    private static int missingNodeCharacters(Map<String, TreeMap<Integer, Integer>> requested,
            Map<String, TreeMap<Integer, Integer>> visible, Map<String, String> sources) {
        int missing = 0;
        for (var source : requested.entrySet()) {
            var alreadyVisible = visible.getOrDefault(source.getKey(), new TreeMap<>());
            for (var range : source.getValue().entrySet())
                for (var gap : DeploymentEvidencePages.missing(sources.get(source.getKey()), range.getKey(), range.getValue(), alreadyVisible))
                    missing += (int) gap.get("next") - (int) gap.get("offset");
        }
        return missing;
    }

    private static int nodeCoverageLength(Map<String, TreeMap<Integer, Integer>> ranges) {
        return ranges.values().stream().flatMap(source -> source.entrySet().stream())
            .mapToInt(range -> range.getValue() - range.getKey()).sum();
    }

    private static void verifyNodeInlineHasNoRetainedDuplicates(JsonNode facts, Map<String, TreeMap<Integer, Integer>> visible,
                                                                  Map<String, String> sources) {
        var inline = new LinkedHashMap<String, TreeMap<Integer, Integer>>();
        seedInlineSourceRanges(facts, inline);
        for (var source : inline.entrySet()) {
            String original = sources.get(source.getKey());
            if (original == null) {
                assertFalse(visible.containsKey(source.getKey()), "An inline-only frame must not reappear in retained source");
                continue;
            }
            var retained = visible.computeIfAbsent(source.getKey(), ignored -> new TreeMap<>());
            for (var range : source.getValue().entrySet()) {
                int offset = range.getKey(), next = range.getValue();
                assertEquals(List.of(Map.of("offset", offset, "next", next, "text", original.substring(offset, next))),
                    DeploymentEvidencePages.missing(original, offset, next, retained), "Retained source must not repeat inline bytes");
                DeploymentEvidencePages.remember(retained, offset, next);
            }
        }
    }

    @Test
    void preparationRebaseKeepsExactUsefulEarlierPagesAndAvoidsDuplicateResends() throws Exception {
        try (var fixture = new MirroredInstallerTestFixture();
             var request = diagnosedPreparation(fixture, "PHP", "8.3")) {
            var intent = intent("prepare", fixture, request);
            var facts = new DeploymentFixedCommandProtocol(fixture.context).inspect(intent, request, fixture.layout, fixture.task);
            var evidence = DeploymentCommandSourceEvidence.project(DeploymentAiJson.facts(facts, 1048576), intent, request,
                fixture.layout).orElseThrow();
            var messages = new ArrayList<>(evidence.messages());
            var current = JSON.readTree(messages.getLast().text());
            String snapshot = current.at("/" + PHASE + "/snapshotSha256").asText();
            String pointer = "/mirrorTransport/runtimeSource";
            String runtime = DeploymentMirrorRuntime.runtimeSource();
            var reader = evidence.reader(16);
            for (int batch = 0; batch < 2; batch++) {
                var response = mockResponses(read("r28-distinct-" + batch, snapshot, pointer, 40000 + batch * 8000, 8000));
                reader.continueReview(messages, response, reader.read(response));
            }
            assertFalse(messages.stream().anyMatch(message -> message.continuation().isPresent() || message.toolResult().isPresent()));
            String retained = messages.get(messages.size() - 2).text();
            assertTrue(retained.contains(runtime.substring(40000, 48000)), "Rebase preserves a full earlier 8 KiB page");
            assertTrue(retained.contains(runtime.substring(48000, 56000)), "Rebase preserves all exact current bytes");
            var repeated = reader.read(mockResponses(read("r28-repeated", snapshot, pointer, 40000, 8000))).getFirst();
            var value = pagePayload(repeated.text()).path("value");
            assertTrue(value.path("newRanges").isArray());
            assertTrue(value.path("newRanges").isEmpty(), "A currently visible range is not serialized again");
        }
    }

    @Test
    void r27AssetLendingPreparationSuppliesExactDotnetTransferAndRedirectBeforeAnyRead() throws Exception {
        try (var fixture = new MirroredInstallerTestFixture(); var command = diagnosedPreparation(fixture, "DOTNET", "10");
             var assisted = new DeploymentNativeHelperPackageImpactTest.Assisted(fixture)) {
            var intent = intent("prepare", fixture, command);
            var original = new DeploymentFixedCommandProtocol(fixture.context).inspect(intent, command, fixture.layout, fixture.task);
            var evidence = DeploymentCommandSourceEvidence.project(DeploymentAiJson.facts(original, 1048576), intent, command,
                fixture.layout).orElseThrow();
            var facts = JSON.readTree(evidence.messages().getLast().text());
            var manifest = facts.path(PHASE);
            var policy = facts.at("/mirrorTransport/frozenPolicy/points");
            var originalPolicy = JSON.valueToTree(original).at("/mirrorTransport/frozenPolicy/points");
            var points = new HashSet<String>();
            policy.fieldNames().forEachRemaining(points::add);
            assertEquals(Set.of("DOTNET_METADATA", "DOTNET_SDK"), points);
            for (String point : List.of("DOTNET_METADATA", "DOTNET_SDK"))
                assertEquals(originalPolicy.path(point), policy.path(point));
            var selection = manifest.path("preparationSelection");
            String body = evidence.messages().get(selection.path("ordinal").asInt()).text().substring(selection.path("headerCharacters").asInt());
            String mirror = DeploymentMirrorRuntime.runtimeSource();
            var source = facts.at("/mirrorTransport/runtimeSource");
            for (var range : manifest.path("selectedPreparationRanges")) {
                if (!source.path("sourcePointer").equals(range.path("sourcePointer"))) continue;
                int start = range.path("startCharacter").asInt(), offset = range.path("offset").asInt(), next = range.path("next").asInt();
                assertEquals(mirror.substring(offset, next), body.substring(start, start + next - offset));
            }
            for (String boundary : List.of("if ecosystem == 'DOTNET':", "official_url(f['url']), 'sha512'",
                    "def validate_redirect(", "def redirect_path(", "class MirrorRedirect(", "redirected.remove_header(",
                    "policy.validate_url(endpoint, policy.maps(endpoint, original))", "ProxyHandler({})",
                    "do_handshake()", "digest.hexdigest().lower() != expected.lower()", "os.replace(temporary, target)"))
                assertTrue(body.contains(boundary), boundary);

            var replay = new R24Review("prepare", "DOTNET", "10", 37251, List.of(
                new R24Turn(103019, 1048576, 0, 20852, 85741,
                    "43/0/8000 45/0/8000 45/12000/8000 30/3984/10000 30/13643/6000 30/16170/16500 30/144991/5000 45/13053/5500"),
                new R24Turn(123871, 1048576, 0, 0, 135916, "")));
            replayToolchainReview("R27", "asset-lending", replay, command, fixture, assisted, false, 0);
            assertEquals(0, assisted.hosts.get());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void rustPreparationIncludesExactSystemReuseAndDistributionBoundaries(boolean diagnosed) throws Exception {
        try (var fixture = new MirroredInstallerTestFixture(); var command = rustPreparation(fixture, diagnosed)) {
            var intent = intent("prepare", fixture, command);
            var facts = new DeploymentFixedCommandProtocol(fixture.context).inspect(intent, command, fixture.layout, fixture.task);
            var evidence = DeploymentCommandSourceEvidence.project(DeploymentAiJson.facts(facts, 1048576), intent, command,
                fixture.layout).orElseThrow();
            var projected = JSON.readTree(evidence.messages().getLast().text());
            var manifest = projected.path(PHASE);
            var frame = manifest.path("preparationSelection");
            String body = evidence.messages().get(frame.path("ordinal").asInt()).text()
                .substring(frame.path("headerCharacters").asInt());
            var helper = DeploymentManagedHelperBundle.renderScript(fixture.layout);
            var mirror = DeploymentMirrorRuntime.runtimeSource();
            var originals = Map.of("/toolchainReviewEvidence/installedHelperSource", helper, "/mirrorTransport/runtimeSource", mirror);
            assertIdentity(manifest.path("installedHelperSource"), helper);
            assertIdentity(projected.at("/mirrorTransport/runtimeSource"), mirror);
            assertIdentity(frame, body);
            assertIdentity(projected.path("exactAction"), intent.exactAction());
            assertTrue(body.contains(diagnosed ? "prepare(request['arguments'])" : "        prepare(args)\n"));
            for (var range : manifest.path("selectedPreparationRanges")) {
                String source = originals.get(range.path("sourcePointer").asText());
                assertNotNull(source);
                int offset = range.path("offset").asInt(), next = range.path("next").asInt();
                int start = range.path("startCharacter").asInt();
                assertEquals(source.substring(offset, next), body.substring(start, start + next - offset));
            }
            for (String boundary : List.of("def discover_system(", "def materialize_system(", "def verify_system(",
                "def saved_installation_marker(", "'RUST': ['bin/rustc', '--version']", "def probe_output(",
                "checksum_text(url + '.sha256')", "def checksum_text(", "digest.hexdigest().lower() != expected.lower()",
                "def extract(archive, destination):", "archive entry has a link ancestor", "total > 8 * MAX_ARCHIVE",
                "def rust_read_manifest(", "def rust_component_path(", "def rust_component_plan(", "def rust_component_copy(",
                "target.open('xb')", "install = install_with_rust_distribution", "return install_rust_distribution(",
                "def probe_managed_rust(", "'--clear-groups', '--no-new-privs'", "probe = probe_with_rust_distribution"))
                assertTrue(body.contains(boundary), boundary);
            assertFalse(body.contains("str(source / 'install.sh')"));
        }
    }

    @Test
    void r38NodeBindReadsPreviouslyRejectedShellCoordinatesInTheFixedSnapshot() throws Exception {
        try (var fixture = new MirroredInstallerTestFixture(); var command = r26Binding(fixture, "NODE", "24.22.3")) {
            var action = intent("bind", fixture, command);
            var facts = new DeploymentFixedCommandProtocol(fixture.context).inspect(action, command, fixture.layout, fixture.task);
            var evidence = DeploymentCommandSourceEvidence.project(DeploymentAiJson.facts(facts, 1048576), action,
                command, fixture.layout).orElseThrow();
            var manifest = JSON.readTree(evidence.messages().getLast().text()).path(PHASE);
            var shell = manifest.path("mirrorBootstrapShell");
            String pointer = shell.path("sourcePointer").asText();
            String source = r24Shell(fixture);
            var reader = evidence.reader(8);
            int[] offsets = {0, 8000, 16000, 24000, 32000};
            var calls = new ArrayList<AiToolCall>();
            for (int offset : offsets) {
                var arguments = JSON.createObjectNode().put("pointer", pointer).put("offset", offset)
                    .put("limit", Math.min(8000, source.length() - offset));
                calls.add(new AiToolCall("r38-node-bind-" + offset, "evidence", arguments.toString()));
            }
            var response = mockResponses(calls.toArray(AiToolCall[]::new));
            var pages = reader.read(response);
            for (int i = 0; i < offsets.length; i++) {
                int offset = offsets[i];
                assertFalse(pages.get(i).error(), pages.get(i).text());
                assertEquals(source.substring(offset, Math.min(offset + 8000, source.length())),
                    pagePayload(pages.get(i).text()).at("/value/text").asText());
            }
            var messages = new ArrayList<>(evidence.messages());
            reader.continueReview(messages, response, pages);
            reader.read(response(Optional.empty(), verdict("source-visible", "APPROVE")));
            assertTrue(reader.approvalEvidenceBlock().isEmpty());
            var invalid = reader.read(mockResponses(read("foreign-snapshot", "0".repeat(64), pointer, 0, 1),
                new AiToolCall("outside-source", "evidence", JSON.createObjectNode().put("pointer", pointer)
                    .put("offset", source.length() + 1).put("limit", 1).toString())));
            assertTrue(invalid.getFirst().text().contains("SNAPSHOT_MISMATCH"));
            assertTrue(invalid.getLast().text().contains("CURSOR_INVALID"));
            assertEquals(Optional.of("READ_REJECTED"), reader.approvalEvidenceBlock());
        }
    }

    @Test
    void r49AssetLendingLocalNodeBindingFitsOriginalTerminalCeilingWithExactEvidence() throws Exception {
        int cap = 70049;
        int factsFloor = 35758;
        String helperPointer = "/toolchainReviewEvidence/installedHelperSource";
        String shellPointer = "/toolchainReviewEvidence/mirrorBootstrapShell";
        try (var fixture = new MirroredInstallerTestFixture(DownloadPoint.values());
             var assisted = new DeploymentNativeHelperPackageImpactTest.Assisted(fixture);
             var mirrored = r26Binding(fixture, "NODE", "24.22.3")) {
            var original = DeploymentManagedHelperBundle.verifiedBody(mirrored.script(), fixture.layout);
            String prefix = "'" + fixture.layout.helperPath() + "' prepare-toolchains 'bind' '";
            assertTrue(original.startsWith(prefix));
            String encoded = original.substring(prefix.length(), original.length() - 1);
            String launcher = DeploymentManagedHelperBundle.renderLocalNodeBinding(encoded, fixture.task.id(),
                fixture.server, 180, fixture.layout);
            try (var request = new CommandRequest(launcher, new byte[0], Duration.ofSeconds(180), 65536)) {
                var protocol = new DeploymentFixedCommandProtocol(fixture.context);
                var oldAction = intent("bind", fixture, mirrored);
                var oldFacts = protocol.inspect(oldAction, mirrored, fixture.layout, fixture.task);
                var oldEvidence = DeploymentCommandSourceEvidence.project(DeploymentAiJson.facts(oldFacts, 1048576),
                    oldAction, mirrored, fixture.layout).orElseThrow();
                var oldManifest = JSON.readTree(oldEvidence.messages().getLast().text()).path(PHASE);
                assertFalse(oldManifest.path("mirrorBootstrapShell").isMissingNode());
                var oldReader = oldEvidence.reader(8);
                var invalidLimit = oldReader.read(mockResponses(boundBindingRead("old-8500", shellPointer, 16500, 8500))).getFirst();
                assertTrue(invalidLimit.error());
                assertEquals(Optional.of("READ_REJECTED"), oldReader.approvalEvidenceBlock());

                var action = intent("bind", fixture, request);
                var facts = new LinkedHashMap<String, Object>(protocol.inspect(action, request, fixture.layout, fixture.task));
                assertEquals(oldFacts.get("decodedToolchainBinding"), facts.get("decodedToolchainBinding"));
                facts.put("currentCommandEvidence", assisted.scope.effectFacts(action, fixture.task));
                var sourceEvidence = DeploymentCommandSourceEvidence.project(DeploymentAiJson.facts(facts, 1048576),
                    action, request, fixture.layout).orElseThrow();
                var projected = JSON.readTree(sourceEvidence.messages().getLast().text());
                String ownSnapshot = projected.at("/" + PHASE + "/snapshotSha256").asText();
                assertNotEquals(oldManifest.path("snapshotSha256").asText(), ownSnapshot);
                assertEquals(action.exactAction(), projected.path("exactAction").asText());
                assertFalse(projected.at("/localNodeBinding/materialStaging").asBoolean(true));
                assertTrue(projected.path("mirrorTransport").isMissingNode());
                assertTrue(projected.at("/" + PHASE + "/mirrorBootstrapShell").isMissingNode());

                var stale = sourceEvidence.reader(8);
                assertTrue(stale.read(mockResponses(read("old-binding-snapshot", oldManifest.path("snapshotSha256").asText(),
                    helperPointer, 0, 1))).getFirst().text().contains("SNAPSHOT_MISMATCH"));
                assertEquals(Optional.of("READ_REJECTED"), stale.approvalEvidenceBlock());
                var missing = sourceEvidence.reader(8);
                assertTrue(missing.read(mockResponses(boundBindingRead("old-shell-pointer", shellPointer, 0, 7000))).getFirst().error());
                assertEquals(Optional.of("READ_REJECTED"), missing.approvalEvidenceBlock());

                int[][] helpingRanges = {{343500, 300}, {146000, 250}, {147500, 500}};
                var calls = new ArrayList<AiToolCall>();
                var requested = new LinkedHashMap<String, TreeMap<Integer, Integer>>();
                String helper = DeploymentManagedHelperBundle.renderScript(fixture.layout);
                for (int[] range : helpingRanges) {
                    int start = range[0], limit = range[1];
                    assertTrue(start + limit <= helper.length());
                    calls.add(boundBindingRead("asset-node-source-" + start, helperPointer, start, limit));
                    addR24Range(requested, helperPointer, start, start + limit);
                }
                fixture.model.replies.add(success(mockResponses(calls.toArray(AiToolCall[]::new))));
                reply(fixture, verdict("asset-node-denial", "DENY"));
                var bounded = wireBudgetContext(assisted.context, Map.of(), factsFloor, () -> cap);
                var result = new DeploymentAiJson(bounded).askCommand(
                    "Inspect the exact Node binding. " + DeploymentFixedCommandApproval.commandReviewInstructions(false),
                    facts, fixture.model.configurationRevision, fixture.task, DeploymentReviewDecision::malformed, action, request);
                assertEquals("DENY", result.path("decision").asText());
                assertEquals(2, fixture.model.requests.size());
                var first = fixture.model.requests.getFirst();
                var terminal = fixture.model.requests.getLast();
                byte[] firstWire = wireWithR24FactsSize(first, factsFloor, cap, Map.of());
                byte[] terminalWire = wireWithR24FactsSize(terminal, factsFloor, cap, Map.of());
                var terminalFacts = JSON.readTree(terminal.messages().getLast().text());
                var selectedHelper = terminalFacts.at("/" + PHASE + "/installedHelperSource");
                assertIdentity(selectedHelper, helper);
                var descriptor = terminalFacts.at("/" + PHASE + "/frames/0");
                var frame = terminal.messages().get(descriptor.path("ordinal").asInt() + 1);
                int header = descriptor.path("headerCharacters").asInt();
                assertIdentity(descriptor, frame.text().substring(header));
                var sections = new LinkedHashSet<String>();
                for (var range : selectedHelper.path("selectedRanges")) {
                    sections.add(range.path("section").asText());
                    int offset = range.path("offset").asInt(), next = range.path("next").asInt();
                    int start = header + range.path("startCharacter").asInt();
                    assertEquals(helper.substring(offset, next), frame.text().substring(start, start + next - offset));
                }
                assertTrue(sections.containsAll(Set.of("shippedCatalogAndRoot", "mainAdmissionAndLock", "literalBindBranch",
                    "processProbe", "ownedDirectory", "installedToolProbes", "validateAndSeal")));
                var visible = replayRetainedRanges(terminalWire, terminalFacts.at("/" + PHASE + "/snapshotSha256").asText(),
                    Map.of(helperPointer, helper)).orElseThrow();
                assertReplayRangesPresent(requested, visible, terminalFacts);
                assertEquals(0, assisted.hosts.get());
                System.out.printf("R54_ASSET_NODE_BIND_RESPONSES launcher=%d factsFloor=%d first=%d terminal=%d cap=%d helperRanges=%d verdict=DENY%n",
                    launcher.getBytes(StandardCharsets.UTF_8).length, factsFloor, firstWire.length, terminalWire.length, cap, sections.size());
            }
        }
    }

    private static AiToolCall boundBindingRead(String id, String pointer, int offset, int limit) {
        return new AiToolCall(id, "evidence", JSON.createObjectNode().put("pointer", pointer)
            .put("offset", offset).put("limit", limit).toString());
    }

    @Test
    void r49RustBindRetainsObservedShellPagesAgainstItsOwnSnapshot() throws Exception {
        int cap = 960481;
        try (var fixture = new MirroredInstallerTestFixture(DownloadPoint.values());
             var command = r26Binding(fixture, "RUST", "1.95.0")) {
            var action = intent("bind", fixture, command);
            var facts = new DeploymentFixedCommandProtocol(fixture.context).inspect(action, command, fixture.layout, fixture.task);
            String pointer = "/toolchainReviewEvidence/mirrorBootstrapShell";
            String shell = r24Shell(fixture);
            assertTrue(shell.length() >= 38195);
            int[][][] batches = {{{0, 8000}, {11000, 8000}, {34000, 4195}},
                {{0, 8000}, {8000, 8000}, {16000, 8000}, {24000, 8000}, {32000, 6195}}};
            for (int batch = 0; batch < batches.length; batch++) {
                var calls = new ArrayList<AiToolCall>();
                for (int[] range : batches[batch]) {
                    var arguments = JSON.createObjectNode().put("pointer", pointer).put("offset", range[0]).put("limit", range[1]);
                    calls.add(new AiToolCall("r49-rust-bind-" + batch + "-" + range[0], "evidence", arguments.toString()));
                }
                fixture.model.replies.add(success(mockResponses(calls.toArray(AiToolCall[]::new))));
            }
            reply(fixture, verdict("r49-rust-bind-denied", "DENY"));
            var result = new DeploymentAiJson(fixture.context).askCommand("Review the original Rust binding", facts,
                fixture.model.configurationRevision, fixture.task, DeploymentReviewDecision::malformed, action, command);
            assertEquals("DENY", result.path("decision").asText());
            assertEquals(3, fixture.model.requests.size());
            var initial = fixture.model.requests.getFirst();
            var nativeEvidence = initial.tools().stream().filter(tool -> tool.name().equals("evidence")).findFirst().orElseThrow();
            var schema = JSON.readTree(nativeEvidence.inputSchema());
            assertTrue(schema.at("/properties/snapshotSha256").isMissingNode());
            assertEquals(3, schema.path("required").size());
            var terminal = fixture.model.requests.getLast();
            var manifest = JSON.readTree(terminal.messages().getLast().text()).path(PHASE);
            String snapshot = manifest.path("snapshotSha256").asText();
            byte[] encoded = wire(terminal, cap, Map.of());
            var visible = replayRetainedRanges(encoded, snapshot, Map.of(pointer, shell)).orElseThrow();
            var covered = visible.get(pointer);
            assertEquals(Map.of(0, 38195), covered);
            assertTrue(encoded.length <= cap);

            var evidence = DeploymentCommandSourceEvidence.project(DeploymentAiJson.facts(facts, 1048576), action,
                command, fixture.layout).orElseThrow();
            var reader = evidence.reader(8);
            var ownSnapshot = JSON.readTree(evidence.messages().getLast().text()).at("/" + PHASE + "/snapshotSha256").asText();
            assertFalse(reader.read(mockResponses(read("own-snapshot", ownSnapshot, pointer, 0, 1))).getFirst().error());
            var stale = reader.read(mockResponses(read("foreign-snapshot", "0".repeat(64), pointer, 0, 8000))).getFirst();
            assertTrue(stale.error());
            assertTrue(stale.text().contains("SNAPSHOT_MISMATCH"));
            reader.read(response(Optional.empty(), verdict("stale-source-approval", "APPROVE")));
            assertEquals(Optional.of("READ_REJECTED"), reader.approvalEvidenceBlock());
        }
    }

    @Test
    void r38PythonPreparationActualCursorBatchesFitTheTerminalWireAllowance() throws Exception {
        int cap = 121089;
        try (var fixture = new MirroredInstallerTestFixture(DownloadPoint.values());
             var assisted = new DeploymentNativeHelperPackageImpactTest.Assisted(fixture);
             var command = diagnosedPreparation(fixture, "PYTHON", "3.12")) {
            var action = intent("prepare", fixture, command);
            var facts = new LinkedHashMap<String, Object>(
                new DeploymentFixedCommandProtocol(fixture.context).inspect(action, command, fixture.layout, fixture.task));
            facts.put("currentCommandEvidence", assisted.scope.effectFacts(action, fixture.task));
            var unpadded = DeploymentCommandSourceEvidence.project(DeploymentAiJson.facts(facts, 1048576), action, command,
                fixture.layout).orElseThrow();
            int padding = Math.max(0, 34589 - unpadded.messages().getLast().text().length() - 24);
            if (padding > 0) facts.put("replayFixturePadding", "x".repeat(padding));
            var evidence = DeploymentCommandSourceEvidence.project(DeploymentAiJson.facts(facts, 1048576), action, command,
                fixture.layout).orElseThrow();
            var initial = r31DeniedApproval(fixture, assisted, facts, action, command);
            var messages = new ArrayList<>(initial.messages());
            messages.set(messages.size() - 1, evidence.messages().getLast());
            int fixed = wire(copy(initial, messages, 1048576), 1048576, Map.of()).length;
            var reader = evidence.reader(16);
            reader.offerInitialSource(messages);
            var manifest = JSON.readTree(evidence.messages().getLast().text()).path(PHASE);
            String snapshot = manifest.path("snapshotSha256").asText();
            String policy = manifest.at("/mirrorPolicySource/sourcePointer").asText();
            String shell = manifest.at("/mirrorBootstrapShell/sourcePointer").asText();
            String helper = manifest.at("/installedHelperSource/sourcePointer").asText();
            String runtime = "/mirrorTransport/runtimeSource";
            var sources = Map.of(policy, DeploymentMirrorTasks.policy(fixture.task), shell, r24Shell(fixture),
                helper, DeploymentManagedHelperBundle.renderScript(fixture.layout), runtime, DeploymentMirrorRuntime.runtimeSource());
            var batches = List.of(
                List.of(read("r38-python-1", snapshot, policy, 0, 8000), read("r38-python-2", snapshot, policy, 8000, 2000),
                    read("r38-python-3", snapshot, helper, 186612, 3000), read("r38-python-4", snapshot, shell, 13709, 2000),
                    read("r38-python-5", snapshot, runtime, 3984, 4000)),
                List.of(read("r38-python-6", snapshot, helper, 186612, 5753), read("r38-python-7", snapshot, helper, 224950, 1775),
                    read("r38-python-8", snapshot, helper, 277223, 4191), read("r38-python-9", snapshot, helper, 205808, 1885)),
                List.of(read("r38-python-10", snapshot, policy, 0, 8000), read("r38-python-11", snapshot, policy, 8000, 1272),
                    read("r38-python-12", snapshot, shell, 13709, 1118), read("r38-python-13", snapshot, runtime, 3984, 3157)));
            byte[] encoded = wire(copy(initial, messages, 1048576), 1048576, Map.of());
            int first = encoded.length;
            assertTrue(first < cap, "first request " + first);
            for (var batch : batches) {
                var reply = mockResponses(batch.toArray(AiToolCall[]::new));
                var results = reader.read(reply);
                assertEquals(List.of(), results.stream().filter(AiToolResult::error).map(AiToolResult::text).toList());
                reader.continueReview(messages, reply, results);
                encoded = wire(copy(initial, messages, 1048576), 1048576, Map.of());
                if (encoded.length > cap) {
                    assertTrue(reader.repackBeforeSend(messages, exactBudgetProblem(encoded.length, cap)));
                    encoded = wire(copy(initial, messages, cap), cap, Map.of());
                }
                assertTrue(encoded.length <= cap);
                assertTrue(replayRetainedRanges(encoded, snapshot, sources).isPresent());
            }
            reader.read(response(Optional.empty(), verdict("r38-python-verdict", "APPROVE")));
            System.out.printf("R38_PYTHON_REPLAY fixedEncoded=%d firstEncoded=%d terminalEncoded=%d cap=%d verdictBlock=%s%n",
                fixed, first, encoded.length, cap, reader.approvalEvidenceBlock().orElse("NONE"));
            String header = messages.get(messages.size() - 2).text().lines().skip(1).findFirst().orElseThrow();
            var deferred = JSON.readTree(header).path("previouslyDeferred");
            assertTrue(reader.approvalEvidenceBlock().isEmpty(), () -> reader.approvalEvidenceBlock() + " " + deferred);
        }
    }

    @Test
    void kotlinPreparationIncludesExactJavaPrerequisiteChecksInTheIndependentRequest() throws Exception {
        int maximum = 1048576;
        try (var fixture = new MirroredInstallerTestFixture(DownloadPoint.KOTLIN_DISTRIBUTION);
             var assisted = new DeploymentNativeHelperPackageImpactTest.Assisted(fixture);
             var command = diagnosedPreparation(fixture, "KOTLIN", "2.3", fixture.layout.toolchains() + "/versions/java-" + "c".repeat(64))) {
            var action = intent("prepare", fixture, command);
            var facts = new LinkedHashMap<String, Object>(
                new DeploymentFixedCommandProtocol(fixture.context).inspect(action, command, fixture.layout, fixture.task));
            facts.put("currentCommandEvidence", assisted.scope.effectFacts(action, fixture.task));
            var evidence = DeploymentCommandSourceEvidence.project(DeploymentAiJson.facts(facts, maximum), action, command,
                fixture.layout).orElseThrow();
            var initial = r31DeniedApproval(fixture, assisted, facts, action, command);
            var manifest = JSON.readTree(evidence.messages().getLast().text()).path(PHASE);
            var selection = manifest.path("preparationSelection");
            var required = manifest.path("requiredPreparationRanges");
            String body = evidence.messages().get(selection.path("ordinal").asInt()).text()
                .substring(selection.path("headerCharacters").asInt());
            String source = DeploymentManagedHelperBundle.renderScript(fixture.layout);
            var selected = new LinkedHashMap<String, JsonNode>();
            for (var range : manifest.path("selectedPreparationRanges")) {
                selected.put(range.path("section").asText(), range);
            }
            assertEquals(15, required.size());
            for (var range : required) {
                var inline = selected.get(range.path("section").asText());
                assertNotNull(inline);
                assertEquals(range.path("sourcePointer"), inline.path("sourcePointer"));
                int start = inline.path("startCharacter").asInt(), offset = range.path("offset").asInt(),
                    next = range.path("next").asInt();
                assertEquals(source.substring(offset, next), body.substring(start, start + next - offset));
            }
            for (String original : List.of("def require_java_executable(", "def require_system_java_record(",
                    "def require_prepared_java(", "probe('JAVA', directory, version)", "str(require_prepared_java(args[5]))")) {
                assertTrue(body.contains(original), original);
            }
            assertTrue(evidence.instructions().contains("requiredPreparationRanges"));
            byte[] encoded = wire(initial, maximum, Map.of());
            assertTrue(encoded.length <= maximum);
            System.out.printf("R50_KOTLIN_JAVA_REVIEW required=%d firstEncoded=%d max=%d%n",
                required.size(), encoded.length, maximum);
        }
    }

    @Test
    void sourceBuiltRubyReviewBindsFrozenMakeToTheOriginalIsolatedWorkerAndRequiresItsRoute() throws Exception {
        int maximum = 1048576;
        try (var fixture = new MirroredInstallerTestFixture(DownloadPoint.APT_UBUNTU, DownloadPoint.RUBY_DISTRIBUTION);
             var assisted = new DeploymentNativeHelperPackageImpactTest.Assisted(fixture);
             var command = diagnosedPreparation(fixture, "RUBY", "3.3")) {
            var action = intent("prepare", fixture, command);
            var facts = new LinkedHashMap<String, Object>(
                new DeploymentFixedCommandProtocol(fixture.context).inspect(action, command, fixture.layout, fixture.task));
            facts.put("currentCommandEvidence", assisted.scope.effectFacts(action, fixture.task));
            var evidence = DeploymentCommandSourceEvidence.project(DeploymentAiJson.facts(facts, maximum), action, command,
                fixture.layout).orElseThrow();
            var initial = r31DeniedApproval(fixture, assisted, facts, action, command);
            var factsFrame = JSON.readTree(evidence.messages().getLast().text());
            var endpoint = factsFrame.at("/mirrorTransport/frozenPolicy/points/GNU_MAKE_SOURCE/0");
            assertEquals(DownloadPoint.GNU_MAKE_PROVIDER, endpoint.path("providerId").asText());
            assertEquals(DownloadPoint.GNU_MAKE_ORIGIN, endpoint.path("baseUrl").asText());
            assertTrue(endpoint.path("official").asBoolean());
            var manifest = factsFrame.path(PHASE);
            var selection = manifest.path("preparationSelection");
            String body = evidence.messages().get(selection.path("ordinal").asInt()).text()
                .substring(selection.path("headerCharacters").asInt());
            var helper = manifest.path("installedHelperSource");
            String pointer = helper.path("sourcePointer").asText();
            String source = DeploymentManagedHelperBundle.renderScript(fixture.layout);
            var sources = Map.of(pointer, source, manifest.at("/mirrorBootstrapShell/sourcePointer").asText(), r24Shell(fixture),
                "/mirrorTransport/runtimeSource", DeploymentMirrorRuntime.runtimeSource());
            var inline = new LinkedHashMap<String, TreeMap<Integer, Integer>>();
            for (var range : manifest.path("selectedPreparationRanges")) {
                String sourcePointer = range.path("sourcePointer").asText();
                int offset = range.path("offset").asInt(), next = range.path("next").asInt();
                int start = range.path("startCharacter").asInt();
                assertEquals(sources.get(sourcePointer).substring(offset, next), body.substring(start, start + next - offset));
                addR24Range(inline, sourcePointer, offset, next);
            }
            for (String expected : List.of("RUBY_WORKER = gnu_make_worker(", "def bootstrap_gnu_make(", "\"./build.sh\"",
                    "GNU_MAKE_SOURCE_BYTES = 2348200", "dd16fb1d67bfab79a72f5e8390735c49e3e8e70b4945a15ab1f81ddb78658fb3",
                    "def prepare_gnu_make_source(", "maximum=GNU_MAKE_SOURCE_BYTES", "/tmp/wtl-gnu-make-source",
                    "configure(release, prefix, options", "environment[\"MAKE\"] = \"make\"",
                    "command(GNU_MAKE_PROGRAM, \"MAKE=make\", \"-j2\"",
                    "command(GNU_MAKE_PROGRAM, \"MAKE=make\", \"install\"", "sealed_gnu_make(make_identity, digest, unit)",
                    "del environment[\"MAKE\"]", "MemorySwapMax=0",
                    "APT::Get::AllowUnauthenticated=false", "Acquire::Check-Valid-Until=true", "--no-download")) {
                assertTrue(body.contains(expected), expected);
            }
            assertIdentity(helper, source);
            var required = manifest.path("requiredPreparationRanges");
            assertTrue(required.isArray() && required.size() >= 10);
            var missing = helper.at("/namedRanges/def gnu_make_origin(");
            int offset = missing.path(0).asInt(), next = missing.path(1).asInt();
            assertTrue(offset > 0 && next > offset && next - offset <= 8000);
            assertTrue(source.substring(offset, next).contains("the frozen GNU Make route differs from the fixed source"));
            for (var range : required) {
                assertEquals(pointer, range.path("sourcePointer").asText());
                if (range.path("section").asText().equals("def gnu_make_origin(")) continue;
                var visible = inline.get(pointer).floorEntry(range.path("offset").asInt());
                assertTrue(visible != null && visible.getValue() >= range.path("next").asInt(), range.path("section").asText());
            }
            String snapshot = manifest.path("snapshotSha256").asText();
            var reader = evidence.reader(8);
            reader.read(response(Optional.empty(), verdict("ruby-early-approval", "APPROVE")));
            assertEquals(Optional.of("REQUIRED_SOURCE_MISSING"), reader.approvalEvidenceBlock());
            var messages = new ArrayList<>(initial.messages());
            messages.set(messages.size() - 1, evidence.messages().getLast());
            int first = wire(fixture.model.requests.getFirst(), maximum, Map.of()).length;
            var reply = mockResponses(read("ruby-fixed-gnu-origin", snapshot, pointer, offset, next - offset));
            var pages = reader.read(reply);
            assertFalse(pages.getFirst().error());
            assertEquals(source.substring(offset, next), pagePayload(pages.getFirst().text()).at("/value/text").asText());
            reader.continueReview(messages, reply, pages);
            byte[] retained = wire(copy(initial, messages, maximum), maximum, Map.of());
            assertTrue(replayRetainedRanges(retained, snapshot, sources).isPresent());
            reader.read(response(Optional.empty(), verdict("ruby-complete-approval", "APPROVE")));
            assertEquals(Optional.empty(), reader.approvalEvidenceBlock());
            assertEquals(0, assisted.hosts.get());
            System.out.printf("R46_RUBY_REVIEW firstEncoded=%d retainedEncoded=%d cap=%d%n", first, retained.length, maximum);
        }
    }

    @Test
    void sourceBuiltPythonReviewIncludesTheActualSignedAptPathAndExactSourceRanges() throws Exception {
        try (var fixture = new MirroredInstallerTestFixture(DownloadPoint.APT_UBUNTU, DownloadPoint.PYTHON_DISTRIBUTION);
             var command = diagnosedPreparation(fixture, "PYTHON", "3.12")) {
            var intent = intent("prepare", fixture, command);
            var facts = new DeploymentFixedCommandProtocol(fixture.context).inspect(intent, command, fixture.layout, fixture.task);
            var evidence = DeploymentCommandSourceEvidence.project(DeploymentAiJson.facts(facts, 1048576), intent, command,
                fixture.layout).orElseThrow();
            var factsFrame = JSON.readTree(evidence.messages().getLast().text());
            assertEquals("https://ftp.gnu.org/gnu/make/",
                factsFrame.at("/mirrorTransport/frozenPolicy/points/GNU_MAKE_SOURCE/0/baseUrl").asText());
            var projected = factsFrame.path(PHASE);
            var frame = projected.path("preparationSelection");
            String body = evidence.messages().get(frame.path("ordinal").intValue()).text()
                .substring(frame.path("headerCharacters").intValue());
            var originals = Map.of(
                "/toolchainReviewEvidence/installedHelperSource", DeploymentManagedHelperBundle.renderScript(fixture.layout),
                "/toolchainReviewEvidence/mirrorBootstrapShell", r24Shell(fixture),
                "/mirrorTransport/runtimeSource", DeploymentMirrorRuntime.runtimeSource());
            for (var range : projected.path("selectedPreparationRanges")) {
                String source = originals.get(range.path("sourcePointer").asText());
                assertNotNull(source);
                int offset = range.path("offset").intValue(), next = range.path("next").intValue();
                int start = range.path("startCharacter").intValue();
                assertEquals(source.substring(offset, next), body.substring(start, start + next - offset));
            }
            assertTrue(body.contains("wtl_apt_source_security()"));
            assertTrue(body.contains("APT::Get::AllowUnauthenticated=false"));
            assertTrue(body.contains("Acquire::Check-Valid-Until=true"));
            assertTrue(body.contains("wtl_apt_install_frozen()"));
            assertTrue(body.contains("apt-helper"));
            assertTrue(body.contains("--no-download"));
            assertTrue(body.contains("def mirror_system_command("));
            assertTrue(body.contains("if ecosystem == 'PYTHON' or layout == 'python':"));
            for (String checked : List.of("RUBY_SCRATCH_BYTES = 1536 * 1024 * 1024",
                "RUBY_SCRATCH_INODES = 96000", "RUBY_MEMORY_BYTES = 3 * 1024 * 1024 * 1024",
                "MemorySwapMax=0", "require_ruby_resources(*resource_limits)",
                "ruby_create_scratch(scratch, unit)", "ruby_remove_scratch(work / 'scratch', unit)",
                "def verify_native_build_chain(release):", "verify_native_build_chain(release)",
                "(\"/usr/bin/ar\", \"crs\", \"probe.a\", \"probe.o\")",
                "make_failure = make_python(release)", "sha256 = download(url, archive, algorithm, expected)",
                "GNU_MAKE_SOURCE_BYTES = 2348200", "dd16fb1d67bfab79a72f5e8390735c49e3e8e70b4945a15ab1f81ddb78658fb3",
                "def bootstrap_gnu_make(", "\"./build.sh\"", "bootstrap_gnu_make(resource_limits[0])",
                "command(GNU_MAKE_PROGRAM, GNU_MAKE_OVERRIDE, \"install\"")) {
                assertTrue(body.contains(checked), checked);
            }
            var helper = projected.path("installedHelperSource");
            String source = originals.get(helper.path("sourcePointer").asText());
            String snapshot = projected.path("snapshotSha256").asText();
            int diagnostics = source.indexOf("PYTHON_MAKE_FAILURES = {");
            int next = source.indexOf("\nPYTHON_SOURCE_WORKER = ", diagnostics);
            assertTrue(diagnostics >= 0 && next > diagnostics && next - diagnostics <= 8000);
            assertFalse(body.contains("PYTHON_MAKE_FAILURES = {"));
            var bootstrap = helper.at("/namedRanges/gnuMakeBootstrap");
            int bootstrapOffset = bootstrap.path(0).asInt();
            assertTrue(source.substring(bootstrapOffset, bootstrap.path(1).asInt()).startsWith("GNU_MAKE_BOOTSTRAP = r'''"));
            for (var section : Map.of("pythonMakeInvocation", "def make_python(release):",
                    "def prepare_gnu_make_source(", "maximum=GNU_MAKE_SOURCE_BYTES",
                    "def gnu_make_origin(", "the frozen GNU Make route differs from the fixed source",
                    "def download(", "official.hexdigest() != expected",
                    "def preparation_lock(", "def preparation_lock(").entrySet()) {
                var range = helper.path("namedRanges").path(section.getKey());
                int offset = range.path(0).asInt(), length = range.path(1).asInt() - offset;
                var call = read("read-pinned-section", snapshot, helper.path("sourcePointer").asText(), offset, length);
                var result = evidence.reader(1).read(mockResponses(call)).getFirst();
                assertFalse(result.error());
                String returned = pagePayload(result.text()).at("/value/text").asText();
                assertEquals(source.substring(offset, offset + length), returned);
                assertTrue(returned.contains(section.getValue()), section.getKey());
            }
            var diagnosticCall = read("read-python-diagnostic-grammar", snapshot, helper.path("sourcePointer").asText(),
                diagnostics, next - diagnostics);
            var page = evidence.reader(1).read(mockResponses(diagnosticCall)).getFirst();
            assertFalse(page.error());
            assertEquals(source.substring(diagnostics, next), pagePayload(page.text()).at("/value/text").asText());
        }
    }

    private static AiResponse mockResponses(AiToolCall... calls) {
        var result = response(Optional.of(new AiContinuation(UUID.randomUUID(), 64)), calls);
        var attempt = new AiGenerationAttempt(UUID.randomUUID(), 1, result.modelId(), "OPENAI_RESPONSES", TaskResult.SUCCEEDED,
            "AI_GENERATION_COMPLETE", "NONE", 0L, result.usage(), 1, 65536);
        return new AiResponse(result.text(), result.modelId(), result.finishReason(), result.usage(), result.toolCalls(),
            result.continuation(), Optional.empty(), Optional.empty(), List.of(attempt));
    }

    private static void replayR24ToolchainReview(String round, String project, R24Review review, CommandRequest request,
                                                  MirroredInstallerTestFixture fixture,
                                                  DeploymentNativeHelperPackageImpactTest.Assisted assisted) throws Exception {
        replayToolchainReview(round, project, review, request, fixture, assisted, false, 0);
    }

    private static void replayToolchainReview(String round, String project, R24Review review, CommandRequest request,
                                              MirroredInstallerTestFixture fixture, DeploymentNativeHelperPackageImpactTest.Assisted assisted,
                                              boolean preparationSelectionPointer, int terminalHeadroom) throws Exception {
        var intent = intent(review.kind(), fixture, request);
        DeploymentCurrentCommandEvidence evidence = !review.kind().equals("prepare")
            ? (action, command, task) -> assisted.scope.effectFacts(action, task) : assisted.scope;
        Callable<OperationResult<ApprovalGrant>> approve = () -> DeploymentFixedCommandApproval.approveWithEvidence(
            assisted.context, assisted.host, intent, request, fixture.task, fixture.model.configurationRevision,
            "AUTOMATIC", false, evidence);
        reply(fixture, verdict("r24-baseline", "DENY"));
        assertReviewDenied(approve);
        var initial = fixture.model.requests.getLast();
        var facts = JSON.readTree(initial.messages().getLast().text());
        String snapshot = facts.at("/" + PHASE + "/snapshotSha256").asText();
        var sources = r24Sources(initial, facts, fixture);
        if (preparationSelectionPointer) {
            var selection = facts.at("/" + PHASE + "/preparationSelection");
            addReplaySource(sources, selection,
                initial.messages().get(1 + selection.path("ordinal").asInt()).text().substring(selection.path("headerCharacters").asInt()));
        }
        assertIdentity(facts.path("exactAction"), intent.exactAction());
        var continuations = new LinkedHashMap<UUID, String>();
        var requests = new ArrayList<List<AiToolCall>>();
        var turns = review.turns();
        int expectedFacts = round.equals("R27") ? r27PreparationFactsSize(initial, facts, review.factsCharacters())
            : review.factsCharacters();
        for (int index = 0; index < turns.size() - 1; index++) {
            var calls = turns.get(index).calls(snapshot, sources, index);
            requests.add(calls);
            int continuationBytes = (!round.equals("R24") && index > 0 || round.equals("R27")) ? Math.max(6000, 320 * calls.size())
                : turns.get(index + 1).cumulativeState() - turns.get(index).cumulativeState();
            replyAtTotalBytes(fixture, continuations, continuationBytes, "OPENAI_RESPONSES", calls.toArray(AiToolCall[]::new));
        }
        reply(fixture, verdict("r24-result", "DENY"));
        int first = fixture.model.requests.size();
        var correctionVerdict = verdict("corrected-historical-denial", "DENY");
        var bounded = wireBudgetContext(assisted.context, continuations, expectedFacts,
            () -> turns.get(fixture.model.requests.size() - first).cap(), requestForModel -> {
                if (requestForModel.requiredToolName().filter("submit_result"::equals).isPresent()) {
                    fixture.model.replies.clear();
                    reply(fixture, correctionVerdict);
                }
            });
        var stopped = assertThrows(ApiException.class, () -> DeploymentFixedCommandApproval.approveWithEvidence(
            bounded, assisted.host, intent, request, fixture.task, fixture.model.configurationRevision, "AUTOMATIC", false, evidence));
        int sent = fixture.model.requests.size() - first;
        boolean corrected = fixture.model.requests.getLast().requiredToolName().filter("submit_result"::equals).isPresent();
        boolean cycle = "EVIDENCE_NO_PROGRESS".equals(stopped.problem().context().get("reviewCode"));
        if (cycle) {
            assertTrue(round.equals("R27") || round.equals("R24") && project.equals("survey-scoring"), project);
            assertEquals("DEPLOYMENT_COMMAND_REVIEW_INVALID", stopped.problem().eventCode());
            assertTrue(sent < turns.size() && sent >= 3, "Only a repeated evidence sequence may end before the historical verdict");
            fixture.model.replies.clear();
        } else {
            assertEquals("DEPLOYMENT_COMMAND_REVIEW_DENIED", stopped.problem().eventCode());
            if (corrected) assertTrue(sent >= 4 && sent <= turns.size());
            else assertEquals(turns.size(), sent);
        }

        long observed = turns.getFirst().observed();
        int browsingRequests = corrected ? sent - 1 : sent;
        for (int index = 0; index < browsingRequests; index++) {
            var turn = turns.get(index);
            var actual = fixture.model.requests.get(first + index);
            assertEquals(turn.observed(), observed);
            int bound = (int) Math.min(1048576, 1500000 - observed - 65536);
            assertEquals(turn.cap(), bound);
            byte[] encoded;
            try {
                encoded = wireWithR24FactsSize(actual, expectedFacts, bound, continuations);
            } catch (ModelRequestBudgetException tooLarge) {
                throw new AssertionError(project + " " + review.kind() + " turn=" + index + " encoded="
                    + tooLarge.encodedBytes() + " bound=" + bound, tooLarge);
            }
            var visible = new LinkedHashMap<String, TreeMap<Integer, Integer>>();
            seedInlineSourceRanges(facts, visible);
            if (index > 0) {
                var latest = requests.get(index - 1);
                var asked = new LinkedHashMap<String, TreeMap<Integer, Integer>>();
                collectRequestedCoverage(asked, latest, sources);
                assertR24VisiblePages(encoded, snapshot, latest, review.kind().equals("prepare") || index > 1, visible, sources);
                assertR24Coverage(asked, visible);
            }
            if (index == browsingRequests - 1 && terminalHeadroom > 0)
                assertTrue(bound - encoded.length >= terminalHeadroom, "Late PREPARE headroom " + (bound - encoded.length));
            observed += turn.usage();
            System.out.printf("%s_REPLAY project=%s action=%s ecosystem=%s turn=%d observed=%d originalBytes=%d encoded=%d bound=%d%n",
                round, project, review.kind(), review.ecosystem(), index, turn.observed(), turn.originalBytes(), encoded.length, bound);
        }
    }

    private static int r27PreparationFactsSize(AiRequest initial, JsonNode projected, int observedOriginalCharacters) throws Exception {
        // Restore only the retired policy projection shape to estimate unlogged application metadata.
        // The real frozen policy is available from the original local snapshot, never read from the state DB.
        var restored = (ObjectNode) projected.deepCopy();
        var mirror = (ObjectNode) restored.path("mirrorTransport");
        var scope = mirror.remove("completePublicPolicy");
        mirror.remove("frozenPolicyScope");
        int removedPolicyCharacters = scope.path("characters").asInt() - mirror.path("frozenPolicy").toString().length();
        var selected = ((ObjectNode) restored.path(PHASE)).withArray("selectedPreparationRanges");
        var added = Set.of("releaseDownloadPoint", "archiveInstall", "archiveReceipt", "archiveVersionProbe",
            "dotnetSystemReuse", "preparationDiagnosticRun", "shellStartup", "startupOwnership",
            "def source_origin(", "def source_url(", "def toolchain_point(", "def json_at(",
            "def extract(archive, destination):", "def discover_system(", "def materialize_system(");
        for (int index = selected.size() - 1; index >= 0; index--) {
            var range = selected.get(index);
            if (added.contains(range.path("section").asText())
                || range.path("sourcePointer").asText().equals("/mirrorTransport/runtimeSource"))
                selected.remove(index);
        }
        int baselineCharacters = restored.toString().length() + removedPolicyCharacters;
        return initial.messages().getLast().text().length() + Math.max(0, observedOriginalCharacters - baselineCharacters);
    }

    private static void assertReviewDenied(Callable<OperationResult<ApprovalGrant>> review) {
        var failure = assertThrows(ApiException.class, review::call);
        assertEquals("DEPLOYMENT_COMMAND_REVIEW_DENIED", failure.problem().eventCode(),
            () -> failure.getCause() == null ? failure.problem().eventCode() : failure.getCause().toString());
    }

    private static gold.debug.windowstolinux.appapi.mod.ModContext wireBudgetContext(
            gold.debug.windowstolinux.appapi.mod.ModContext original, Map<UUID, String> continuations, int factsCharacters,
            java.util.function.IntSupplier nextBound) {
        return wireBudgetContext(original, continuations, factsCharacters, nextBound, request -> { });
    }

    private static gold.debug.windowstolinux.appapi.mod.ModContext wireBudgetContext(
            gold.debug.windowstolinux.appapi.mod.ModContext original, Map<UUID, String> continuations, int factsCharacters,
            java.util.function.IntSupplier nextBound, java.util.function.Consumer<AiRequest> beforeDelivery) {
        var loader = DeploymentToolchainReviewEvidenceTest.class.getClassLoader();
        var aiType = gold.debug.windowstolinux.appapi.ai.AiService.class;
        var service = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[] {aiType}, (proxy, method, args) -> {
            if (method.getName().equals("call")) {
                int limit = nextBound.getAsInt();
                var request = (AiRequest) args[0];
                try {
                    wireWithR24FactsSize(request, factsCharacters, limit, continuations);
                } catch (ModelRequestBudgetException exceeded) {
                    throw new ApiException(exactBudgetProblem(exceeded.encodedBytes(), limit));
                }
                beforeDelivery.accept(request);
            }
            try {
                return method.invoke(original.ai(), args);
            } catch (java.lang.reflect.InvocationTargetException rejected) {
                throw rejected.getCause();
            }
        });
        var contextType = gold.debug.windowstolinux.appapi.mod.ModContext.class;
        return contextType.cast(java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[] {contextType}, (proxy, method, args) -> {
            if (method.getName().equals("ai")) return service;
            try {
                return method.invoke(original, args);
            } catch (java.lang.reflect.InvocationTargetException rejected) {
                throw rejected.getCause();
            }
        }));
    }

    private static byte[] wireWithR24FactsSize(AiRequest actual, int minimumCharacters, int bound,
                                               Map<UUID, String> continuations) throws Exception {
        var messages = new ArrayList<>(actual.messages());
        for (int index = 1; index < messages.size(); index++) {
            var message = messages.get(index);
            if (message.role() != AiRole.USER || !message.text().startsWith("{")) continue;
            var current = (ObjectNode) JSON.readTree(message.text());
            if (!current.has(PHASE) && !current.has("installerReviewEvidence") && !current.has("buildReviewEvidence")) continue;
            // The in-memory workflow uses less metadata than the original app. Preserve the observed payload scale.
            int extra = Math.max(0, minimumCharacters - message.text().length() - 24);
            if (extra > 0) {
                current.put("replayFixturePadding", "x".repeat(extra));
                messages.set(index, new AiMessage(AiRole.USER, current.toString(), List.of()));
            }
            break;
        }
        return wire(copy(actual, messages, bound), bound, continuations);
    }

    private static Map<Integer, Map.Entry<String, String>> r24Sources(AiRequest initial, JsonNode facts,
                                                                        MirroredInstallerTestFixture fixture) {
        var manifest = facts.path(PHASE);
        var result = new LinkedHashMap<Integer, Map.Entry<String, String>>();
        if (manifest.has("installedHelperSource"))
            addReplaySource(result, manifest.path("installedHelperSource"), DeploymentManagedHelperBundle.renderScript(fixture.layout));
        addReplaySource(result, manifest.path("mirrorBootstrapShell"), r24Shell(fixture));
        addReplaySource(result, manifest.path("mirrorPolicySource"), DeploymentMirrorTasks.policy(fixture.task));
        addReplaySource(result, manifest.has("mirrorRuntime") ? manifest.path("mirrorRuntime") : facts.at("/mirrorTransport/runtimeSource"),
            DeploymentMirrorRuntime.runtimeSource());
        for (var selection : manifest.path("frames")) {
            if (selection.equals(manifest.path("preparationSelection"))) continue;
            addReplaySource(result, selection,
                initial.messages().get(1 + selection.path("ordinal").asInt()).text().substring(selection.path("headerCharacters").asInt()));
        }
        return result;
    }

    private static void addReplaySource(Map<Integer, Map.Entry<String, String>> sources, JsonNode descriptor, String value) {
        String pointer = descriptor.path("sourcePointer").asText();
        sources.put(pointer.length(), Map.entry(pointer, value));
    }

    private static String r24Shell(MirroredInstallerTestFixture fixture) {
        String bootstrap = DeploymentMirrorRuntime.bootstrapPrefix(DeploymentMirrorTasks.policy(fixture.task));
        int offset = bootstrap.indexOf("\nwtl_source_providers() {\n") + 1;
        assertTrue(offset > 0);
        return bootstrap.substring(offset);
    }

    private static void seedInlineSourceRanges(JsonNode facts, Map<String, TreeMap<Integer, Integer>> visible) {
        var helper = facts.at("/" + PHASE + "/installedHelperSource");
        for (var range : helper.path("selectedRanges"))
            addR24Range(visible, helper.path("sourcePointer").asText(), range.path("offset").asInt(), range.path("next").asInt());
        for (var frame : facts.at("/" + PHASE + "/frames"))
            addR24Range(visible, frame.path("sourcePointer").asText(), 0, frame.path("characters").asInt());
        for (var range : facts.at("/" + PHASE + "/selectedPreparationRanges"))
            addR24Range(visible, range.path("sourcePointer").asText(), range.path("offset").asInt(), range.path("next").asInt());
        for (var range : facts.at("/" + PHASE + "/selectedRuntimeRanges"))
            addR24Range(visible, range.path("sourcePointer").asText(), range.path("offset").asInt(), range.path("next").asInt());
    }

    private static void collectRequestedCoverage(Map<String, TreeMap<Integer, Integer>> coverage, List<AiToolCall> calls,
                                                  Map<Integer, Map.Entry<String, String>> sources) {
        for (var call : calls) {
            var input = DeploymentAiJson.arguments(call);
            String pointer = input.path("pointer").asText();
            if (!sources.containsKey(pointer.length())) continue;
            String source = sources.get(pointer.length()).getValue();
            int offset = input.path("offset").asInt();
            int limit = input.path("limit").asInt();
            if (offset > source.length() || limit > 8000) continue;
            int next = (int) DeploymentEvidencePages.page(source, offset, limit).get("next");
            addR24Range(coverage, pointer, offset, next);
        }
    }

    private static void assertR24VisiblePages(byte[] encoded, String snapshot, List<AiToolCall> latest,
                                               boolean rebased,
                                               Map<String, TreeMap<Integer, Integer>> visible,
                                               Map<Integer, Map.Entry<String, String>> sources) throws Exception {
        var calls = new ArrayList<String>();
        var outputs = new ArrayList<String>();
        int opaque = 0;
        int frames = 0;
        for (var item : JSON.readTree(encoded).path("input")) {
            switch (item.path("type").asText()) {
                case "reasoning" -> opaque++;
                case "function_call" -> calls.add(item.path("call_id").asText());
                case "function_call_output" -> {
                    outputs.add(item.path("call_id").asText());
                    String output = item.path("output").asText();
                    if (!output.startsWith(RAW_PAGE)) {
                        assertTrue(output.startsWith("EVIDENCE_REQUEST_REJECTED:"));
                        continue;
                    }
                    var page = pagePayload(output);
                    assertEquals(snapshot, page.path("snapshotSha256").asText());
                    String pointer = page.path("pointer").asText();
                    var value = page.path("value");
                    Iterable<? extends JsonNode> ranges = value.has("newRanges") ? value.path("newRanges") : List.of(value);
                    for (var range : ranges)
                        assertR24SourceRange(visible, sources, pointer, range, range.path("text").asText());
                }
                case "message" -> {
                    String frame = item.at("/content/0/text").asText();
                    if (frame.startsWith("WTL-COMMAND-RETAINED-SOURCE-1\n")) {
                        assertEquals("user", item.path("role").asText());
                        assertR24RetainedSource(frame, snapshot, latest, visible, sources);
                        frames++;
                    }
                }
                default -> fail("Unexpected Responses item " + item.path("type"));
            }
        }
        assertEquals(rebased ? 0 : 1, opaque);
        assertEquals(rebased ? 1 : 0, frames);
        assertEquals(rebased ? List.of() : latest.stream().map(AiToolCall::id).toList(), calls);
        assertEquals(calls, outputs);
    }

    private static void assertR24RetainedSource(String frame, String snapshot, List<AiToolCall> latest,
                                                  Map<String, TreeMap<Integer, Integer>> visible,
                                                  Map<Integer, Map.Entry<String, String>> sources) throws Exception {
        int first = frame.indexOf('\n') + 1;
        int separator = frame.indexOf('\n', first);
        var header = JSON.readTree(frame.substring(first, separator));
        assertEquals(snapshot, header.path("snapshotSha256").asText());
        assertEquals("UNTRUSTED_DATA", header.path("trust").asText());
        var readIds = new ArrayList<String>();
        for (var receipt : header.path("latestReads")) {
            readIds.add(receipt.path("callId").asText());
            var requested = DeploymentAiJson.arguments(latest.get(readIds.size() - 1));
            if (receipt.path("rejected").asBoolean()) {
                assertFalse(receipt.has("pointer"), "Rejected read metadata must not echo untrusted arguments");
            } else {
                assertEquals(requested.path("pointer"), receipt.path("pointer"));
                assertEquals(requested.path("offset"), receipt.path("offset"));
                assertTrue(receipt.path("next").asInt() <= requested.path("offset").asInt() + requested.path("limit").asInt());
            }
        }
        assertEquals(latest.stream().map(AiToolCall::id).toList(), readIds);
        int start = separator + 1;
        for (var range : header.path("ranges")) {
            int next = start + range.path("next").asInt() - range.path("offset").asInt();
            assertR24SourceRange(visible, sources, range.path("pointer").asText(), range, frame.substring(start, next));
            start = next;
        }
        assertEquals(frame.length(), start);
    }

    private static void assertR24SourceRange(Map<String, TreeMap<Integer, Integer>> visible,
                                               Map<Integer, Map.Entry<String, String>> sources, String pointer,
                                               JsonNode range, String text) {
        int offset = range.path("offset").asInt(), next = range.path("next").asInt();
        var source = sources.values().stream().filter(entry -> entry.getKey().equals(pointer)).findFirst()
            .orElseThrow(() -> new AssertionError("Unexpected retained source " + pointer));
        assertEquals(source.getValue().substring(offset, next), text);
        addR24Range(visible, pointer, offset, next);
    }

    private static void addR24Range(Map<String, TreeMap<Integer, Integer>> map, String pointer, int offset, int next) {
        if (next > offset) DeploymentEvidencePages.remember(map.computeIfAbsent(pointer, ignored -> new TreeMap<>()), offset, next);
    }

    private static void assertR24Coverage(Map<String, TreeMap<Integer, Integer>> asked, Map<String, TreeMap<Integer, Integer>> visible) {
        asked.forEach((pointer, ranges) -> ranges.forEach((offset, next) -> {
            assertTrue(visible.containsKey(pointer), pointer);
            var range = visible.get(pointer).floorEntry(offset);
            assertNotNull(range, pointer + " offset=" + offset);
            assertTrue(range.getValue() >= next, pointer + " required=" + offset + ".." + next);
        }));
    }

    private record R24Review(String kind, String ecosystem, String branch, int factsCharacters, List<R24Turn> turns) {
        static List<R24Review> forProject(String project) {
            // Safe projection of the original R24 logs. Source pointers resolved by their logged lengths, no command or user data.
            return switch (project) {
                case "asset-lending" -> List.of(
                    new R24Review("prepare", "DOTNET", "10", 31741, List.of(
                        new R24Turn(120498, 1048576, 0, 16715, 70414, "46/571019/8000 46/215141/8000 46/241045/8000 46/204731/8000 46/160851/8000 46/155035/8000 46/150706/8000 46/156519/8000"),
                        new R24Turn(137213, 1048576, 10548, 29467, 129099, "46/143713/8000 46/182608/8000 46/186126/8000 46/194763/8000 30/7163/8000 30/8737/8000 30/16170/8000 30/193282/8000"),
                        new R24Turn(166680, 1048576, 21859, 42550, 191858, "46/211000/8000 46/196000/8000 45/13053/8000 30/0/8000 30/24170/8000 30/40000/8000 30/71600/8000"),
                        new R24Turn(209230, 1048576, 33074, 54078, 250270, "30/32170/8000 30/48000/8000 30/56000/8000 45/0/8000 45/26000/8000 43/0/8000 46/134000/8000 46/139000/8000"),
                        new R24Turn(263308, 1048576, 44343, 71378, 325110, "45/8000/8000 45/20000/8000 45/33000/8000 46/19000/8000 46/244000/8000 46/214000/8000 46/191000/8000 46/178000/8000"),
                        new R24Turn(334686, 1048576, 61534, 82169, 374524, "46/0/8000 46/8000/8000 46/16000/8000 46/24000/8000 46/166000/8000 46/170000/8000 46/174000/8000 46/204000/8000"),
                        new R24Turn(416855, 1017609, 85439, 101195, 437846, ""))),
                    new R24Review("bind", "DOTNET", "10", 30844, List.of(
                        new R24Turn(518050, 916414, 0, 18047, 74958, "41/0/8000 43/0/8000 45/0/8000 45/8000/8000 45/16000/8000 45/24000/8000 45/32000/4000"),
                        new R24Turn(536097, 898367, 11344, 33552, 134596, "46/168979/6000 46/175000/6000 46/177500/6000 46/187700/5000 46/203800/7000 46/237000/5000 46/240000/2500"),
                        new R24Turn(569649, 864815, 31696, 45132, 190120, "46/143847/2500 46/237523/2000 46/238000/3000 46/240000/1800 46/204731/1600 46/175550/3000"),
                        new R24Turn(614781, 819683, 80381, 48255, 244234, ""))),
                    new R24Review("prepare", "NODE", "24", 33245, List.of(
                        new R24Turn(728911, 705553, 0, 16947, 72087, "46/570000/8000 46/203800/8000 46/214500/8000 46/240000/8000 46/143000/8000 43/0/8000 30/0/8000 45/12000/8000"),
                        new R24Turn(745858, 688606, 7233, 33717, 143493, "46/211000/8000 46/149500/8000 46/154000/8000 46/160000/8000 46/182000/8000 46/193500/8000 45/0/8000 30/7000/8000"),
                        new R24Turn(779575, 654889, 16689, 48926, 208782, "46/168000/8000 46/174000/8000 46/194000/8000 46/237000/8000 46/202000/2500 45/12000/8000 45/20000/8000 30/151000/8000"),
                        new R24Turn(828501, 605963, 34124, 60055, 266495, "46/184000/8000 46/188000/8000 30/45000/8000 30/160000/8000 30/192000/4150 45/8000/5000 45/13000/3000 45/33000/1600"),
                        new R24Turn(888556, 545908, 43912, 69147, 310149, "30/15000/8000 30/20000/8000 30/13000/4000 46/188000/5000"),
                        new R24Turn(957703, 476761, 60468, 72361, 341802, "30/28000/8000 30/37000/8000 30/13000/2500 46/205000/4000"),
                        new R24Turn(1030064, 404400, 64165, 76772, 363605, "45/28000/5200"),
                        new R24Turn(1106836, 327628, 77877, 0, 383150, ""))));
                case "csv-inspector" -> List.of(
                    new R24Review("prepare", "PYTHON", "3.12", 32394, List.of(
                        new R24Turn(118299, 1048576, 0, 17163, 71358, "46/143713/8000 46/215141/8000 46/571019/6000 46/204731/8000 46/241045/8000 46/179988/8000 46/150706/8000 46/182608/8000"),
                        new R24Turn(135462, 1048576, 17583, 31551, 144974, "46/190608/8000 46/211000/8000 46/223141/8000"),
                        new R24Turn(167013, 1048576, 26062, 36294, 170921, "46/160851/8000 46/198608/8000 46/211000/8000 45/13590/6000 45/32397/6000 30/16170/8000 30/144973/7000 30/158384/6000"),
                        new R24Turn(203307, 1048576, 34061, 50373, 230249, "46/147000/8000 46/237524/8000 46/155035/6000 30/3984/8000 30/7163/6000 30/9615/7000 30/113677/6000 43/0/8000"),
                        new R24Turn(253680, 1048576, 59281, 60585, 293014, "30/24170/8000 30/20095/7000 45/19590/8000 45/27590/8000 46/143000/7000"),
                        new R24Turn(314265, 1048576, 73357, 68897, 331939, "46/132000/7000 46/138000/7000 45/0/7000 43/8000/1000"),
                        new R24Turn(383162, 1048576, 103275, 75286, 382599, "46/170000/8000 46/188000/8000 46/202000/8000 46/215141/7000"),
                        new R24Turn(458448, 976016, 117770, 78053, 407195, "46/164000/8000"),
                        new R24Turn(536501, 897963, 123529, 79906, 414646, "30/32170/8000 30/40170/8000 46/178000/8000 46/208000/5000"),
                        new R24Turn(616407, 818057, 144021, 86768, 455343, "45/7000/6590"),
                        new R24Turn(703175, 731289, 188580, 95682, 507402, ""))),
                    new R24Review("bind", "PYTHON", "3.12", 31425, List.of(
                        new R24Turn(798857, 635607, 0, 18221, 75623, "45/0/8000 45/8000/8000 45/16000/8000 45/24000/8000 45/32000/4000"),
                        new R24Turn(817078, 617386, 15783, 31726, 130757, "46/145295/4000 46/168979/4000 46/175550/4000 46/177592/4000 46/187867/4000 46/204731/4000 46/237523/4000 46/175000/3000"),
                        new R24Turn(848804, 585660, 40121, 39870, 185298, "46/164000/8000 46/208731/4000 46/238441/5000 46/240705/4000"),
                        new R24Turn(888674, 545790, 48645, 44812, 208293, "46/143847/3000 46/198800/6000 46/213000/8000 46/221000/8000 46/229500/8000"),
                        new R24Turn(933486, 500978, 64315, 55386, 257870, ""))),
                    new R24Review("prepare", "PHP", "8.3", 33911, List.of(
                        new R24Turn(1052793, 381671, 0, 17045, 72863, "46/571019/8000 46/143713/8000 46/241045/8000 46/215141/8000 46/204731/8000 46/150706/8000 46/155035/8000 46/179988/8000"),
                        new R24Turn(1069838, 364626, 7284, 32261, 137918, "46/211000/8000 46/237524/8000 46/167000/8000 46/183000/8000 46/194763/8000 43/0/8000 30/144973/8000 30/193282/8000"),
                        new R24Turn(1102099, 332365, 18004, 43991, 191406, "46/187988/8000 46/237524/8000 46/212731/8000 45/13590/8000 45/32397/8000 30/100000/8000 30/110000/8000 43/8000/2000"),
                        new R24Turn(1146090, 288374, 33480, 52135, 235734, "30/3984/8000 30/7163/8000 30/16170/8000 30/20095/8000 30/144973/8000 45/14702/8000 46/237524/8000 46/232000/8000"),
                        new R24Turn(1198225, 236239, 43128, 0, 283139, ""))));
                case "survey-scoring" -> List.of(
                    new R24Review("prepare", "RUBY", "3.3", 38301, List.of(
                        new R24Turn(132759, 1048576, 0, 18103, 77910, "46/204000/8000 46/214500/8000 46/241000/8000 46/179700/8000 46/182500/8000 46/194600/8000 45/16000/8000 45/34900/8000"),
                        new R24Turn(150862, 1048576, 6512, 33978, 143718, "46/150400/8000 46/158000/8000 46/186000/8000 46/570700/1283 43/0/8000 43/8000/4789 45/0/8000 45/7300/8000"),
                        new R24Turn(184840, 1048576, 17250, 48662, 209224, "46/166000/8000 46/190000/8000 46/211000/8000 46/143500/8000 46/569000/3000 30/7000/8000 30/15800/8000 30/144500/8000"),
                        new R24Turn(233502, 1048576, 28663, 61903, 269455, "46/198000/8000 46/200000/8000 46/137000/7000 45/23000/8000 30/0/7000 30/76000/8000 30/104000/8000 30/110000/7000"),
                        new R24Turn(295405, 1048576, 52977, 75580, 342128, "45/15300/8000 45/30000/7737 46/100000/8000 30/23800/8000 30/30000/8000 30/6800/1200 30/193000/3148"),
                        new R24Turn(370985, 1048576, 79096, 85319, 402562, "46/176000/5000 46/237000/8000 46/565000/4500 46/212000/3000"),
                        new R24Turn(456304, 978160, 110309, 95538, 447900, ""))),
                    new R24Review("bind", "RUBY", "3.3", 37308, List.of(
                        new R24Turn(551842, 882622, 0, 19782, 82104, "46/168979/6000 46/178777/5200 46/187867/5000 46/204731/5000 45/0/4500 45/15500/5000 30/0/1000 43/0/1000"),
                        new R24Turn(571624, 862840, 11819, 28172, 124423, "46/175550/3000 46/177592/2600 46/237523/4000 46/240705/1200 45/9500/5000 45/11000/3000 45/16190/1400 30/193282/3000"),
                        new R24Turn(599796, 834668, 17716, 35219, 150659, "46/145295/3000 46/165000/5000 46/237000/1300 45/32737/5000 45/17700/2200"),
                        new R24Turn(635015, 799449, 41933, 39992, 190366, "46/158000/6500"),
                        new R24Turn(675007, 759457, 47588, 41963, 203130, "46/149000/5000"),
                        new R24Turn(716970, 717494, 50299, 43527, 211486, "46/172500/3000"),
                        new R24Turn(760497, 673967, 53650, 43992, 215882, "46/180192/6000"),
                        new R24Turn(804489, 629975, 56849, 45734, 225677, "46/182000/4000"),
                        new R24Turn(850223, 584241, 59260, 46595, 228562, "46/166000/4000"),
                        new R24Turn(896818, 537646, 71074, 47087, 240850, "46/164500/5000"),
                        new R24Turn(943905, 490559, 76257, 48621, 247051, "45/4500/5000 45/14000/3000 45/20500/7500 45/28000/5000"),
                        new R24Turn(992526, 441938, 92131, 55537, 284028, ""))),
                    new R24Review("prepare", "NODE", "24", 39985, List.of(
                        new R24Turn(1134448, 300016, 0, 18615, 79489, "46/570800/3500 46/143500/8000 46/241000/8000 46/204500/6000 46/150500/6000 46/160600/7000 46/194500/5500 46/179700/5000"),
                        new R24Turn(1153063, 281401, 5448, 32617, 135914, "46/214800/7500 46/184500/7000 46/186000/8500 30/3984/7000 30/7163/7000 30/16170/7000 30/193282/6000 45/16190/6500"),
                        new R24Turn(1185680, 248784, 14177, 44706, 190044, "46/191500/8000 46/211000/4500 46/237300/5500 46/171000/6500 46/155000/7000 30/23170/7500 30/32000/7000 30/14163/2007"),
                        new R24Turn(1230386, 204078, 36632, 0, 254725, ""))));
                default -> throw new IllegalArgumentException(project);
            };
        }
    }

    private record R24Turn(int observed, int cap, int cumulativeState, int usage, int originalBytes, String coordinates) {
        List<AiToolCall> calls(String snapshot, Map<Integer, Map.Entry<String, String>> sources, int round) throws Exception {
            var calls = new ArrayList<AiToolCall>();
            for (String coordinate : coordinates.split(" ")) {
                var values = coordinate.split("/");
                int width = Integer.parseInt(values[0]);
                String pointer = sources.containsKey(width) ? sources.get(width).getKey() : "/".repeat(Math.max(1, width));
                calls.add(read("r24-" + round + "-" + calls.size(), snapshot, pointer, Integer.parseInt(values[1]), Integer.parseInt(values[2])));
            }
            return List.copyOf(calls);
        }
    }

    private static CommandRequest diagnosedPreparation(MirroredInstallerTestFixture fixture, String ecosystem, String branch) {
        return diagnosedPreparation(fixture, ecosystem, branch, "-");
    }

    private static CommandRequest diagnosedPreparation(MirroredInstallerTestFixture fixture, String ecosystem, String branch,
                                                        String javaPrerequisite) {
        long seconds = 135;
        var input = new DeploymentToolchainDiagnostics.Request(fixture.operation, fixture.task.id().toString(), fixture.server.id(),
            "a".repeat(64), "a".repeat(64), fixture.layout, List.of("prepare", ecosystem, branch, "-", "120", javaPrerequisite));
        String invocation = DeploymentToolchainDiagnostics.command("prepare-diagnosed", input);
        String guarded = DeploymentManagedHelperBundle.verifiedCommand(invocation, fixture.layout);
        String wrapped = DeploymentMirrorTasks.preparationCommand(guarded, fixture.task, seconds);
        return new CommandRequest(wrapped, input.bytes(), Duration.ofSeconds(seconds), 65536);
    }

    private static CommandRequest rustPreparation(MirroredInstallerTestFixture fixture, boolean diagnosed) {
        if (diagnosed) return diagnosedPreparation(fixture, "RUST", "1.95");
        String invocation = "'" + fixture.layout.helperPath() + "' prepare-toolchains 'prepare' 'RUST' '1.95' '-' '120' '-'";
        String guarded = DeploymentManagedHelperBundle.verifiedCommand(invocation, fixture.layout);
        String wrapped = DeploymentMirrorTasks.preparationCommand(guarded, fixture.task, 135);
        return new CommandRequest(wrapped, new byte[0], Duration.ofSeconds(135), 65536);
    }

    private static CommandRequest request(String kind, MirroredInstallerTestFixture fixture) {
        if (kind.equals("dependencies")) return dependencyRequest(fixture, DeploymentToolchainDependencies.PROFILE);
        long seconds = kind.equals("prepare") ? 135 : 180;
        String arguments = kind.equals("prepare") ? "'prepare' 'NODE' '22' '-' '120' '-'" : "'bind' '" + binding(fixture) + "'";
        String command = DeploymentManagedHelperBundle.verifiedCommand("'" + fixture.layout.helperPath() + "' prepare-toolchains " + arguments, fixture.layout);
        String wrapped = DeploymentMirrorTasks.preparationCommand(command, fixture.task, seconds);
        return new CommandRequest(wrapped, new byte[0], Duration.ofSeconds(seconds), 65536);
    }

    private static CommandRequest dependencyRequest(MirroredInstallerTestFixture fixture, String profile) {
        long seconds = 120;
        String command = DeploymentToolchainDependencies.command(DeploymentToolchainDependencies.frame(profile, "b".repeat(32),
            seconds, fixture.server, fixture.task.id().toString()));
        String wrapped = DeploymentMirrorTasks.preparationCommand(command, fixture.task, seconds);
        byte[] input = DeploymentToolchainDependencies.dependencies(fixture.layout, profile).getBytes(StandardCharsets.UTF_8);
        return new CommandRequest(wrapped, input, Duration.ofSeconds(seconds), 65536);
    }

    private static ActionIntent intent(String kind, MirroredInstallerTestFixture fixture, CommandRequest request) {
        var targets = kind.equals("dependencies")
            ? DeploymentToolchainDependencies.dependencyTargets(fixture.server, "NATIVE_BUILD", "b".repeat(32), fixture.layout)
            : Set.of(new ActionTarget(fixture.server.id(), "deployment-build", fixture.layout.toolchains()));
        return RemoteActions.command(fixture.server, "a".repeat(64), request).withSemantics(new ActionSemantics("deployment.toolchain." + kind + ".v1", targets));
    }

    private static String binding(MirroredInstallerTestFixture fixture) {
        String payload = "WTL-TOOLS-1\tcurrent\nJAVA\tLANGUAGE_TARGET\tMjE=\tcG9tLnhtbA==\tSERIES\t21\t21.0.12.1+1\t"
            + fixture.layout.toolchains() + "/versions/java-" + "c".repeat(64) + "\tSYSTEM\tc3lzdGVtOi91c3IvYmluL2phdmE=\t" + "d".repeat(64) + "\n";
        return Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    private static String collect(DeploymentCommandSourceEvidence evidence, JsonNode descriptor, String phase) throws Exception {
        String snapshot = JSON.readTree(evidence.messages().getLast().text()).at("/" + phase + "/snapshotSha256").asText();
        int total = descriptor.path("characters").intValue();
        var body = new StringBuilder();
        var manifest = JSON.readTree(evidence.messages().getLast().text()).path(phase);
        var inline = new TreeMap<Integer, String>();
        boolean build = phase.equals("buildReviewEvidence");
        boolean installer = phase.equals("installerReviewEvidence");
        boolean apt = manifest.has("dependencyAptSelection");
        boolean dependency = manifest.has("dependencyRuntimeSelection");
        String selectionName = installer ? "installerSelection" : build ? "runtimeSelection" : apt ? "dependencyAptSelection"
            : dependency ? "dependencyRuntimeSelection" : "preparationSelection";
        String rangesName = installer ? "selectedInstallerRanges" : build || dependency ? "selectedRuntimeRanges"
            : apt ? "selectedAptRanges" : "selectedPreparationRanges";
        var selection = manifest.path(manifest.has("nodePreparationSelection") ? "nodePreparationSelection" : selectionName);
        if (selection.has("ordinal")) {
            String text = evidence.messages().get(selection.path("ordinal").asInt()).text()
                .substring(selection.path("headerCharacters").asInt());
            for (var range : manifest.path(rangesName))
                if (range.path("sourcePointer").equals(descriptor.path("sourcePointer")))
                    inline.merge(range.path("offset").asInt(), text.substring(range.path("startCharacter").asInt(),
                        range.path("startCharacter").asInt() + range.path("next").asInt() - range.path("offset").asInt()),
                        (left, right) -> left.length() >= right.length() ? left : right);
        }
        while (body.length() < total) {
            var call = read("restore-" + body.length(), snapshot, descriptor.path("sourcePointer").asText(), body.length(), 8000);
            var reply = evidence.reader(1).read(response(Optional.empty(), call)).getFirst();
            assertFalse(reply.error());
            var page = pagePayload(reply.text()).path("value");
            assertEquals(body.length(), page.path("offset").intValue());
            assertEquals(descriptor.path("sha256").asText(), page.path("sha256").asText());
            if (page.has("text")) body.append(page.path("text").asText());
            else {
                var supplied = new TreeMap<>(inline);
                for (var range : page.path("newRanges"))
                    supplied.put(range.path("offset").asInt(), range.path("text").asText());
                int end = page.path("next").asInt();
                while (body.length() < end) {
                    var part = supplied.floorEntry(body.length());
                    while (part != null && part.getKey() + part.getValue().length() <= body.length())
                        part = supplied.lowerEntry(part.getKey());
                    assertNotNull(part);
                    int first = body.length() - part.getKey();
                    int next = Math.min(part.getValue().length(), end - part.getKey());
                    assertTrue(next > first, "Every omitted page byte must exist in its exact inline selection");
                    body.append(part.getValue(), first, next);
                }
            }
            assertEquals(body.length(), page.path("next").intValue());
        }
        return body.toString();
    }

    private static JsonNode restore(JsonNode wire, Map<String, String> pagedSources) throws Exception {
        var messages = java.util.stream.StreamSupport.stream(wire.path("input").spliterator(), false)
            .filter(message -> !nodeInitialSupplement(message.at("/content/0/text").asText())).toList();
        var projection = (ObjectNode) JSON.readTree(messages.get(messages.size() - 1).at("/content/0/text").asText());
        var manifest = projection.path(PHASE);
        var sources = new LinkedHashMap<>(pagedSources);
        int firstFrame = messages.size() - manifest.path("frames").size() - 1;
        for (var frame : manifest.path("frames")) {
            var message = messages.get(firstFrame + frame.path("ordinal").intValue());
            assertEquals("user", message.path("role").asText());
            String source = message.at("/content/0/text").asText().substring(frame.path("headerCharacters").intValue());
            assertIdentity(frame, source);
            assertNull(sources.put(frame.path("sourcePointer").asText(), source));
        }
        String exact = restoreValue(projection.path("exactAction"), sources);
        projection.put("exactAction", exact);
        if (projection.has("completeExecutableInput")) {
            projection.put("completeExecutableInput", restoreValue(projection.path("completeExecutableInput"), sources));
            var script = projection.path("commandScript");
            String body = exact.substring(script.path("startCharacter").intValue());
            assertIdentity(script, body);
            projection.put("commandScript", body);
            var transaction = (ObjectNode) projection.at("/currentCommandEvidence/currentNativeBuildPrerequisites");
            assertEquals("/toolchainDependencies", transaction.at("/fixedTransaction/sourcePointer").asText());
            transaction.set("fixedTransaction", projection.path("toolchainDependencies"));
        } else {
            var mirror = (ObjectNode) projection.path("mirrorTransport");
            mirror.put("runtimeSource", sources.get("/mirrorTransport/runtimeSource"));
            if (mirror.has("completePublicPolicy")) {
                var original = mirror.remove("completePublicPolicy");
                mirror.remove("frozenPolicyScope");
                mirror.set("frozenPolicy", JSON.readTree(sources.get(original.path("sourcePointer").asText())));
                mirror.put("meaning", "Complete decoded shipped runtime is supplied inline or at runtimeSourcePointer in this same request, alongside frozen public routing data. Inspect that complete source, not only its SHA. Secret values remain opaque. These bytes grant no permission.");
            }
        }
        projection.remove(PHASE);
        return projection;
    }

    private static String restoreValue(JsonNode value, Map<String, String> sources) throws Exception {
        if (value.isTextual()) return value.textValue();
        var result = new StringBuilder();
        for (var part : value.path("segments")) {
            String source = sources.get(part.path("sourcePointer").asText());
            result.append(switch (part.path("kind").asText()) {
                case "LITERAL" -> part.path("text").asText();
                case "SOURCE_TEXT" -> source;
                case "SHELL_SINGLE_QUOTE" -> "'" + source.replace("'", "'\"'\"'") + "'";
                case "JSON_STRING" -> JSON.writeValueAsString(source);
                default -> throw new AssertionError(part);
            });
        }
        assertIdentity(value, result.toString());
        return result.toString();
    }

    private static void assertIdentity(JsonNode node, String original) throws Exception {
        assertEquals(original.length(), node.path("characters").intValue());
        assertEquals(original.getBytes(StandardCharsets.UTF_8).length, node.path("utf8Bytes").intValue());
        assertEquals(MirroredInstallerTestFixture.sha(original), node.path("sha256").asText());
    }

    private static void assertPage(AiToolResult page, String snapshot, String original) throws Exception {
        var payload = pagePayload(page.text());
        assertEquals(snapshot, payload.path("snapshotSha256").asText());
        assertEquals(original.substring(0, 120), payload.at("/value/text").asText());
        assertEquals(120, payload.at("/value/next").intValue());
        assertTrue(payload.at("/value/hasMore").asBoolean());
        assertEquals(MirroredInstallerTestFixture.sha(original), payload.at("/value/sha256").asText());
    }

    private static JsonNode pagePayload(String text) throws Exception {
        if (!text.startsWith(RAW_PAGE)) return JSON.readTree(text);
        int bodyStart = text.indexOf('\n', RAW_PAGE.length()) + 1;
        assertTrue(bodyStart > RAW_PAGE.length());
        var payload = JSON.readTree(text.substring(RAW_PAGE.length(), bodyStart - 1));
        var value = (ObjectNode) payload.path("value");
        Iterable<? extends JsonNode> ranges = value.has("newRanges") ? value.path("newRanges") : List.of(value);
        int position = bodyStart;
        for (var range : ranges) {
            int next = position + range.path("next").intValue() - range.path("offset").intValue();
            ((ObjectNode) range).put("text", text.substring(position, next));
            position = next;
        }
        assertEquals(text.length(), position);
        return payload;
    }

    private static void assertRange(String source, JsonNode range) {
        assertEquals(source.substring(range.path("offset").intValue(), range.path("next").intValue()), range.path("text").asText());
    }

    private static void assertPageRanges(String source, JsonNode value) {
        Iterable<? extends JsonNode> ranges = value.has("newRanges") ? value.path("newRanges") : List.of(value);
        for (var range : ranges) assertRange(source, range);
    }

    private static AiToolCall verdict(String id, String decision) throws Exception {
        return new AiToolCall(id, "submit_result", JSON.writeValueAsString(Map.of("decision", decision,
            "requiresUserConfirmation", false, "reason", "Controlled independent review")));
    }

    private static AiToolCall read(String id, String snapshot, String pointer, int offset) throws Exception {
        return read(id, snapshot, pointer, offset, 120);
    }

    private static AiToolCall read(String id, String snapshot, String pointer, int offset, int limit) throws Exception {
        return new AiToolCall(id, "evidence", JSON.writeValueAsString(Map.of("snapshotSha256", snapshot, "pointer", pointer, "offset", offset, "limit", limit)));
    }

    private static AiResponse response(Optional<AiContinuation> continuation, AiToolCall... calls) {
        var usage = new AiUsage(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        return new AiResponse("", "fixture-model", "tool_calls", usage, List.of(calls), continuation);
    }

    private static void reply(MirroredInstallerTestFixture fixture, AiToolCall... calls) {
        fixture.model.replies.add(new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(response(Optional.empty(), calls)), Optional.empty()));
    }

    private static void reply(MirroredInstallerTestFixture fixture, Map<UUID, String> continuations, int encryptedBytes,
                              AiToolCall... calls) throws Exception {
        retainReply(fixture, continuations, nativeOutput("a".repeat(encryptedBytes), calls), calls);
    }

    private static void replyAtTotalBytes(MirroredInstallerTestFixture fixture, Map<UUID, String> continuations, int totalBytes,
                                          AiToolCall... calls) throws Exception {
        replyAtTotalBytes(fixture, continuations, totalBytes, null, calls);
    }

    private static void replyAtTotalBytes(MirroredInstallerTestFixture fixture, Map<UUID, String> continuations, int totalBytes,
                                          String protocol, AiToolCall... calls) throws Exception {
        var output = nativeOutput("", calls);
        int framing = JSON.writeValueAsBytes(output).length;
        ((ObjectNode) output.get(0)).put("encrypted_content", "a".repeat(totalBytes - framing));
        assertEquals(totalBytes, JSON.writeValueAsBytes(output).length);
        retainReply(fixture, continuations, output, protocol, calls);
    }

    private static com.fasterxml.jackson.databind.node.ArrayNode nativeOutput(String encrypted, AiToolCall... calls) {
        var nativeOutput = JSON.createArrayNode();
        nativeOutput.addObject().put("type", "reasoning").put("encrypted_content", encrypted);
        for (var call : calls) nativeOutput.addObject().put("type", "function_call").put("call_id", call.id())
            .put("name", call.name()).put("arguments", call.arguments());
        return nativeOutput;
    }

    private static void retainReply(MirroredInstallerTestFixture fixture, Map<UUID, String> continuations, JsonNode nativeOutput,
                                    AiToolCall... calls) throws Exception {
        retainReply(fixture, continuations, nativeOutput, null, calls);
    }

    private static void retainReply(MirroredInstallerTestFixture fixture, Map<UUID, String> continuations, JsonNode nativeOutput,
                                    String protocol, AiToolCall... calls) throws Exception {
        String value = JSON.writeValueAsString(nativeOutput);
        var continuation = new AiContinuation(UUID.randomUUID(), value.getBytes(StandardCharsets.UTF_8).length);
        continuations.put(continuation.id(), value);
        var response = response(Optional.of(continuation), calls);
        if (protocol != null) {
            var attempt = new AiGenerationAttempt(UUID.randomUUID(), 1, response.modelId(), protocol, TaskResult.SUCCEEDED,
                "AI_GENERATION_COMPLETE", "NONE", 0L, response.usage(), 1, 65536);
            response = new AiResponse(response.text(), response.modelId(), response.finishReason(), response.usage(),
                response.toolCalls(), response.continuation(), Optional.empty(), Optional.empty(), List.of(attempt));
        }
        fixture.model.replies.add(new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(response), Optional.empty()));
    }

    private static byte[] wire(AiRequest original, int maximum, Map<UUID, String> continuations) {
        return ModelProtocolCodec.forProtocol(ModelProtocol.OPENAI_RESPONSES).encode("gpt-6-luna",
            copy(original, original.messages(), maximum), 65536, ModelReasoningLevel.HIGH, continuations);
    }

    private static AiRequest copy(AiRequest original, List<AiMessage> messages, int maximum) {
        return new AiRequest(original.purpose(), messages, original.maxOutputTokens(), original.timeout(), original.jsonObjectResponse(),
            original.tools(), maximum, original.requiredToolName(), original.generationRecoveryAttempts(), Optional.of("gpt-6-luna"), Optional.of(AiReasoningMode.HIGH));
    }
}
