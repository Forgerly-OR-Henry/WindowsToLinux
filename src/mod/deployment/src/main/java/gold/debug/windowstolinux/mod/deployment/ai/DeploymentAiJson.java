package gold.debug.windowstolinux.mod.deployment.ai;

import java.io.IOException;
import java.time.Duration;
import java.util.*;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import gold.debug.windowstolinux.appapi.ai.model.*;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.log.*;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCommandEvidence;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCommandReviewModel;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCommandSourceEvidence;
import gold.debug.windowstolinux.mod.deployment.ai.skill.DeploymentSkillResources;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentConfig;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigLoader;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentHelperReviewContract;

/** Native model transport and side-effect-free structured submissions. Business validators retain authority. */
public final class DeploymentAiJson {
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private static final String MISSING_NATIVE_SUBMISSION = "SUBMISSION_REJECTED: native submit_result is missing. "
        + "Review the same complete current request and submit exactly one native submit_result with the required schema. "
        + "Text alone is not a decision or execution permission; no action was authorized or executed by that response. "
        + "This is the final submission opportunity for this request.";

    private static final String REVIEW_VERDICT_ONLY = "Evidence reads repeated. Independently assess these exact visible facts. "
        + "Submit APPROVE or DENY once. Deny when evidence is insufficient or effects are unsafe.";

    private enum SubmissionCorrection { MISSING_NATIVE_CALL, EVIDENCE_NO_PROGRESS }

    private final ModContext context;

    private final DeploymentConfig.Ai output;

    /** Explicit caller selection; the ordinary adapter defaults to zero extra attempts. / 调用者显式选择，普通适配器默认零额外尝试。 */
    private final int generationRecoveryAttempts;

    private final gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentApprovalConversation approvalConversation;

    public DeploymentAiJson(ModContext context) {
        this(context, DeploymentConfigLoader.current().ai());
    }

    DeploymentAiJson(ModContext context, DeploymentConfig.Ai output) {
        this(context, output, null);
    }

    public DeploymentAiJson(ModContext context, gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentApprovalConversation conversation) {
        this(context, DeploymentConfigLoader.current().ai(), Objects.requireNonNull(conversation));
    }

    private DeploymentAiJson(ModContext context, DeploymentConfig.Ai output,
                             gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentApprovalConversation conversation) {
        this(context, output, conversation, 0);
    }

    /** Opts one independent caller into pure-generation recovery without changing deployment orchestration. / 一个独立调用者选择纯生成恢复，不改变部署编排。
     * @param context original capabilities / 原能力
     * @param extraAttempts selected 0..16 extra attempts within each original deadline / 各原期限内选择的 0..16 额外次数 */
    public DeploymentAiJson(ModContext context, int extraAttempts) {
        this(context, DeploymentConfigLoader.current().ai(), null, extraAttempts);
    }

    private DeploymentAiJson(ModContext context, DeploymentConfig.Ai output,
                             gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentApprovalConversation conversation,
                             int extraAttempts) {
        this.context = Objects.requireNonNull(context);
        this.output = Objects.requireNonNull(output);
        this.approvalConversation = conversation;
        if (extraAttempts < 0 || extraAttempts > 16)
            throw new IllegalArgumentException("Generation recovery attempts must be in 0..16");
        this.generationRecoveryAttempts = extraAttempts;
    }

    /** Unknown instruction supplements retain SYSTEM authority only on the current stateless transport. */
    public boolean supportsTrustedCommandInstructions() {
        return approvalConversation == null;
    }

    public static final class CapabilityOutcomeException extends Exception {
        private final TaskResult outcome;

        private final Optional<Problem> problem;

        public CapabilityOutcomeException(TaskResult outcome, Optional<Problem> problem) {
            super("Deployment capability returned " + outcome);
            this.outcome = outcome;
            this.problem = problem;
        }

        public TaskResult outcome() {
            return outcome;
        }

        public Optional<Problem> problem() {
            return problem;
        }
    }

    public JsonNode ask(AiPurpose purpose, String instruction, Map<String, ?> facts, TaskContext task)
        throws ApiException, CapabilityOutcomeException, IOException {
        return ask(purpose, instruction, facts, context.ai().configurationRevision(purpose, task), task);
    }

    public JsonNode ask(AiPurpose purpose, String instruction, Map<String, ?> facts, String revision, TaskContext task)
        throws ApiException, CapabilityOutcomeException, IOException {
        if (purpose == AiPurpose.APPROVAL && approvalConversation != null)
            return askCommand(instruction, facts, revision, task,
                gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentReviewDecision::malformed);
        return submit(purpose, instruction, facts, revision, task, 48000,
            "{\"type\":\"object\",\"additionalProperties\":true}", node -> null);
    }

