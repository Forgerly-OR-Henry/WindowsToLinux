package gold.debug.windowstolinux.mod.deployment.ai.agent;

import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import gold.debug.windowstolinux.appapi.ai.model.*;

/** Keeps complete native batches; only the latest message carries the full current state. */
public final class DeploymentNativeHistory {
    private final gold.debug.windowstolinux.mod.deployment.config.DeploymentConfig.History limits;

    public DeploymentNativeHistory() {
        this(gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigLoader.current().ai().history());
    }

    public DeploymentNativeHistory(gold.debug.windowstolinux.mod.deployment.config.DeploymentConfig.History limits) {
        this.limits = Objects.requireNonNull(limits);
    }

    private final Deque<List<AiMessage>> batches = new ArrayDeque<>();

    private final List<List<AiMessage>> initial = new ArrayList<>();

    private boolean initialClosed;

    private long decisions;

    private Set<String> visibleToolResults = Set.of();

    private boolean preserveInitial;

    private boolean splitEvidence;

    public List<AiMessage> messages(String policy, String current) {
        preserveInitial = false;
        splitEvidence = false;
        trimShared(current.length(), bytes(policy) + bytes(current));
        if (size() + current.length() > limits.characters() || payloadSize() + bytes(policy) + bytes(current) > limits.payloadBytes())
            throw new IllegalArgumentException("Current Agent evidence exceeds bounded context");
        return visibleMessages(policy, current);
    }

    private List<AiMessage> visibleMessages(String policy, String current) {
        return visibleMessages(assembled(policy, current));
    }

    private List<AiMessage> visibleMessages(List<AiMessage> messages) {
        var visible = new HashSet<String>();
        messages.forEach(message -> message.toolResult().ifPresent(result -> visible.add(resultDigest(result.text()))));
        visibleToolResults = Set.copyOf(visible);
        return messages;
    }

    /** Autonomous planning may drop even the latest completed batch as one unit; shared conversations keep their original retention rule. */
    public List<AiMessage> agentMessages(String policy, String current) {
        preserveInitial = true;
        splitEvidence = false;
        long currentBytes = bytes(policy) + bytes(current);
        if (current.length() > limits.characters() || currentBytes > limits.payloadBytes())
            throw agentContextFailure(policy, current);
        trim(current.length(), currentBytes);
        while (!batches.isEmpty() && (size() + current.length() > limits.characters() || payloadSize() + currentBytes > limits.payloadBytes()))
            batches.removeFirst();
        while (!initial.isEmpty() && (size() + current.length() > limits.characters() || payloadSize() + currentBytes > limits.payloadBytes()))
            initial.removeFirst();
        return visibleMessages(policy, current);
    }

    /** Keeps one complete current evidence prefix; only activated product skills and the dynamic tail follow native batches. */
    public List<AiMessage> agentMessages(String policy, String stableEvidence, String activatedSkills, String current) {
        preserveInitial = true;
        splitEvidence = true;
        long fixedCharacters = (long) policy.length() + stableEvidence.length() + activatedSkills.length() + current.length();
        long fixedBytes = strictBytes(policy) + strictBytes(stableEvidence) + strictBytes(activatedSkills) + strictBytes(current);
        if (fixedCharacters > limits.characters() || fixedBytes > limits.payloadBytes())
            throw new IllegalArgumentException("Complete fixed Agent evidence, policy, skills and state exceed bounded context");
        trim((int) fixedCharacters, fixedBytes);
        while (!batches.isEmpty() && (size() + fixedCharacters > limits.characters() || payloadSize() + fixedBytes > limits.payloadBytes()))
            batches.removeFirst();
        while (!initial.isEmpty() && (size() + fixedCharacters > limits.characters() || payloadSize() + fixedBytes > limits.payloadBytes()))
            initial.removeFirst();
        var messages = new ArrayList<AiMessage>();
        messages.add(new AiMessage(AiRole.SYSTEM, policy, List.of()));
        messages.add(new AiMessage(AiRole.USER, stableEvidence, List.of()));
        initial.forEach(messages::addAll);
        batches.forEach(messages::addAll);
        if (!activatedSkills.isEmpty()) messages.add(new AiMessage(AiRole.SYSTEM, activatedSkills, List.of()));
        messages.add(new AiMessage(AiRole.USER, current, List.of()));
        for (var message : messages) {
            strictBytes(message.text());
            message.toolCalls().forEach(call -> strictBytes(call.arguments()));
            message.toolResult().ifPresent(result -> strictBytes(result.text()));
        }
        return visibleMessages(List.copyOf(messages));
    }

