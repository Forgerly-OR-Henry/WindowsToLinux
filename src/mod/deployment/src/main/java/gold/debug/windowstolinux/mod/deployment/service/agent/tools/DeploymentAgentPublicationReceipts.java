package gold.debug.windowstolinux.mod.deployment.service.agent.tools;

import java.time.Duration;
import java.util.*;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;

import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.*;
import gold.debug.windowstolinux.appapi.linux.session.*;
import gold.debug.windowstolinux.appapi.linux.transfer.*;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.ApprovalGrant;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentBuildCommandChannel;
import gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson;
import gold.debug.windowstolinux.mod.deployment.ai.json.DeploymentJsonFields;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.linux.runtime.DeploymentWorkloadArguments;
import gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentFeedback;
import gold.debug.windowstolinux.mod.deployment.service.agent.contract.DeploymentAgentInputs;
import gold.debug.windowstolinux.mod.deployment.service.core.model.application.DeploymentUnitIdentity;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentHealthCheck;
import gold.debug.windowstolinux.mod.deployment.service.core.publish.DeploymentPublishResult;

/** Observes only exact fixed health requests sent by the publisher; owns no remote authority. */
final class DeploymentAgentPublicationReceipts implements RemoteSession {
    private static final int RETAINED_RECEIPTS = 4;

    private static final int STDERR_CHARACTERS = 800;

    private static final String OUTPUT_SCOPE = "STDOUT_EXCLUDED; STDERR_REDACTED_BOUNDED_UNTRUSTED_DATA";

    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final RemoteSession borrowed;

    private final ServerId server;

    private final String revision;

    private final String application;

    private final Map<String, String> expected = new HashMap<>();

    private final Deque<Map<String, Object>> receipts = new ArrayDeque<>();

    private String phase = "NONE";

    private int captured;

    private boolean rollbackObserved;

    DeploymentAgentPublicationReceipts(RemoteSession borrowed, ServerId server, String revision,
                                       DeploymentPublishResult proposal) {
        this.borrowed = Objects.requireNonNull(borrowed);
        this.server = Objects.requireNonNull(server);
        this.revision = Objects.requireNonNull(revision);
        application = proposal.graph().applicationId();
        for (var component : proposal.graph().components()) {
            expect(component.componentId(), component.unit(), component.reviewedRuntime().healthCheck());
            if (component.componentId().equals(proposal.graph().healthComponentId()))
                expect(component.componentId(), component.unit(), proposal.graph().healthCheck());
        }
    }

    private void expect(String component, DeploymentUnitIdentity unit, DeploymentHealthCheck check) {
        try (var request = healthRequest(unit, check)) {
            expected.put(binding(request), component);
        }
    }

    static CommandRequest healthRequest(DeploymentUnitIdentity unit, DeploymentHealthCheck check) {
        String script = String.join(" ", List.of(unit.layout().helperPath(), "application-health", unit.id(),
                unit.ownershipManifestSha256(), DeploymentWorkloadArguments.healthPayload(check)).stream()
            .map(DeploymentBuildCommandChannel::shellQuote).toList());
        return new CommandRequest(DeploymentManagedHelperBundle.verifiedCommand(script, unit.layout()), new byte[0],
            Duration.ofSeconds(check.timeoutSeconds() + 45L), 4096);
    }

    void begin(String operation) {
        if (!Set.of("publish", "verify").contains(operation))
            throw new IllegalArgumentException("Invalid publication observation phase");
        phase = operation;
        captured = 0;
        rollbackObserved = false;
        receipts.clear();
    }

    /** Called only after the original publisher journal consumer has accepted this event. */
    void journaled(String type, String action, String detail) {
        if (phase.equals("publish") && type.equals("GRAPH_ROLLBACK_RESULT") && action.equals(application)
            && detail.equals("RESTORED")) rollbackObserved = true;
    }

    Map<String, Object> observation(OperationResult<?> result) {
        return Map.ofEntries(Map.entry("phase", phase), Map.entry("outcome", result.outcome().name()),
            Map.entry("event", result.problem().map(Problem::eventCode).orElse("NONE")),
            Map.entry("rollbackConfirmed", phase.equals("publish") && result.outcome() == TaskResult.FAILED && rollbackObserved),
            Map.entry("healthReceipts", List.copyOf(receipts)), Map.entry("capturedReceiptCount", captured),
            Map.entry("excludedReceiptCount", Math.max(0, captured - receipts.size())),
            Map.entry("receiptListTruncated", captured > receipts.size()),
            Map.entry("outputScope", OUTPUT_SCOPE));
    }