    /** Uses the caller's existing structured contract and bounded submission correction. / 使用调用方原结构合同和有界提交纠正。 */
    public JsonNode askStructured(AiPurpose purpose, String instruction, Map<String, ?> facts, String revision,
                                  TaskContext task, String schema, java.util.function.Function<JsonNode, String> validate)
        throws ApiException, CapabilityOutcomeException, IOException {
        return submit(purpose, instruction, facts, revision, task, 48000, schema, validate);
    }

    public JsonNode askCommand(String instruction, Map<String, ?> facts, String revision, TaskContext task,
                               java.util.function.Predicate<JsonNode> malformed)
        throws ApiException, CapabilityOutcomeException, IOException {
        return askCommand(instruction, facts, revision, task, malformed, Optional.empty(), false);
    }

    public JsonNode askCommand(String instruction, Map<String, ?> facts, String revision, TaskContext task,
                               java.util.function.Predicate<JsonNode> malformed, ActionIntent intent, CommandRequest request)
        throws ApiException, CapabilityOutcomeException, IOException {
        if (approvalConversation != null) return askCommand(instruction, facts, revision, task, malformed);
        var layout = gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture.originalTaskLayout(context.tasks(), task);
        var evidence = DeploymentCommandSourceEvidence.supports(intent)
            ? DeploymentCommandSourceEvidence.project(facts(facts, 1048576, true), intent, request, layout)
            : Optional.<DeploymentCommandSourceEvidence>empty();
        boolean nativeObservation = DeploymentCommandEvidence.nativePublicationApplication(intent, request, layout).isPresent();
        return askCommand(instruction, facts, revision, task, malformed, evidence, nativeObservation);
    }

    private JsonNode askCommand(String instruction, Map<String, ?> facts, String revision, TaskContext task,
                                java.util.function.Predicate<JsonNode> malformed, Optional<DeploymentCommandSourceEvidence> evidence,
                                boolean nativeObservation)
        throws ApiException, CapabilityOutcomeException, IOException {
        return submit(AiPurpose.APPROVAL, instruction + DeploymentSkillResources.text(DeploymentAiJson.class, "finite-command-review.md"),
            facts, revision, task, 1048576, """
                {"type":"object","additionalProperties":false,
                 "required":["decision","requiresUserConfirmation","reason"],
                 "properties":{"decision":{"type":"string","enum":["APPROVE","DENY"]},
                 "requiresUserConfirmation":{"type":"boolean"},
                 "reason":{"type":"string","minLength":1,"maxLength":2000}}}
                """, node -> malformed.test(node) ? "Command review schema is invalid" : null, evidence, nativeObservation);
    }

    private JsonNode submit(AiPurpose purpose, String policy, Map<String, ?> facts, String revision, TaskContext task,
                            int bound, String schema, java.util.function.Function<JsonNode, String> validate)
        throws ApiException, CapabilityOutcomeException, IOException {
        return submit(purpose, policy, facts, revision, task, bound, schema, validate, Optional.empty(), false);
    }