    private static long strictBytes(String value) {
        try {
            return StandardCharsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(value)).remaining();
        } catch (java.nio.charset.CharacterCodingException invalid) {
            throw new IllegalArgumentException("Agent message is not valid Unicode", invalid);
        }
    }

    private IllegalArgumentException agentContextFailure(String policy, String current) {
        return new IllegalArgumentException("Current Agent state and policy exceed bounded context: initialBatches=" + initial.size()
            + " completedBatches=" + batches.size() + " historyCharacters=" + size() + " historyPayloadBytes=" + payloadSize()
            + " policyBytes=" + bytes(policy) + " currentCharacters=" + current.length() + " currentBytes=" + bytes(current));
    }

    /** Checks exact result-body visibility in the last assembled Agent request, independently of later retention. */
    public boolean visibleToolResult(String body) {
        return visibleToolResults.contains(resultDigest(body));
    }

    private static String resultDigest(String body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /** Approval retains a separately bounded history while the current complete executable evidence keeps its existing transport bound. */
    public List<AiMessage> reviewMessages(String policy, String current) {
        preserveInitial = true;
        splitEvidence = false;
        if (current.length() > 1048576)
            throw new IllegalArgumentException("Complete approval evidence exceeds its existing bound");
        trim(0, bytes(policy));
        if (size() > limits.characters() || payloadSize() + bytes(policy) > limits.payloadBytes())
            throw new IllegalArgumentException("Approval history exceeds bounded context");
        return assembled(policy, current);
    }

    private List<AiMessage> assembled(String policy, String current) {
        var messages = new ArrayList<AiMessage>();
        messages.add(new AiMessage(AiRole.SYSTEM, policy, List.of()));
        initial.forEach(messages::addAll);
        batches.forEach(messages::addAll);
        messages.add(new AiMessage(AiRole.USER, current, List.of()));
        return List.copyOf(messages);
    }

    public void retain(String input, AiResponse response, List<AiToolResult> results) {
        retain(input, response, results, limits.characters());
    }

    public void retainReview(String input, AiResponse response, List<AiToolResult> results) {
        retain(input, response, results, 1048576);
    }

    private void retain(String input, AiResponse response, List<AiToolResult> results, int inputLimit) {
        var ids = response.toolCalls().stream().map(AiToolCall::id).toList();
        if (!ids.equals(results.stream().map(AiToolResult::callId).toList()))
            throw new IllegalArgumentException("Native batch results must preserve every call in order");
        var group = new ArrayList<AiMessage>();
        group.add(new AiMessage(AiRole.USER, stateReference(input, inputLimit), List.of()));
        group.add(AiMessage.assistant(response));
        results.forEach(result -> group.add(AiMessage.result(result)));
        if (groupSize(group) > limits.characters() || groupBytes(group) > limits.payloadBytes())
            throw new IllegalArgumentException("A complete native batch exceeds context budget");
        var complete = List.copyOf(group);
        if (!initialClosed && decisions < limits.initialBatches() && groupBytes(group)
            + initial.stream().mapToLong(DeploymentNativeHistory::groupBytes).sum() <= limits.initialPayloadBytes()) {
            initial.add(complete);
        } else {
            initialClosed = true;
            batches.addLast(complete);
        }
        if (decisions == limits.initialBatches() - 1) initialClosed = true;
        if (preserveInitial) trim(0, 0);
        else trimShared(0, 0);
        decisions++;
    }

    private String stateReference(String input, int inputLimit) {
        if (input.length() > inputLimit) throw new IllegalArgumentException("Current evidence exceeds bounded context");
        try {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(input.getBytes(StandardCharsets.UTF_8)));
            return "Previous tool interaction batch " + (decisions + 1) + " state sha256=" + digest
                + ". Batch numbers are not approval verdicts. " + (splitEvidence
                ? "Use the fixed current evidence USER message and final dynamic USER state; prior tool results are historical evidence."
                : "Use the latest user message for current facts; prior tool results are historical evidence.");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private long size() {
        return initial.stream().mapToLong(DeploymentNativeHistory::groupSize).sum()
            + batches.stream().mapToLong(DeploymentNativeHistory::groupSize).sum();
    }

    private static long groupSize(List<AiMessage> group) {
        return group.stream().mapToLong(m -> m.text().length()
            + m.toolCalls().stream().mapToLong(c -> c.arguments().length()).sum()
            + m.toolResult().map(r -> r.text().length()).orElse(0)).sum();
    }

    private long payloadSize() {
        return initial.stream().mapToLong(DeploymentNativeHistory::groupBytes).sum()
            + batches.stream().mapToLong(DeploymentNativeHistory::groupBytes).sum();
    }

    private static long bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static long groupBytes(List<AiMessage> group) {
        return group.stream().mapToLong(m -> bytes(m.text())
            + m.toolCalls().stream().mapToLong(c -> bytes(c.arguments())).sum()
            + m.toolResult().map(r -> bytes(r.text())).orElse(0L)
            + m.continuation().map(c -> (long) c.payloadBytes()).orElse(0L)).sum();
    }

    private void trim(int currentSize, long currentBytes) {
        while (batches.size() > 1 && (batches.size() + initial.size() > limits.maxBatches() || size() + currentSize > limits.characters()
            || payloadSize() + currentBytes > limits.payloadBytes())) batches.removeFirst();
    }

    /** Shared callers may discard the oldest complete group, but must retain the latest group. */
    private void trimShared(int currentSize, long currentBytes) {
        while (initial.size() + batches.size() > 1 && (initial.size() + batches.size() > limits.maxBatches()
            || size() + currentSize > limits.characters() || payloadSize() + currentBytes > limits.payloadBytes())) {
            if (!initial.isEmpty()) initial.removeFirst();
            else batches.removeFirst();
        }
    }
}
