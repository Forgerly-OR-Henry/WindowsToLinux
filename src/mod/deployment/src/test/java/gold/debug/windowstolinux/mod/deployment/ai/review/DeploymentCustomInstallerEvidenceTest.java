package gold.debug.windowstolinux.mod.deployment.ai.review;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import gold.debug.windowstolinux.appapi.ai.model.*;
import gold.debug.windowstolinux.appapi.task.action.*;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson;
import gold.debug.windowstolinux.mod.deployment.linux.helper.*;
import gold.debug.windowstolinux.mod.deployment.service.agent.review.*;
import gold.debug.windowstolinux.mod.deployment.service.agent.toolchain.DeploymentAgentToolchainPrograms;
import gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentHelperInstallerScript;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeploymentCustomInstallerEvidenceTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void oneCompleteWorkerRestoresBothExecutableUsesScopesTargetsAndCompositeNavigation(boolean mirrored) throws Exception {
        try (var fixture = new MirroredInstallerTestFixture(); var request = fixture.request(fixture.installer("CUSTOM", mirrored))) {
            ObjectNode facts = JSON.valueToTree(fixture.facts("CUSTOM", request));
            var evidence = facts.path("completeExecutableInput");
            String script = new String(request.stdin(), StandardCharsets.UTF_8);
            String program = DeploymentAgentToolchainPrograms.dependencies(fixture.layout);
            String worker = program.substring(program.indexOf('\n') + 1);
            assertEquals(mirrored ? DeploymentInstallerEvidence.CUSTOM_MIRROR_FORMAT : DeploymentInstallerEvidence.CUSTOM_FORMAT, evidence.path("format").asText());
            assertEquals(mirrored ? 4 : 3, evidence.path("sources").size());
            assertEquals(mirrored ? 11 : 9, evidence.path("segments").size());
            assertEquals(worker, evidence.at("/sources/dependencyWorker").textValue());
            assertArrayEquals(request.stdin(), DeploymentInstallerEvidence.reconstruct(evidence).getBytes(StandardCharsets.UTF_8));
            DeploymentInstallerEvidence.verify(evidence, script);
            var oldShape = JSON.valueToTree(DeploymentInstallerEvidence.describe(script,
                DeploymentAgentHelperInstallerScript.render(fixture.customOperation, fixture.layout, fixture.server, fixture.task.id().toString()), fixture.layout));
            ObjectNode unchangedScopes = evidence.path("digestScopes").deepCopy();
            unchangedScopes.remove("dependencyWorkerDigest");
            assertEquals(oldShape.path("digestScopes"), unchangedScopes, "Original stdin/body/helper/entry/runtime digest scopes retain their exact meanings and bytes");
            assertEquals(fixture.intent("CUSTOM", request).exactAction(), facts.path("exactAction").textValue());
            assertEquals(DeploymentAgentHelperBootstrapEvidence.targets(fixture.server, fixture.layout, fixture.customOperation),
                JSON.convertValue(facts.path("resourceTargets"), new TypeReference<Set<ActionTarget>>() {}));
            assertEquals(MirroredInstallerTestFixture.sha(worker), evidence.at("/digestScopes/dependencyWorkerDigest/value").textValue());
            assertEquals("COMPLETE_DEPENDENCY_WORKER_SOURCE_UTF8_BEFORE_JSON_STRING_OR_SOURCE_TEXT_TRANSFORMATION", evidence.at("/digestScopes/dependencyWorkerDigest/bytes").textValue());
            int jsonIndex = mirrored ? 3 : 1;
            assertEquals("JSON_STRING", evidence.path("segments").get(jsonIndex).path("kind").textValue());
            assertEquals("\n", evidence.path("segments").get(jsonIndex + 1).path("text").textValue());
            assertEquals("SOURCE_TEXT", evidence.path("segments").get(jsonIndex + 2).path("kind").textValue());
            assertEquals(2, java.util.stream.StreamSupport.stream(evidence.path("segments").spliterator(), false)
                .filter(segment -> segment.path("source").asText().equals("dependencyWorker")).count());
            var leaves = DeploymentInstallerEvidence.readableBodies(evidence);
            assertEquals(mirrored ? 10 : 8, leaves.size());
            assertEquals(1, leaves.stream().filter("/sources/dependencyWorker"::equals).count());
            if (mirrored) {
                assertFalse(facts.path("mirrorTransport").has("runtimeSource"));
                assertEquals(DeploymentInstallerEvidence.MIRROR_SOURCE_POINTER, facts.at("/mirrorTransport/runtimeSourcePointer").textValue());
                DeploymentInstallerEvidence.verifySourceReferences(facts);
            }
            var conversation = fixture.conversation();
            var complete = JSON.readTree(JSON.writeValueAsString(conversation.facts("Review exact CUSTOM bytes",
                fixture.facts("CUSTOM", request), fixture.model.configurationRevision, fixture.task)));
            var navigation = complete.at("/helperInstallationSourceEvidence/agentBootstrap");
            assertEquals(mirrored ? 6 : 5, complete.at("/helperInstallationSourceEvidence/installerLocations").size());
            assertEquals(leaves.size(), complete.at("/helperInstallationSourceEvidence/requiredExecutableEvidencePointers").size());
            var prefix = new StringBuilder();
            for (var part : navigation.path("prefixParts")) prefix.append(MirroredInstallerTestFixture.navigationPart(complete, part, request.stdin()));
            assertTrue(script.startsWith(prefix.toString()));
            var restoredProgram = new StringBuilder();
            for (var part : navigation.at("/dependencyProgram/parts")) restoredProgram.append(MirroredInstallerTestFixture.navigationPart(complete, part, request.stdin()));
            assertEquals(program, restoredProgram.toString());
            assertEquals(4, navigation.at("/dependencyProgram/parts").size());
            assertEquals(MirroredInstallerTestFixture.sha(program), navigation.at("/dependencyProgram/sha256").textValue());
            for (var location : navigation.path("dependencyLocations")) {
                assertEquals("/completeExecutableInput/sources/dependencyWorker", location.path("sourcePointer").textValue());
                assertTrue(worker.lines().skip(location.path("startLine").longValue() - 1).findFirst().orElseThrow().startsWith("def " + location.path("function").textValue() + "("));
            }
            String current = DeploymentAiJson.facts(JSON.convertValue(complete, new TypeReference<Map<String, Object>>() {}), 1048576);
            var messages = conversation.messages(current, fixture.model.configurationRevision, fixture.task);
            assertEquals(complete, MirroredInstallerTestFixture.restoreCarrier(messages));
            assertEquals(mirrored ? 10 : 8, JSON.readTree(messages.getLast().text()).at("/approvalSourceCarrier/frames").size());
            assertTrue(fixture.model.requests.isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"MISSING_WORKER", "MISSING_JSON_REFERENCE", "MISSING_RAW_REFERENCE", "EXTRA_SOURCE", "EXTRA_REFERENCE",
        "REFERENCE_ORDER", "REFERENCE_KIND", "REFERENCE_TARGET", "REFERENCE_FIELD", "UNKNOWN_FORMAT", "JSON_PREFIX", "LF",
        "WORKER_SOURCE", "PACKAGE_METADATA", "WORKER_DIGEST", "MIRROR_POINTER", "MIRROR_DIGEST", "MIRROR_INSERTION", "INVALID_UNICODE", "LITERAL_FIELD"})
    void changedCustomSourceReferenceEncodingOrMirrorBindingStopsBeforeModelAndHost(String mutation) throws Exception {
        try (var fixture = new MirroredInstallerTestFixture(); var request = fixture.request(fixture.installer("CUSTOM", true))) {
            ObjectNode facts = JSON.valueToTree(fixture.facts("CUSTOM", request));
            var evidence = (ObjectNode) facts.path("completeExecutableInput");
            var sources = (ObjectNode) evidence.path("sources");
            var segments = (ArrayNode) evidence.path("segments");
            switch (mutation) {
                case "MISSING_WORKER" -> sources.remove("dependencyWorker");
                case "MISSING_JSON_REFERENCE" -> segments.remove(3);
                case "MISSING_RAW_REFERENCE" -> segments.remove(5);
                case "EXTRA_SOURCE" -> sources.put("unreviewed", "print('changed')\n");
                case "EXTRA_REFERENCE" -> segments.add(segments.get(3).deepCopy());
                case "REFERENCE_ORDER" -> { JsonNode first = segments.get(3).deepCopy(); segments.set(3, segments.get(5).deepCopy()); segments.set(5, first); }
                case "REFERENCE_KIND" -> ((ObjectNode) segments.get(3)).put("kind", "SOURCE_TEXT");
                case "REFERENCE_TARGET" -> ((ObjectNode) segments.get(3)).put("source", "managedHelper");
                case "REFERENCE_FIELD" -> ((ObjectNode) segments.get(3)).put("text", "unreviewed");
                case "UNKNOWN_FORMAT" -> evidence.put("format", "ORDERED_CUSTOM_DEPENDENCY_WORKER_FUTURE");
                case "JSON_PREFIX" -> ((ObjectNode) segments.get(2)).put("text", segments.get(2).path("text").textValue() + " ");
                case "LF" -> ((ObjectNode) segments.get(4)).put("text", "\r\n");
                case "WORKER_SOURCE" -> sources.put("dependencyWorker", sources.path("dependencyWorker").textValue() + "# changed\n");
                case "PACKAGE_METADATA" -> sources.put("dependencyWorker", sources.path("dependencyWorker").textValue().replace("DEPENDENCY_PACKAGES = json.loads(", "DEPENDENCY_PACKAGES = changed.loads("));
                case "WORKER_DIGEST" -> ((ObjectNode) evidence.at("/digestScopes/dependencyWorkerDigest")).put("value", "0".repeat(64));
                case "MIRROR_POINTER" -> ((ObjectNode) facts.path("mirrorTransport")).put("runtimeSourcePointer", "/completeExecutableInput/sources/dependencyWorker");
                case "MIRROR_DIGEST" -> ((ObjectNode) facts.path("mirrorTransport")).put("runtimeSha256", "0".repeat(64));
                case "MIRROR_INSERTION" -> ((ObjectNode) segments.get(2)).put("text", "# changed\n" + segments.get(2).path("text").textValue());
                case "INVALID_UNICODE" -> sources.put("dependencyWorker", sources.path("dependencyWorker").textValue() + '\uD800');
                case "LITERAL_FIELD" -> ((ObjectNode) segments.get(4)).put("source", "dependencyWorker");
                default -> throw new AssertionError(mutation);
            }
            Map<String, Object> changed = JSON.convertValue(facts, new TypeReference<>() {});
            var protocol = new DeploymentCommandProtocol() {
                public boolean registered(ActionIntent intent) { return true; }
                public Map<String, Object> inspect(ActionIntent intent, gold.debug.windowstolinux.appapi.linux.command.CommandRequest command,
                    gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout layout, gold.debug.windowstolinux.appapi.task.run.TaskContext task) { return changed; }
            };
            var hosts = new AtomicInteger();
            var host = (InteractionService) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{InteractionService.class}, (p, m, a) -> {
                hosts.incrementAndGet(); throw new AssertionError("Invalid CUSTOM evidence cannot obtain a grant");
            });
            var ai = new DeploymentAiJson(fixture.context, fixture.conversation());
            var refused = assertThrows(ApiException.class, () -> DeploymentFixedCommandApproval.approve(fixture.context, host,
                fixture.intent("CUSTOM", request), request, fixture.task, fixture.model.configurationRevision, "AUTOMATIC", false,
                DeploymentCommandReviewModel.submission(ai), DeploymentCurrentCommandEvidence.NONE, true, protocol));
            assertEquals("DEPLOYMENT_COMMAND_REVIEW_INVALID", refused.problem().eventCode());
            assertTrue(fixture.model.requests.isEmpty());
            assertEquals(0, hosts.get());
        }
    }

    @Test
    void noncanonicalJsonEscapingOrChangedPackageProgramCannotSupplyCanonicalWorkerEvidence() throws Exception {
        try (var fixture = new MirroredInstallerTestFixture()) {
            String script = fixture.installer("CUSTOM", false);
            String program = DeploymentAgentToolchainPrograms.dependencies(fixture.layout);
            String escaped = program.substring(0, program.indexOf('\n')).replace("import", "\\u0069mport") + program.substring(program.indexOf('\n'));
            assertNotEquals(program, escaped);
            assertThrows(IllegalArgumentException.class, () -> DeploymentInstallerEvidence.describeCustom(script, script, escaped, fixture.layout));
            assertThrows(IllegalArgumentException.class, () -> DeploymentAgentHelperBootstrapEvidence.describe(
                script.replace("DEPENDENCY_PACKAGES = json.loads(", "DEPENDENCY_PACKAGES = changed.loads("), fixture.layout));
            assertTrue(fixture.model.requests.isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"PLAIN", "TRACKED"})
    void originalV1AndV2FormatsStillRejectCustomSourcesAndEncodings(String kind) throws Exception {
        try (var fixture = new MirroredInstallerTestFixture(); var request = fixture.request(fixture.installer(kind, kind.equals("TRACKED")))) {
            var original = JSON.valueToTree(fixture.facts(kind, request)).path("completeExecutableInput");
            DeploymentInstallerEvidence.verify(original, new String(request.stdin(), StandardCharsets.UTF_8));
            ObjectNode extra = original.deepCopy();
            ((ObjectNode) extra.path("sources")).put("dependencyWorker", "print('unreviewed')\n");
            assertThrows(IllegalArgumentException.class, () -> DeploymentInstallerEvidence.reconstruct(extra));
            ObjectNode encoding = original.deepCopy();
            ((ObjectNode) encoding.path("segments").get(1)).put("kind", "JSON_STRING");
            assertThrows(IllegalArgumentException.class, () -> DeploymentInstallerEvidence.reconstruct(encoding));
            assertTrue(fixture.model.requests.isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"DENY", "APPROVE"})
    void customCompleteEvidenceKeepsFinalDenyAndEveryApproveRequiresFreshOriginalHostGrant(String decision) throws Exception {
        try (var fixture = new MirroredInstallerTestFixture(); var request = fixture.request(fixture.installer("CUSTOM", true))) {
            var hosts = new AtomicInteger();
            var grants = new HashSet<UUID>();
            var intent = fixture.intent("CUSTOM", request);
            var host = (InteractionService) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{InteractionService.class}, (p, m, a) -> {
                assertEquals("approveReviewedAction", m.getName());
                assertEquals(intent, a[0]); assertEquals(Optional.of(request), a[1]); assertSame(fixture.task, a[4]);
                assertEquals("APPROVE", decision);
                hosts.incrementAndGet();
                var grant = new ApprovalGrant(UUID.randomUUID()); assertTrue(grants.add(grant.id()));
                return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(grant), Optional.empty());
            });
            var ai = new DeploymentAiJson(fixture.context, fixture.conversation());
            int attempts = decision.equals("APPROVE") ? 2 : 1;
            for (int index = 0; index < attempts; index++) {
                fixture.model.nativeReply(new AiToolCall("custom-verdict-" + index, "submit_result",
                    "{\"decision\":\"" + decision + "\",\"requiresUserConfirmation\":false,\"reason\":\"Independent current CUSTOM verdict\"}"));
                org.junit.jupiter.api.function.Executable approval = () -> DeploymentFixedCommandApproval.approve(fixture.context, host, intent,
                    request, fixture.task, fixture.model.configurationRevision, "AUTOMATIC", false, DeploymentCommandReviewModel.submission(ai),
                    DeploymentCurrentCommandEvidence.NONE, true, new DeploymentAgentCommandProtocol(fixture.context, Map::of));
                if (decision.equals("DENY")) assertEquals("DEPLOYMENT_COMMAND_REVIEW_DENIED", assertThrows(ApiException.class, approval).problem().eventCode());
                else assertDoesNotThrow(approval);
            }
            assertEquals(attempts, fixture.model.requests.size());
            assertEquals(decision.equals("APPROVE") ? 2 : 0, hosts.get());
        }
    }
}