    private JsonNode submit(AiPurpose purpose, String policy, Map<String, ?> facts, String revision, TaskContext task,
                            int bound, String schema, java.util.function.Function<JsonNode, String> validate,
                            Optional<DeploymentCommandSourceEvidence> evidence, boolean nativeObservation)
        throws ApiException, CapabilityOutcomeException, IOException {
        var definition = new AiToolDefinition("submit_result", "Submit the complete structured result required by the business contract. This submission grants no execution permission.",
            schema);
        policy += " Submit exactly one native submit_result call. Its arguments are the complete requested result. Text alone is not a submission.";
        if (purpose == AiPurpose.APPROVAL) {
            var layout = gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture.originalTaskLayout(context.tasks(), task);
            policy += "\n" + reviewContract(evidence, layout, nativeObservation, facts);
        }
        if (evidence.isPresent()) policy += "\n" + evidence.orElseThrow().instructions();
        var conversation = purpose == AiPurpose.APPROVAL ? approvalConversation : null;
        try {
            if (conversation != null) facts = conversation.facts(policy, facts, revision, task);
        } catch (ApiException rejected) {
            throw rejected;
        } catch (Exception rejected) {
            throw new IOException("Approval conversation binding rejected", rejected);
        }
        String current = facts(facts, bound, purpose == AiPurpose.APPROVAL);
        var messages = new ArrayList<AiMessage>();
        messages.add(new AiMessage(AiRole.SYSTEM, policy, List.of()));
        var definitions = evidence.isPresent() ? List.of(definition, evidence.orElseThrow().evidenceTool()) : List.of(definition);
        if (evidence.isPresent()) messages.addAll(evidence.orElseThrow().messages());
        else messages.add(new AiMessage(AiRole.USER, current, List.of()));
        var evidenceReader = evidence.map(value -> value.reader(output.execution().standaloneToolAttempts()));
        SubmissionCorrection correction = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                if (conversation != null) messages = new ArrayList<>(conversation.messages(current, revision, task));
            } catch (ApiException rejected) {
                throw rejected;
            } catch (Exception rejected) {
                throw new IOException("Approval conversation history rejected", rejected);
            }
            // Rebuilt conversation history must receive this request-local correction without retaining it for another command.
            if (correction == SubmissionCorrection.MISSING_NATIVE_CALL) {
                int position = evidence.isPresent() ? 1 : messages.size() - 1;
                messages.add(position, new AiMessage(AiRole.USER, MISSING_NATIVE_SUBMISSION, List.of()));
            }
            AiResponse response;
            try {
                response = correction == SubmissionCorrection.EVIDENCE_NO_PROGRESS
                    ? requestFinalVerdict(messages, definition, revision, task, evidenceReader.orElseThrow())
                    : requestSubmission(purpose, messages, definitions, revision, task, bound > 48000, evidenceReader);
            } catch (DeploymentCommandReviewModel.Failure stalled) {
                if (purpose != AiPurpose.APPROVAL || attempt != 0 || !stalled.evidenceReadMadeNoProgress()
                    || evidenceReader.isEmpty() || evidenceReader.orElseThrow().hasUnfulfilledApprovalEvidence()
                    || Thread.currentThread().isInterrupted() || task.cancelToken().requested()) throw stalled;
                correction = SubmissionCorrection.EVIDENCE_NO_PROGRESS;
                recordSubmissionCorrection(task, correction);
                continue;
            }
            if (purpose == AiPurpose.APPROVAL && attempt == 0 && response.finishReason().equals("stop") && response.toolCalls().isEmpty()) {
                correction = SubmissionCorrection.MISSING_NATIVE_CALL;
                recordSubmissionCorrection(task, correction);
                continue;
            }
            if (!response.finishReason().equals("tool_calls") || response.toolCalls().isEmpty())
                throw new IOException("A complete native result submission is required");
            if (response.toolCalls().size() != 1) {
                if (purpose != AiPurpose.APPROVAL && attempt == 0) {
                    messages.add(AiMessage.assistant(response));
                    for (var rejected : response.toolCalls())
                        messages.add(AiMessage.result(new AiToolResult(rejected.id(),
                            "SUBMISSION_REJECTED: submit exactly one result; no action was executed. Resubmit one complete action.", true)));
                    continue;
                }
                throw new IOException("Exactly one native result submission is required");
            }
            var call = response.toolCalls().getFirst();
            JsonNode result = null;
            String invalid;
            try {
                if (!call.name().equals("submit_result"))
                    throw new IllegalArgumentException("Unsupported submission tool");
                result = arguments(call);
                invalid = validate.apply(result);
            } catch (IllegalArgumentException rejected) {
                invalid = rejected.getMessage();
            }
            if (invalid == null && result.path("decision").asText().equals("APPROVE")) {
                var blocker = evidenceReader.flatMap(DeploymentCommandSourceEvidence.Reader::approvalEvidenceBlock);
                if (blocker.isPresent())
                    throw new DeploymentCommandReviewModel.Failure(DeploymentCommandReviewModel.FailureStage.VERDICT, "submit_result",
                        "EVIDENCE_INCOMPLETE_VERDICT", new IOException("Current review source is incomplete"), blocker.orElseThrow());
            }
            var receipt = new AiToolResult(call.id(), invalid == null
                ? "REVIEW_RECORDED: model verdict only; no execution grant. The next command requires a new complete independent review."
                : "SUBMISSION_REJECTED: " + invalid.substring(0, Math.min(512, invalid.length())), invalid != null);
            try {
                if (conversation != null) conversation.retain(current, response, receipt, revision, task);
            } catch (ApiException rejected) {
                throw rejected;
            } catch (Exception rejected) {
                throw new IOException("Approval conversation receipt rejected", rejected);
            }
            if (invalid == null) return result;
            if (attempt == 1) throw new IOException("Structured submission remains invalid");
            messages.add(AiMessage.assistant(response));
            messages.add(AiMessage.result(receipt));
        }
        throw new IOException("Submission budget exhausted");
    }

    private static String reviewContract(Optional<DeploymentCommandSourceEvidence> evidence,
                                          gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout layout,
                                          boolean nativeObservation, Map<String, ?> facts) {
        if (nativeObservation) return DeploymentHelperReviewContract.nativeObservationText(layout);
        if ("deployment.mirror.compatibility.v1".equals(facts.get("operation")) && facts.containsKey("mirrorCompatibilityInput"))
            return "Review the fixed Bash launcher, the complete executable stdin and the bound task independently. "
                + "mirrorCompatibilityInput.stdinHex is the full actual stdin before JSON encoding. Each pair is one byte. "
                + "Ordinary leading ASCII spaces and the final 0a byte are included exactly once. Compare the full body, "
                + "line byte counts and digest using that representation before deciding whether the actual effects are safe.";
        if ("deployment.candidate.verify.v1".equals(facts.get("operation"))
            && facts.get("currentCommandEvidence") instanceof Map<?, ?> current && current.containsKey("candidateObservation"))
            return DeploymentHelperReviewContract.candidateVerificationText(layout);
        if (evidence.filter(DeploymentCommandSourceEvidence::bindingReview).isPresent())
            return DeploymentHelperReviewContract.bindingText(layout);
        if (evidence.filter(DeploymentCommandSourceEvidence::toolchainReview).isPresent())
            return DeploymentHelperReviewContract.toolchainText(layout);
        return DeploymentHelperReviewContract.text(layout);
    }

    private AiResponse requestSubmission(AiPurpose purpose, List<AiMessage> messages, List<AiToolDefinition> definitions,
                                         String revision, TaskContext task, boolean extended,
                                         Optional<DeploymentCommandSourceEvidence.Reader> evidenceReader)
        throws ApiException, CapabilityOutcomeException, IOException {
        if (evidenceReader.isPresent()) evidenceReader.orElseThrow().offerInitialSource(messages);
        while (true) {
            AiResponse response;
            try {
                response = call(purpose, messages, definitions, revision, task, extended, Optional.empty());
            } catch (ApiException refused) {
                if (Thread.currentThread().isInterrupted() || task.cancelToken().requested() || evidenceReader.isEmpty()
                    || !evidenceReader.orElseThrow().repackBeforeSend(messages, refused.problem())) throw refused;
                response = call(purpose, messages, definitions, revision, task, extended, Optional.empty());
            }
            if (evidenceReader.isEmpty()) return response;
            var reader = evidenceReader.orElseThrow();
            var results = reader.read(response);
            if (results.isEmpty()) return response;
            reader.continueReview(messages, response, results);
        }
    }

    private AiResponse requestFinalVerdict(List<AiMessage> accepted, AiToolDefinition definition, String revision,
                                           TaskContext task, DeploymentCommandSourceEvidence.Reader reader)
        throws ApiException, CapabilityOutcomeException, IOException {
        var messages = new ArrayList<>(accepted);
        var originalPolicy = messages.getFirst();
        messages.set(0, new AiMessage(AiRole.SYSTEM, originalPolicy.text() + "\n" + REVIEW_VERDICT_ONLY, List.of()));
        var response = call(AiPurpose.APPROVAL, messages, List.of(definition), revision, task, true, Optional.of(definition.name()), 0);
        if (response.finishReason().equals("tool_calls") && response.toolCalls().size() == 1
            && response.toolCalls().getFirst().name().equals(definition.name())) reader.read(response);
        return response;
    }

    private void recordSubmissionCorrection(TaskContext task, SubmissionCorrection correction) {
        try {
            var target = context.logs().register(new LogCategory("deployment", "Deployment", Optional.of("deployment_log")));
            context.logs().write(target, new LogEvent(LogLevel.WARN, "DEPLOYMENT_AI_SUBMISSION_CORRECTION", "DEPLOYMENT_AI_SUBMISSION_CORRECTION",
                Optional.of("{\"purpose\":\"APPROVAL\",\"reason\":\"" + correction.name()
                    + "\",\"rejectedSubmission\":\"1\",\"nextSubmission\":\"2\",\"maximumSubmissions\":\"2\"}"),
                Optional.of(task.id()), Optional.of(UUID.randomUUID()), Optional.empty()));
        } catch (ApiException unavailable) {
            // Logging failure cannot convert a format correction into authorization or change the original model result.
        }
    }

    public AiResponse call(AiPurpose purpose, List<AiMessage> messages, List<AiToolDefinition> tools,
                           String revision, TaskContext task, boolean extended)
        throws ApiException, CapabilityOutcomeException {
        return call(purpose, messages, tools, revision, task, extended, Optional.empty());
    }

    private AiResponse call(AiPurpose purpose, List<AiMessage> messages, List<AiToolDefinition> tools,
                            String revision, TaskContext task, boolean extended, Optional<String> requiredTool)
        throws ApiException, CapabilityOutcomeException {
        return call(purpose, messages, tools, revision, task, extended, requiredTool, generationRecoveryAttempts);
    }

    private AiResponse call(AiPurpose purpose, List<AiMessage> messages, List<AiToolDefinition> tools,
                            String revision, TaskContext task, boolean extended, Optional<String> requiredTool, int recoveryAttempts)
        throws ApiException, CapabilityOutcomeException {
        int tokens = purpose == AiPurpose.APPROVAL ? output.approvalMaxOutputTokens()
            : extended ? output.extendedDeploymentMaxOutputTokens() : output.deploymentMaxOutputTokens();
        int seconds = purpose == AiPurpose.APPROVAL ? output.requests().approvalTimeoutSeconds()
            : extended ? output.requests().extendedTimeoutSeconds() : output.requests().deploymentTimeoutSeconds();
        var request = new AiRequest(purpose, messages, tokens, Duration.ofSeconds(seconds), false, tools)
            .withGenerationRecovery(recoveryAttempts);
        if (requiredTool.isPresent()) request = request.withRequiredTool(requiredTool.orElseThrow());
        var metrics = new DeploymentAiCallMetrics(context, task, "native_tools");
        var result = metrics.call(request, revision);
        if (result.outcome() != TaskResult.SUCCEEDED)
            throw new CapabilityOutcomeException(result.outcome(), result.problem());
        var response = result.value().orElseThrow();
        if (response.finishReason().equals("length"))
            throw new CapabilityOutcomeException(TaskResult.FAILED, Optional.of(new Problem(ProblemCode.INVALID_INPUT,
                "DEPLOYMENT_AI_OUTPUT_BUDGET_EXHAUSTED", "The model exhausted its output budget before a complete result",
                Map.of("finishReason", "length", "purpose", purpose.name(), "requestedMaximumOutputTokens", Integer.toString(tokens)),
                Optional.of(task.id()))));
        return response;
    }

    public static JsonNode arguments(AiToolCall call) {
        if (call.arguments().length() > 65536)
            throw new IllegalArgumentException("Native arguments exceed deployment bound");
        try {
            var value = JSON.readTree(call.arguments());
            if (value == null || !value.isObject())
                throw new IllegalArgumentException("Native arguments must be an object");
            return value;
        } catch (IOException invalid) {
            throw new IllegalArgumentException("Invalid native JSON arguments");
        }
    }

    public static String facts(Map<String, ?> facts, int limit) throws IOException {
        return facts(facts, limit, false);
    }

    private static String facts(Map<String, ?> facts, int limit, boolean executableFirst) throws IOException {
        if (facts.size() > 64) throw new IllegalArgumentException("AI facts exceed deployment budget");
        JsonNode node = canonical(JSON.valueToTree(facts));
        if (executableFirst) {
            var ordered = JSON.createObjectNode();
            if (node.path("completeExecutableInput").isObject() && node.path("completeExecutableInput").has("sources")) {
                var installer = JSON.createObjectNode();
                installer.set("sources", node.path("completeExecutableInput").get("sources"));
                node.path("completeExecutableInput").fields().forEachRemaining(entry -> {
                    if (!installer.has(entry.getKey())) installer.set(entry.getKey(), entry.getValue());
                });
                ((com.fasterxml.jackson.databind.node.ObjectNode) node).set("completeExecutableInput", installer);
            }
            for (String key : List.of("completeExecutableInput", "exactAction"))
                if (node.has(key)) ordered.set(key, node.get(key));
            node.fields().forEachRemaining(entry -> {
                if (!ordered.has(entry.getKey())) ordered.set(entry.getKey(), entry.getValue());
            });
            node = ordered;
        }
        String value = JSON.writeValueAsString(node);
        if (value.length() > limit) throw new IllegalArgumentException("AI evidence exceeds deployment budget");
        return value;
    }

    private static JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            var result = JSON.createObjectNode();
            var keys = new ArrayList<String>();
            node.fieldNames().forEachRemaining(keys::add);
            keys.sort(String::compareTo);
            keys.forEach(key -> result.set(key, canonical(node.get(key))));
            return result;
        }
        if (node.isArray()) {
            var result = JSON.createArrayNode();
            node.forEach(value -> result.add(canonical(value)));
            return result;
        }
        return node;
    }
}
