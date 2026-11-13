package gold.debug.windowstolinux.mod.deployment.ai.review;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
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
import gold.debug.windowstolinux.appapi.task.action.*;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson;
import gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentToolchainDiagnostics;
import gold.debug.windowstolinux.mod.deployment.linux.command.contract.DeploymentCommandReview;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentInstallerEvidence;
import gold.debug.windowstolinux.mod.deployment.linux.mirror.DeploymentMirrorRuntime;
import gold.debug.windowstolinux.mod.deployment.service.deploy.toolchain.DeploymentToolchainDependencies;
import gold.debug.windowstolinux.mod.deployment.service.mirror.DeploymentMirrorTasks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeploymentToolchainReviewEvidenceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int R20_SMALLEST_REMAINING_BYTES = 365119;
    private static final String PHASE = "toolchainReviewEvidence";
    private static final String RAW_PAGE = "WTL-COMMAND-EVIDENCE-PAGE-1\n";

    @Test
    void nativeInstallerPagesCompleteSourceWhileKeepingActualWorkflowVisible() throws Exception {
        try (var fixture = new MirroredInstallerTestFixture(); var assisted = new DeploymentNativeHelperPackageImpactTest.Assisted(fixture);
             var request = fixture.request(fixture.installer("TRACKED", true))) {
            var intent = fixture.intent("TRACKED", request);
            reply(fixture, verdict("denied-installer", "DENY"));
            var denied = assertThrows(ApiException.class,
                () -> ((DeploymentCommandReview) assisted.scope.interaction()).approveCommand(intent, request, fixture.task));
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
            for (String name : List.of("mirrorRuntime", "managedHelper", "buildEntry")) {
                var descriptor = sources.path(name);
                assertEquals("EVIDENCE_PAGES", descriptor.path("retrieval").asText());
                String source = collect(evidence, descriptor, "installerReviewEvidence");
                assertIdentity(descriptor, source);
                sources.put(name, source);
            }
            assertArrayEquals(request.stdin(), DeploymentInstallerEvidence.reconstruct(facts.path("completeExecutableInput"))
                .getBytes(StandardCharsets.UTF_8));
            System.out.printf("INSTALLER_REVIEW_WIRE bytes=%d ceiling=%d%n", encoded.length, 365119);
        }
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
            assertTrue(mirrorSource.startsWith("class MirrorPolicy:", mirror.at("/locations/class MirrorPolicy:").intValue()));
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
            assertPage(pages.get(2), snapshot, kind.equals("dependencies") ? restored.path("completeExecutableInput").asText()
                : DeploymentManagedHelperBundle.renderScript(fixture.layout));
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
            assertThrows(IOException.class, () -> unique.read(response(Optional.empty(), excessive)));
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
            for (var batch : trace.batches()) {
                var calls = new ArrayList<AiToolCall>();
                for (var page : batch.pages()) {
                    calls.add(read("r23-page-" + callCount++, snapshot, page.helper() ? helper : mirror, page.offset(), page.limit()));
                }
                replyAtTotalBytes(fixture, continuations, batch.continuationBytes() - continuationBytes, calls.toArray(AiToolCall[]::new));
                continuationBytes = batch.continuationBytes();
            }
            reply(fixture, verdict("r23-final-denial", "DENY"));
            assertEquals("DEPLOYMENT_COMMAND_REVIEW_DENIED", assertThrows(ApiException.class, review::call).problem().eventCode());
            var last = fixture.model.requests.getLast();
            byte[] encoded;
            try {
                encoded = wire(last, trace.ceiling(), continuations);
            } catch (ModelRequestBudgetException tooLarge) {
                throw new AssertionError(project + " encoded " + tooLarge.encodedBytes() + " > " + trace.ceiling() + " bytes", tooLarge);
            }
            assertThrows(ModelRequestBudgetException.class, () -> wire(last, encoded.length - 1, continuations));
            assertEquals(65536, JSON.readTree(encoded).path("max_output_tokens").asInt());
            assertR23Continuations(encoded, continuations, callCount);
            assertR23Pages(last, snapshot, mirror, helper, fixture);
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

    private static CommandRequest diagnosedPreparation(MirroredInstallerTestFixture fixture, String ecosystem, String branch) {
        long seconds = 135;
        var input = new DeploymentToolchainDiagnostics.Request(fixture.operation, fixture.task.id().toString(), fixture.server.id(),
            "a".repeat(64), "a".repeat(64), fixture.layout, List.of("prepare", ecosystem, branch, "-", "120", "-"));
        String invocation = DeploymentToolchainDiagnostics.command("prepare-diagnosed", input);
        String guarded = DeploymentManagedHelperBundle.verifiedCommand(invocation, fixture.layout);
        String wrapped = DeploymentMirrorTasks.preparationCommand(guarded, fixture.task, seconds);
        return new CommandRequest(wrapped, input.bytes(), Duration.ofSeconds(seconds), 65536);
    }

    private static CommandRequest request(String kind, MirroredInstallerTestFixture fixture) {
        byte[] stdin = new byte[0];
        String command;
        long seconds;
        if (kind.equals("dependencies")) {
            seconds = 120;
            command = DeploymentToolchainDependencies.command(DeploymentToolchainDependencies.frame("NATIVE_BUILD", "b".repeat(32),
                seconds, fixture.server, fixture.task.id().toString()));
            stdin = DeploymentToolchainDependencies.dependencies(fixture.layout).getBytes(StandardCharsets.UTF_8);
        } else {
            seconds = kind.equals("prepare") ? 135 : 180;
            String arguments = kind.equals("prepare") ? "'prepare' 'NODE' '22' '-' '120' '-'" : "'bind' '" + binding(fixture) + "'";
            command = DeploymentManagedHelperBundle.verifiedCommand("'" + fixture.layout.helperPath() + "' prepare-toolchains " + arguments, fixture.layout);
        }
        String wrapped = DeploymentMirrorTasks.preparationCommand(command, fixture.task, seconds);
        return new CommandRequest(wrapped, stdin, Duration.ofSeconds(seconds), 65536);
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
        var reader = evidence.reader(256);
        var body = new StringBuilder();
        int total = descriptor.path("characters").intValue();
        while (body.length() < total) {
            var call = read("restore-" + body.length(), snapshot, descriptor.path("sourcePointer").asText(), body.length(), 8000);
            var reply = reader.read(response(Optional.empty(), call)).getFirst();
            assertFalse(reply.error());
            var page = pagePayload(reply.text()).path("value");
            assertEquals(body.length(), page.path("offset").intValue());
            assertEquals(descriptor.path("sha256").asText(), page.path("sha256").asText());
            String text = page.path("text").asText();
            assertFalse(text.isEmpty());
            body.append(text);
            assertEquals(body.length(), page.path("next").intValue());
        }
        return body.toString();
    }

    private static JsonNode restore(JsonNode wire, Map<String, String> pagedSources) throws Exception {
        var messages = wire.path("input");
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
            ((ObjectNode) projection.path("mirrorTransport")).put("runtimeSource", sources.get("/mirrorTransport/runtimeSource"));
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
        var output = nativeOutput("", calls);
        int framing = JSON.writeValueAsBytes(output).length;
        ((ObjectNode) output.get(0)).put("encrypted_content", "a".repeat(totalBytes - framing));
        assertEquals(totalBytes, JSON.writeValueAsBytes(output).length);
        retainReply(fixture, continuations, output, calls);
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
        String value = JSON.writeValueAsString(nativeOutput);
        var continuation = new AiContinuation(UUID.randomUUID(), value.getBytes(StandardCharsets.UTF_8).length);
        continuations.put(continuation.id(), value);
        fixture.model.replies.add(new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(response(Optional.of(continuation), calls)), Optional.empty()));
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
