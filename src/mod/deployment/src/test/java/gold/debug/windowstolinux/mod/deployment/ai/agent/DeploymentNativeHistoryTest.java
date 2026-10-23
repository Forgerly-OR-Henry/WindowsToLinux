package gold.debug.windowstolinux.mod.deployment.ai.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

import gold.debug.windowstolinux.appapi.ai.model.*;
import gold.debug.windowstolinux.mod.deployment.ai.NativeAiFixture;
import org.junit.jupiter.api.Test;

class DeploymentNativeHistoryTest {
    private static final gold.debug.windowstolinux.mod.deployment.config.DeploymentConfig.History SMALL_LIMITS =
        new gold.debug.windowstolinux.mod.deployment.config.DeploymentConfig.History(12, 42000, 65536, 2, 8192);

    @Test
    void stableEvidenceAndActivatedSkillsKeepNativePrefixAndWireOrder() throws Exception {
        var history = new DeploymentNativeHistory(SMALL_LIMITS);
        String stable = "{\"currentRequest\":\"COMPLETE_STABLE_EVIDENCE\"}";
        var first = history.agentMessages("BASE_POLICY", stable, "", "dynamic0");
        var calls = List.of(new AiToolCall("a", "read", "{}"), new AiToolCall("b", "read", "{}"));
        String opaque = "{\"role\":\"assistant\",\"content\":\"original\",\"tool_calls\":[{\"id\":\"a\",\"type\":\"function\",\"function\":{\"name\":\"read\",\"arguments\":\"{}\"}},{\"id\":\"b\",\"type\":\"function\",\"function\":{\"name\":\"read\",\"arguments\":\"{}\"}}]}";
        var continuation = new AiContinuation(UUID.randomUUID(), opaque.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        var reply = new AiResponse("original", "model", "tool_calls", new AiUsage(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()), calls, Optional.of(continuation));
        var results = List.of(new AiToolResult("a", "first original body", false), new AiToolResult("b", "second original body", true));
        assertThrows(IllegalArgumentException.class, () -> history.retain("dynamic0", reply, results.subList(0, 1)));
        assertThrows(IllegalArgumentException.class, () -> history.retain("dynamic0", reply, List.of(results.get(1), results.get(0))));
        history.retain("dynamic0", reply, results);
        var without = history.agentMessages("BASE_POLICY", stable, "", "dynamic1");
        var active = history.agentMessages("BASE_POLICY", stable, "TRUSTED_ACTIVATED_SKILL", "dynamic2");
        assertEquals(first.subList(0, 2), active.subList(0, 2));
        assertEquals(without.subList(0, without.size() - 1), active.subList(0, active.size() - 2));
        assertEquals(AiRole.TOOL, active.get(active.size() - 3).role());
        assertEquals(new AiMessage(AiRole.SYSTEM, "TRUSTED_ACTIVATED_SKILL", List.of()), active.get(active.size() - 2));
        assertEquals(1, active.stream().filter(message -> message.text().equals(stable)).count());
        assertTrue(history.visibleToolResult("first original body"));
        assertTrue(history.visibleToolResult("second original body"));
        var request = new AiRequest(AiPurpose.APPROVAL, active, 8192, java.time.Duration.ofMinutes(1), false);
        var codec = gold.debug.windowstolinux.app.ai.model.ModelProtocolCodec.forProtocol(gold.debug.windowstolinux.app.ai.model.ModelProtocol.OPENAI_CHAT_COMPLETIONS);
        var states = Map.of(continuation.id(), opaque);
        byte[] wire = codec.encode("fixture", request, 8192, gold.debug.windowstolinux.app.ai.model.ModelReasoningLevel.NONE, states);
        var encoded = new com.fasterxml.jackson.databind.ObjectMapper().readTree(wire).path("messages");
        assertEquals("system", encoded.get(encoded.size() - 2).path("role").asText());
        assertEquals("second original body", encoded.get(encoded.size() - 3).path("content").asText());
        var broken = new ArrayList<>(active);
        broken.remove(active.size() - 3);
        assertThrows(IllegalArgumentException.class, () -> codec.encode("fixture", new AiRequest(AiPurpose.APPROVAL, broken, 8192, java.time.Duration.ofMinutes(1), false), 8192,
            gold.debug.windowstolinux.app.ai.model.ModelReasoningLevel.NONE, states));
        System.out.println("APP_STABLE_PREFIX_MEASUREMENT fixedMessages=2 fixedUtf8Bytes=" + ("BASE_POLICY" + stable).getBytes(java.nio.charset.StandardCharsets.UTF_8).length + " finalWireBytes=" + wire.length + " fullResults=2 stableEvidenceCopies=1");
    }

    @Test
    void splitEvidenceAccountsForUnicodeContinuationAndFinalWireBudget() throws Exception {
        var history = new DeploymentNativeHistory(SMALL_LIMITS);
        var response = response("large");
        var opaque = new AiResponse(response.text(), response.modelId(), response.finishReason(), response.usage(), response.toolCalls(), Optional.of(new AiContinuation(UUID.randomUUID(), 50000)));
        history.agentMessages("policy", "stable", "", "initial");
        history.retain("initial", opaque, List.of(new AiToolResult("large", "original result", false)));
        var fit = history.agentMessages("policy", "stable", "中".repeat(6000), "current");
        assertFalse(history.visibleToolResult("original result"));
        assertEquals(4, fit.size());
        assertEquals("stable", fit.get(1).text());
        assertEquals("中".repeat(6000), fit.get(2).text());
        assertThrows(IllegalArgumentException.class, () -> history.agentMessages("policy", "stable", "中".repeat(23000), "current"));
        assertThrows(IllegalArgumentException.class, () -> history.agentMessages("policy", "stable", "", "x".repeat(42000)));
        assertThrows(IllegalArgumentException.class, () -> history.agentMessages("policy", "\uD800", "", "current"));
        var large = new DeploymentNativeHistory(new gold.debug.windowstolinux.mod.deployment.config.DeploymentConfig.History(12, 1048576, 1048576, 2, 8192));
        String unescaped = "\u0001".repeat(180000);
        var complete = large.agentMessages("policy", unescaped, "", "dynamic");
        assertEquals(unescaped, complete.get(1).text());
        assertThrows(IllegalArgumentException.class, () -> gold.debug.windowstolinux.app.ai.model.ModelProtocolCodec.forProtocol(gold.debug.windowstolinux.app.ai.model.ModelProtocol.OPENAI_CHAT_COMPLETIONS)
            .encode("fixture", new AiRequest(AiPurpose.APPROVAL, complete, 8192, java.time.Duration.ofMinutes(1), false), 8192,
                gold.debug.windowstolinux.app.ai.model.ModelReasoningLevel.NONE, Map.of()));
    }

    @Test
    void configuredLargeWindowRetainsEveryCompleteNativeBatchBeyondTheOldWindow() {
        var history = new DeploymentNativeHistory();
        history.agentMessages("policy", "initial");
        for (int turn = 0; turn < 20; turn++) {
            var calls = List.of(new AiToolCall("a" + turn, "read", "{}"), new AiToolCall("b" + turn, "read", "{}"));
            var response = new AiResponse("", "model", "tool_calls",
                new AiUsage(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()), calls,
                Optional.of(new AiContinuation(UUID.randomUUID(), 4000)));
            history.retain("state", response, List.of(new AiToolResult("a" + turn, "a".repeat(2000), false),
                new AiToolResult("b" + turn, "b".repeat(2000), false)));
        }
        var messages = history.agentMessages("policy", "current");
        assertEquals(20, messages.stream().filter(message -> message.role() == AiRole.ASSISTANT).count());
        assertTrue(messages.stream().mapToLong(message -> message.toolResult().map(result -> result.text().length()).orElse(0)).sum() > 65536);
        for (int index = 0; index < messages.size(); index++)
            if (messages.get(index).role() == AiRole.ASSISTANT) {
                var assistant = messages.get(index);
                assertTrue(assistant.continuation().isPresent());
                assertEquals(assistant.toolCalls().get(0).id(), messages.get(index + 1).toolResult().orElseThrow().callId());
                assertEquals(assistant.toolCalls().get(1).id(), messages.get(index + 2).toolResult().orElseThrow().callId());
                assertEquals(2000, messages.get(index + 1).toolResult().orElseThrow().text().length());
            }
    }

    @Test
    void completeApprovalAndHistoryStillFaceTheExactFinalEncodedOneMibGate() {
        var history = new DeploymentNativeHistory();
        var messages = history.reviewMessages("policy", "🧭".repeat(270000));
        assertEquals(540000, messages.getLast().text().length());
        var request = new AiRequest(AiPurpose.APPROVAL, messages, 8192, java.time.Duration.ofMinutes(2), false);
        assertEquals(1048576, request.maxRequestBytes());
        assertThrows(IllegalArgumentException.class,
            () -> gold.debug.windowstolinux.app.ai.model.ModelProtocolCodec.forProtocol(
                    gold.debug.windowstolinux.app.ai.model.ModelProtocol.OPENAI_CHAT_COMPLETIONS)
                .encode("fixture", request, 8192, gold.debug.windowstolinux.app.ai.model.ModelReasoningLevel.HIGH, Map.of()));
        assertEquals("🧭".repeat(270000), messages.getLast().text(), "The complete caller input is never silently truncated to fit");
    }

    @Test
    void agentMayEvictLatestWholeBatchWhileSharedContextStillRejectsIt() {
        var history = new DeploymentNativeHistory(SMALL_LIMITS);
        var shared = new DeploymentNativeHistory(SMALL_LIMITS);
        history.agentMessages("policy", "start");
        shared.messages("policy", "start");
        for (int turn = 0; turn < 2; turn++) {
            var response = response("anchor" + turn);
            var results = List.of(new AiToolResult("anchor" + turn, "anchor body " + turn, false));
            history.retain("state", response, results);
            shared.retain("state", response, results);
        }
        var anchors = history.agentMessages("policy", "before").subList(1, 7);
        var calls = List.of(new AiToolCall("large-a", "plan", "a".repeat(3000)), new AiToolCall("large-b", "read", "b".repeat(3000)));
        var response = new AiResponse("complete response", "model", "tool_calls",
            new AiUsage(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()), calls,
            Optional.of(new AiContinuation(UUID.randomUUID(), 50000)));
        var results = List.of(new AiToolResult("large-a", "actual rejected plan", true), new AiToolResult("large-b", "actual source", false));
        history.retain("state", response, results);
        shared.retain("state", response, results);
        assertThrows(IllegalArgumentException.class, () -> shared.messages("p".repeat(2000), "current".repeat(2000)),
            "Shared conversations retain their original latest-batch failure contract");
        var latest = shared.messages("policy", "current");
        assertEquals(AiMessage.assistant(response), latest.get(2));
        assertEquals(results, latest.stream().flatMap(message -> message.toolResult().stream()).toList());
        var messages = history.agentMessages("p".repeat(2000), "current".repeat(2000));
        assertEquals(anchors, messages.subList(1, 7), "Frozen initial batches remain exact");
        assertEquals(8, messages.size());
        assertTrue(messages.stream().flatMap(message -> message.toolCalls().stream()).noneMatch(call -> call.id().startsWith("large-")));
        assertTrue(messages.stream().flatMap(message -> message.toolResult().stream()).noneMatch(result -> result.callId().startsWith("large-")));
        assertFalse(history.visibleToolResult("actual rejected plan"));
        assertTrue(history.visibleToolResult("anchor body 0"));
    }

    @Test
    void agentRetainsExactFittingBatchesAndHardFailsWhenStateOrPolicyAloneCannotFit() {
        var history = new DeploymentNativeHistory(SMALL_LIMITS);
        var response = response("original");
        var result = new AiToolResult("original", "original body", true);
        history.retain("state", response, List.of(result));
        var messages = history.agentMessages("policy", "current");
        assertEquals(AiMessage.assistant(response), messages.get(2));
        assertEquals(result, messages.get(3).toolResult().orElseThrow());
        var oversized = assertThrows(IllegalArgumentException.class, () -> history.agentMessages("policy", "x".repeat(42001)));
        assertTrue(oversized.getMessage().contains("initialBatches=1"));
        assertTrue(oversized.getMessage().contains("completedBatches=0"));
        assertTrue(oversized.getMessage().contains("currentCharacters=42001"));
        assertFalse(oversized.getMessage().contains("original body"));
        var policy = assertThrows(IllegalArgumentException.class,
            () -> new DeploymentNativeHistory(SMALL_LIMITS).agentMessages("🧭".repeat(17000), "current"));
        assertTrue(policy.getMessage().contains("policyBytes=68000"));
        assertFalse(policy.getMessage().contains("🧭"));
    }

    @Test
    void agentEvictsOnlyNecessaryWholeInitialAnchorWhenCurrentStateFitsByItself() {
        var history = new DeploymentNativeHistory(SMALL_LIMITS);
        history.agentMessages("policy", "start");
        for (int turn = 0; turn < 2; turn++)
            history.retain("state", response("anchor" + turn), List.of(new AiToolResult("anchor" + turn, "a".repeat(1000), false)));
        var original = history.agentMessages("policy", "before");
        var messages = history.agentMessages("policy", "x".repeat(40000));
        assertEquals(original.subList(4, 7), messages.subList(1, 4));
        assertEquals(5, messages.size());
        assertEquals("anchor1", messages.get(2).toolCalls().getFirst().id());
        assertEquals("anchor1", messages.get(3).toolResult().orElseThrow().callId());
        assertEquals("x".repeat(40000), messages.getLast().text());
    }

    @Test
    void sharedRetentionEvictsOldInitialGroupsBeforeRecentBatchesAcrossRequests() {
        var history = new DeploymentNativeHistory(SMALL_LIMITS);
        for (int turn = 0; turn < 25; turn++) {
            String state = "state" + turn;
            history.messages("policy", state);
            history.retain(state, response("read" + turn), List.of(new AiToolResult("read" + turn, "b".repeat(3000), false)));
        }
        var messages = history.messages("policy", "current");
        assertEquals(java.util.stream.IntStream.range(13, 25).mapToObj(index -> "read" + index).toList(),
            messages.stream().flatMap(message -> message.toolResult().stream()).map(AiToolResult::callId).toList());
        for (int index = 0; index < messages.size(); index++)
            if (messages.get(index).role() == AiRole.ASSISTANT) {
                assertTrue(messages.get(index).continuation().isPresent());
                assertEquals(messages.get(index).toolCalls().getFirst().id(), messages.get(index + 1).toolResult().orElseThrow().callId());
            }
    }

    @Test
    void assistedSourceSelectionSendsCurrentRequestAfterEvictingOnlyOldInitialGroups() throws Exception {
        var fixture = new NativeAiFixture();
        for (int turn = 0; turn < 4; turn++)
            fixture.nativeReply(new AiToolCall("read" + turn, "read", "{\"path\":\"actual\",\"offset\":0,\"limit\":1}"));
        var latestResponse = fixture.replies.stream().skip(2).findFirst().orElseThrow().value().orElseThrow();
        var selection = new DeploymentNativeSelection(fixture.context);
        selection.select("source policy", Map.of("fact", "initial"), fixture.configurationRevision, fixture.task, false);
        selection.select("source policy", Map.of("fact", "a".repeat(3000)), fixture.configurationRevision, fixture.task, false);
        selection.select("source policy", Map.of("fact", "b".repeat(3000)), fixture.configurationRevision, fixture.task, false);
        var current = Map.of("fact", "c".repeat(124000));
        assertEquals("read3", selection.select("source policy", current, fixture.configurationRevision, fixture.task, false).id());
        assertEquals(4, fixture.requests.size());
        var messages = fixture.requests.getLast().messages();
        assertEquals(gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson.facts(current, 131072), messages.getLast().text());
        assertEquals(AiMessage.assistant(latestResponse), messages.get(2));
        assertEquals(List.of("read2"), messages.stream().flatMap(message -> message.toolResult().stream()).map(AiToolResult::callId).toList());
        assertTrue(messages.get(1).text().startsWith("Previous tool interaction batch 3 state sha256="));
        assertTrue(messages.get(1).text().contains("Batch numbers are not approval verdicts."));
    }

    @Test
    void approvalHistoryRetainsItsInitialPrefixAndCompleteCurrentEvidence() {
        var history = new DeploymentNativeHistory(SMALL_LIMITS);
        history.reviewMessages("policy", "first review");
        for (int turn = 0; turn < 2; turn++)
            history.retainReview("review" + turn, response("anchor" + turn), List.of(new AiToolResult("anchor" + turn, "reviewed", false)));
        var prefix = history.reviewMessages("policy", "next review").subList(1, 7);
        for (int turn = 2; turn < 20; turn++)
            history.retainReview("review" + turn, response("review" + turn), List.of(new AiToolResult("review" + turn, "b".repeat(3000), false)));
        String executable = "complete installer".repeat(6000);
        var messages = history.reviewMessages("policy", executable);
        assertEquals(prefix, messages.subList(1, 7));
        assertTrue(messages.get(1).text().startsWith("Previous tool interaction batch 1 state sha256="));
        assertEquals(executable, messages.getLast().text());
        assertEquals("review19", messages.get(messages.size() - 2).toolResult().orElseThrow().callId());
    }

    @Test
    void visibleResultsMatchExactLastRequestBodiesWithoutChangingNativeBindings() throws Exception {
        var history = new DeploymentNativeHistory(SMALL_LIMITS);
        String state = "original UTF-8 state 原始🧭";
        String body = "o1 component=api\nactual UTF-8 source 原始🧭\n";
        var response = response("query");
        var result = new AiToolResult("query", body, false);
        history.retain(state, response, List.of(result));
        assertFalse(history.visibleToolResult(body), "Retention alone does not mean a body was sent in a request");
        var messages = history.messages("policy", "current");
        assertTrue(history.visibleToolResult(body));
        assertFalse(history.visibleToolResult(body.stripTrailing()));
        assertFalse(history.visibleToolResult(body.replace("原始", "changed")));
        assertEquals(AiMessage.assistant(response), messages.get(2));
        assertEquals(result, messages.get(3).toolResult().orElseThrow());
        String digest = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
            .digest(state.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertTrue(messages.get(1).text().contains("state sha256=" + digest));
    }

    @Test
    void evictedResultCanReenterVisibilityOnlyAfterAnotherRequestIsAssembled() {
        var history = new DeploymentNativeHistory(SMALL_LIMITS);
        for (int turn = 0; turn < 2; turn++)
            history.retain("state", response("prefix" + turn), List.of(new AiToolResult("prefix" + turn, "prefix body " + turn, false)));
        String recovered = "o3 component=api\ncomplete previously queried source page";
        history.retain("state", response("query"), List.of(new AiToolResult("query", recovered, false)));
        history.messages("policy", "current");
        assertTrue(history.visibleToolResult(recovered));
        for (int turn = 0; turn < 2; turn++) {
            String id = "large" + turn;
            var value = response(id);
            var opaque = new AiResponse(value.text(), value.modelId(), value.finishReason(), value.usage(), value.toolCalls(),
                Optional.of(new AiContinuation(value.continuation().orElseThrow().id(), 33000)));
            history.retain("state", opaque, List.of(new AiToolResult(id, "different page " + turn, false)));
        }
        assertTrue(history.visibleToolResult(recovered), "The snapshot describes the request already sent, not post-response retention");
        var evicted = history.messages("policy", "current");
        assertFalse(evicted.stream().flatMap(message -> message.toolResult().stream()).anyMatch(result -> result.text().equals(recovered)));
        assertFalse(history.visibleToolResult(recovered));
        history.retain("state", response("restore"), List.of(new AiToolResult("restore", recovered, false)));
        assertFalse(history.visibleToolResult(recovered));
        var restored = history.messages("policy", "current");
        assertTrue(history.visibleToolResult(recovered));
        assertEquals(recovered, restored.get(restored.size() - 2).toolResult().orElseThrow().text());
        for (int index = 0; index < restored.size(); index++)
            if (restored.get(index).role() == AiRole.ASSISTANT) {
                assertTrue(restored.get(index).continuation().isPresent());
                assertEquals(restored.get(index).toolCalls().getFirst().id(), restored.get(index + 1).toolResult().orElseThrow().callId());
            }
    }

    @Test
    void visibleResultSnapshotsAreIsolatedBetweenHistoryInstances() {
        var first = new DeploymentNativeHistory(SMALL_LIMITS);
        var second = new DeploymentNativeHistory(SMALL_LIMITS);
        first.retain("state", response("first"), List.of(new AiToolResult("first", "first exact body", false)));
        first.messages("policy", "current");
        assertTrue(first.visibleToolResult("first exact body"));
        assertFalse(second.visibleToolResult("first exact body"));
        second.retain("state", response("second"), List.of(new AiToolResult("second", "second exact body", false)));
        second.messages("policy", "current");
        assertTrue(second.visibleToolResult("second exact body"));
        assertFalse(second.visibleToolResult("first exact body"));
        assertTrue(first.visibleToolResult("first exact body"));
        assertFalse(first.visibleToolResult("second exact body"));
    }

    @Test
    void opaqueProviderPayloadSizeEvictsOnlyWholeOldBatches() throws Exception {
        var history = new DeploymentNativeHistory(SMALL_LIMITS);
        var states = new HashMap<UUID, String>();
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        for (int index = 0; index < 3; index++) {
            String id = "opaque" + index;
            var response = response(id);
            var state = json.createObjectNode().put("role", "assistant").put("content", response.text())
                .put("reasoning_content", "synthetic-size-probe ".repeat(2000));
            var call = state.putArray("tool_calls").addObject().put("id", id).put("type", "function");
            call.putObject("function").put("name", "read").put("arguments", response.toolCalls().getFirst().arguments());
            var handle = new AiContinuation(response.continuation().orElseThrow().id(), state.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            response = new AiResponse(response.text(), response.modelId(), response.finishReason(), response.usage(), response.toolCalls(), Optional.of(handle));
            states.put(handle.id(), state.toString());
            history.retain("state", response, List.of(new AiToolResult(id, "o" + (index + 1) + "\nactual fixture body", false)));
        }
        var messages = history.messages("policy", "current");
        long visible = messages.stream().mapToLong(message -> message.text().length()
            + message.toolCalls().stream().mapToLong(call -> call.arguments().length()).sum()
            + message.toolResult().map(result -> result.text().length()).orElse(0)).sum();
        var request = new AiRequest(AiPurpose.LANGUAGE, messages, 8192, java.time.Duration.ofMinutes(1), false);
        var wire = gold.debug.windowstolinux.app.ai.model.ModelProtocolCodec.forProtocol(
                gold.debug.windowstolinux.app.ai.model.ModelProtocol.OPENAI_CHAT_COMPLETIONS)
            .encode("fixture", request, 8192, gold.debug.windowstolinux.app.ai.model.ModelReasoningLevel.HIGH, states);
        assertTrue(visible < 42000);
        assertTrue(wire.length < 65536);
        assertEquals(1, messages.stream().filter(message -> message.continuation().isPresent()).count());
        assertEquals("opaque2", messages.stream().flatMap(m -> m.toolResult().stream()).findFirst().orElseThrow().callId());
        System.out.println("AGENT_CONTEXT_MEASUREMENT visibleCharacters=" + visible + " wireBytes=" + wire.length
            + " opaqueCharacters=" + states.values().stream().mapToInt(String::length).sum());
    }

    @Test
    void intermediateReadBodyRemainsAvailableForFollowingIdentification() {
        var history = new DeploymentNativeHistory(SMALL_LIMITS);
        var evidence = new gold.debug.windowstolinux.mod.deployment.service.agent.protocol.DeploymentAgentEvidence();
        String revision = "a".repeat(64);
        String lostId = "";
        for (int turn = 0; turn < 12; turn++) {
            String id = evidence.observe("", revision, 0, "READ", "api/file" + turn + ".py", false);
            String body = "unique source body at turn " + turn;
            evidence.output(id, body);
            history.retain("state" + turn, response("read" + turn), List.of(new AiToolResult("read" + turn, id + "\n" + body, false)));
            if (turn == 9) lostId = id;
        }
        var messages = history.messages("policy", "identify with current READ metadata");
        assertTrue(messages.stream().flatMap(message -> message.toolResult().stream())
            .anyMatch(result -> result.text().contains("unique source body at turn 9")));
        assertTrue(evidence.latestIndex().toString().contains(lostId));
        assertTrue(evidence.read(lostId, 0, 6000).contains("unique source body at turn 9"));
    }

    @Test
    void agentKeepsInitialPrefixWhileOldestRecentBodiesAreEvicted() {
        var history = new DeploymentNativeHistory(SMALL_LIMITS);
        history.agentMessages("policy", "initial");
        for (int i = 0; i < 12; i++)
            history.retain("state" + i, response("r" + i), List.of(new AiToolResult("r" + i, "b".repeat(3000), false)));
        for (int i = 12; i < 25; i++)
            history.retain("state" + i, response("r" + i), List.of(new AiToolResult("r" + i, "b".repeat(3000), false)));
        var after = history.agentMessages("policy", "changed");
        assertEquals("r0", after.get(2).toolCalls().getFirst().id());
        assertEquals("r1", after.get(5).toolCalls().getFirst().id());
        assertEquals("r15", after.get(8).toolCalls().getFirst().id());
        assertEquals("r24", after.get(after.size() - 2).toolResult().orElseThrow().callId());
        var grown = history.agentMessages("policy", "x".repeat(32000));
        assertEquals(after.subList(1, 7), grown.subList(1, 7), "The initial complete prefix must remain byte-for-byte stable");
        assertEquals("r24", grown.get(grown.size() - 2).toolResult().orElseThrow().callId());
        assertTrue(grown.size() < after.size());
        assertThrows(IllegalArgumentException.class, () -> history.agentMessages("policy", "x".repeat(42001)),
            "Current state alone still cannot exceed its limit");
    }

    @Test
    void latestFullStateAppearsOnceWhileOriginalNativeResponsesAndResultsRemainExact() {
        var history = new DeploymentNativeHistory(SMALL_LIMITS);
        String oldState = "old-source-revision:" + "x".repeat(8000);
        String currentState = "current-source-revision:" + "y".repeat(9000);
        var response = response("first");
        var result = new AiToolResult("first", "o1\noriginal source body", false);
        history.retain(oldState, response, List.of(result));
        var messages = history.messages("fixed policy", currentState);
        assertEquals(currentState, messages.getLast().text());
        assertEquals(1, messages.stream().filter(m -> m.text().equals(currentState)).count());
        assertFalse(messages.stream().anyMatch(m -> m.text().contains(oldState)));
        assertTrue(messages.get(1).text().length() < 256);
        assertTrue(messages.get(1).text().startsWith("Previous tool interaction batch 1 state sha256="));
        assertEquals(AiMessage.assistant(response), messages.get(2));
        assertEquals(result, messages.get(3).toolResult().orElseThrow());
        assertEquals(new AiMessage(AiRole.SYSTEM, "fixed policy", List.of()), messages.getFirst());
    }

    @Test
    void stateChangesDoNotRewritePriorHistoryOrEvictToolBodiesToRepeatFullSnapshots() {
        var history = new DeploymentNativeHistory(SMALL_LIMITS);
        history.retain("first:" + "a".repeat(12000), response("a"), List.of(new AiToolResult("a", "body-a".repeat(500), false)));
        var originalGroup = history.messages("policy", "second:" + "b".repeat(12000)).subList(1, 4);
        history.retain("second:" + "b".repeat(12000), response("b"), List.of(new AiToolResult("b", "body-b".repeat(500), false)));
        var messages = history.messages("policy", "third:" + "c".repeat(12000));
        assertEquals(originalGroup, messages.subList(1, 4));
        assertNotEquals(messages.get(1).text(), messages.get(4).text());
        assertEquals(2, messages.stream().flatMap(m -> m.toolResult().stream()).count());
        assertTrue(messages.stream().mapToInt(m -> m.text().length()).sum() < 21000);
    }

    @Test
    void contextBudgetStillEvictsWholeGroupsAndRejectsOversizedCurrentState() {
        var history = new DeploymentNativeHistory(SMALL_LIMITS);
        for (int i = 0; i < 4; i++) {
            String id = "call" + i;
            history.retain("snapshot", response(id), List.of(new AiToolResult(id, "z".repeat(10000), false)));
        }
        var messages = history.messages("policy", "current".repeat(1000));
        assertEquals(3, messages.stream().filter(m -> m.role() == AiRole.ASSISTANT).count());
        assertEquals(List.of("call1", "call2", "call3"), messages.stream().flatMap(m -> m.toolResult().stream()).map(AiToolResult::callId).toList());
        assertThrows(IllegalArgumentException.class, () -> history.messages("policy", "x".repeat(42001)));
        assertThrows(IllegalArgumentException.class, () -> history.retain("x".repeat(42001), response("next"), List.of(new AiToolResult("next", "body", false))));
    }

    @Test
    void missingReorderedOrForeignResultsCannotEnterHistory() {
        var history = new DeploymentNativeHistory(SMALL_LIMITS);
        var response = response("expected");
        assertThrows(IllegalArgumentException.class, () -> history.retain("state", response, List.of()));
        assertThrows(IllegalArgumentException.class, () -> history.retain("state", response, List.of(new AiToolResult("foreign", "body", false))));
        var batch = new AiResponse(response.text(), response.modelId(), response.finishReason(), response.usage(),
            List.of(new AiToolCall("a", "read", "{}"), new AiToolCall("b", "read", "{}")), response.continuation());
        assertThrows(IllegalArgumentException.class, () -> history.retain("state", batch,
            List.of(new AiToolResult("b", "body", false), new AiToolResult("a", "body", false))));
        assertEquals(2, history.messages("policy", "current").size());
    }

    private AiResponse response(String id) {
        return new AiResponse("original assistant text", "model", "tool_calls",
            new AiUsage(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
            List.of(new AiToolCall(id, "read", "{\"path\":\"original\"}")), Optional.of(new AiContinuation(UUID.randomUUID(), 1)));
    }

    @Test
    void agentTrimmingNeverSeparatesCallBatchFromAllResultsOrLosesTheBinding() {
        var history = new DeploymentNativeHistory(SMALL_LIMITS);
        history.agentMessages("policy", "initial");
        for (int i = 0; i < 20; i++) {
            var calls = List.of(new AiToolCall("a" + i, "read", "{}"), new AiToolCall("b" + i, "read", "{}"));
            var response = new AiResponse("", "model", "tool_calls", new AiUsage(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()), calls, Optional.of(new AiContinuation(UUID.randomUUID(), 1)));
            history.retain("facts", response, List.of(new AiToolResult("a" + i, "first", false), new AiToolResult("b" + i, "second", false)));
        }
        var messages = history.agentMessages("policy", "current");
        assertEquals(12, messages.stream().filter(m -> m.role() == AiRole.ASSISTANT).count());
        assertEquals("a0", messages.get(2).toolCalls().getFirst().id());
        assertEquals("a1", messages.get(6).toolCalls().getFirst().id());
        assertEquals("a10", messages.get(10).toolCalls().getFirst().id());
        assertEquals("a19", messages.stream().filter(m -> m.role() == AiRole.ASSISTANT).reduce((earlier, last) -> last).orElseThrow().toolCalls().getFirst().id());
        for (int index = 0; index < messages.size(); index++)
            if (messages.get(index).role() == AiRole.ASSISTANT) {
                var assistant = messages.get(index);
                assertTrue(assistant.continuation().isPresent());
                assertEquals(assistant.toolCalls().get(0).id(), messages.get(index + 1).toolResult().orElseThrow().callId());
                assertEquals(assistant.toolCalls().get(1).id(), messages.get(index + 2).toolResult().orElseThrow().callId());
            }
    }
}