    /** Validates only this product projection; its metadata never authorizes an effect. */
    static Optional<String> safeFeedback(String tool, String output) throws Exception {
        String prefix;
        if (tool.equals("publish") && output.startsWith("PUBLICATION_RESULT\n")) prefix = "PUBLICATION_RESULT\n";
        else if (tool.equals("verify") && output.startsWith("PUBLICATION_NOT_VERIFIED\n"))
            prefix = "PUBLICATION_NOT_VERIFIED\n";
        else if (tool.equals("verify") && output.startsWith("VERIFIED_CURRENT_GRAPH_AND_APPLICATION_HEALTH\n"))
            prefix = "VERIFIED_CURRENT_GRAPH_AND_APPLICATION_HEALTH\n";
        else return Optional.empty();
        if (output.length() > 5900) throw new IllegalArgumentException("Publication feedback exceeds its bound");
        var root = JSON.readTree(output.substring(prefix.length()));
        DeploymentJsonFields.exact(root, Set.of("phase", "outcome", "event", "rollbackConfirmed", "healthReceipts",
            "capturedReceiptCount", "excludedReceiptCount", "receiptListTruncated", "outputScope"));
        if (!DeploymentJsonFields.text(root, "phase", 7).equals(tool))
            throw new IllegalArgumentException("Publication feedback phase differs from its tool");
        var outcome = TaskResult.valueOf(DeploymentJsonFields.text(root, "outcome", 16));
        if (prefix.equals("VERIFIED_CURRENT_GRAPH_AND_APPLICATION_HEALTH\n") && outcome != TaskResult.SUCCEEDED
            || prefix.equals("PUBLICATION_NOT_VERIFIED\n") && outcome == TaskResult.SUCCEEDED)
            throw new IllegalArgumentException("Publication feedback prefix differs from its outcome");
        if (!DeploymentJsonFields.text(root, "event", 128).matches("[A-Z][A-Z0-9_]{0,127}")
            || !DeploymentJsonFields.text(root, "outputScope", 80).equals(OUTPUT_SCOPE))
            throw new IllegalArgumentException("Invalid publication feedback scope or event");
        boolean rollback = flag(root, "rollbackConfirmed");
        if (rollback && (!tool.equals("publish") || outcome != TaskResult.FAILED))
            throw new IllegalArgumentException("Publication feedback cannot confirm rollback");
        int captured = DeploymentJsonFields.number(root, "capturedReceiptCount", 0, Integer.MAX_VALUE);
        int excluded = DeploymentJsonFields.number(root, "excludedReceiptCount", 0, Integer.MAX_VALUE);
        var values = root.get("healthReceipts");
        if (!values.isArray() || values.size() != Math.min(captured, RETAINED_RECEIPTS)
            || (long) excluded + values.size() != captured || flag(root, "receiptListTruncated") != (excluded > 0))
            throw new IllegalArgumentException("Invalid publication receipt counts");
        for (var receipt : values) {
            DeploymentJsonFields.exact(receipt, Set.of("component", "phase", "requestSha256", "transportOutcome", "exit",
                "transportTruncated", "stderrExcerptTruncated", "stderr", "knownHealthFailure"));
            if (!DeploymentJsonFields.text(receipt, "component", 63).matches("[a-z0-9][a-z0-9-]{0,62}")
                || !DeploymentJsonFields.text(receipt, "phase", 7).equals(tool)
                || !DeploymentJsonFields.text(receipt, "requestSha256", 64).matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Invalid publication health request metadata");
            var transport = TaskResult.valueOf(DeploymentJsonFields.text(receipt, "transportOutcome", 16));
            var exitNode = receipt.get("exit");
            int exit;
            if (exitNode.isIntegralNumber() && exitNode.canConvertToInt() && exitNode.intValue() >= 0 && exitNode.intValue() <= 255)
                exit = exitNode.intValue();
            else if (exitNode.isTextual() && exitNode.textValue().equals("UNKNOWN")) exit = -1;
            else throw new IllegalArgumentException("Invalid publication health exit");
            boolean truncated = flag(receipt, "transportTruncated");
            boolean knownFailure = Set.of(TaskResult.SUCCEEDED, TaskResult.FAILED).contains(transport)
                && !truncated && exit > 0 && exit < 128;
            if (flag(receipt, "knownHealthFailure") != knownFailure)
                throw new IllegalArgumentException("Invalid publication health failure claim");
            boolean excerptTruncated = flag(receipt, "stderrExcerptTruncated");
            var stderrNode = receipt.get("stderr");
            if (!stderrNode.isTextual() || stderrNode.textValue().length() > STDERR_CHARACTERS)
                throw new IllegalArgumentException("Invalid publication stderr excerpt");
            String stderr = stderrNode.textValue();
            var bounded = stderrExcerpt(stderr);
            ((com.fasterxml.jackson.databind.node.ObjectNode) receipt).put("stderr", bounded.text());
            ((com.fasterxml.jackson.databind.node.ObjectNode) receipt).put("stderrExcerptTruncated", excerptTruncated || bounded.truncated());
        }
        String sanitized = JSON.writeValueAsString(root);
        if (sanitized.length() > 5700) throw new IllegalArgumentException("Publication feedback exceeds its bound");
        return Optional.of(prefix + sanitized);
    }

    private static boolean flag(JsonNode node, String field) {
        if (!node.path(field).isBoolean()) throw new IllegalArgumentException("Invalid publication feedback boolean");
        return node.get(field).booleanValue();
    }

    private record StderrExcerpt(String text, boolean truncated) {
    }

    private static StderrExcerpt stderrExcerpt(String stderr) {
        try {
            stderr = DeploymentAgentFeedback.safe("PUBLICATION_HEALTH_STDERR", stderr);
        } catch (Exception excluded) {
            stderr = "[diagnostic content excluded]";
        }
        String excerpt = stderr.substring(0, Math.min(stderr.length(), STDERR_CHARACTERS));
        while (!excerpt.isEmpty()) {
            try {
                DeploymentAiJson.facts(Map.of("stderr", excerpt), 900);
                break;
            } catch (java.io.IOException | IllegalArgumentException tooLarge) {
                excerpt = excerpt.substring(0, excerpt.length() / 2);
            }
        }
        return new StderrExcerpt(excerpt, stderr.length() > excerpt.length());
    }

    private String binding(CommandRequest request) {
        return DeploymentAgentInputs.hash(RemoteActions.command(server, revision, request).exactAction());
    }

    @Override
    public OperationResult<CommandResult> execute(CommandRequest request, ApprovalGrant grant) throws ApiException {
        String digest = binding(request);
        String component = expected.get(digest);
        var result = borrowed.execute(request, grant);
        if (!phase.equals("NONE") && component != null) {
            var value = result.value();
            var stderr = stderrExcerpt(value.map(CommandResult::stderr).orElse(""));
            var exit = value.flatMap(CommandResult::exitCode);
            boolean transportTruncated = value.map(CommandResult::truncated).orElse(false);
            boolean knownFailure = Set.of(TaskResult.SUCCEEDED, TaskResult.FAILED).contains(result.outcome())
                && !transportTruncated && exit.filter(code -> code > 0 && code < 128).isPresent();
            var receipt = Map.<String, Object>ofEntries(Map.entry("component", component), Map.entry("phase", phase),
                Map.entry("requestSha256", digest), Map.entry("transportOutcome", result.outcome().name()),
                Map.entry("exit", exit.<Object>map(code -> code).orElse("UNKNOWN")),
                Map.entry("transportTruncated", transportTruncated), Map.entry("stderrExcerptTruncated", stderr.truncated()),
                Map.entry("stderr", stderr.text()), Map.entry("knownHealthFailure", knownFailure));
            if (receipts.size() == RETAINED_RECEIPTS) receipts.removeFirst();
            receipts.addLast(receipt);
            captured++;
        }
        return result;
    }

    @Override
    public RemoteFacts facts() {
        return borrowed.facts();
    }

    @Override
    public OperationResult<TransferResult> upload(TransferRequest request, ApprovalGrant grant) throws ApiException {
        return borrowed.upload(request, grant);
    }

    @Override
    public OperationResult<TransferResult> upload(TransferRequest request, ApprovalGrant grant, TransferConflictChoice choice) throws ApiException {
        return borrowed.upload(request, grant, choice);
    }

    @Override
    public OperationResult<TransferResult> download(TransferRequest request, ApprovalGrant grant) throws ApiException {
        return borrowed.download(request, grant);
    }

    @Override
    public OperationResult<TransferResult> download(TransferRequest request, ApprovalGrant grant, TransferConflictChoice choice) throws ApiException {
        return borrowed.download(request, grant, choice);
    }

    @Override
    public OperationResult<TransferResult> resumeTransfer(UUID transferId, ApprovalGrant grant) throws ApiException {
        return borrowed.resumeTransfer(transferId, grant);
    }

    @Override
    public OperationResult<Void> discardTransfer(UUID transferId, ApprovalGrant grant) throws ApiException {
        return borrowed.discardTransfer(transferId, grant);
    }

    @Override
    public void close() { /* The original workflow owns the borrowed session. */ }
}
