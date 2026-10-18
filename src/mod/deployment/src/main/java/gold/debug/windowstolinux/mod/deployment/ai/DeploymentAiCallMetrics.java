package gold.debug.windowstolinux.mod.deployment.ai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.appapi.ai.model.*;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.log.*;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;

/** Numeric-only accounting for one logical deployment AI request and its correction. / 仅以数值核算一次部署 AI 逻辑请求及格式纠正。 */
final class DeploymentAiCallMetrics {
    /** Numeric-only diagnostic encoder. / 仅数值诊断编码器。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Original activation. / 原启用。 */
    private final ModContext context;

    /** Original accepted task. / 原准入任务。 */
    private final TaskContext task;

    /** Validated non-secret operation identity. / 已校验非秘密动作身份。 */
    private final String operation;

    /** Correlates format corrections with the original request. / 将格式纠正关联到原请求。 */
    private final UUID invocation = UUID.randomUUID();

    /** Counts attempted capability calls, including local refusals. / 统计能力调用尝试，包括本地拒绝。 */
    private int attempt;

    /** Binds accounting to one logical request. / 将核算绑定到一次逻辑请求。
     * @param context original activation / 原启用
     * @param task original task / 原任务
     * @param operation proposed operation identifier / 拟议动作标识 */
    DeploymentAiCallMetrics(ModContext context, TaskContext task, Object operation) {
        this.context = context;
        this.task = task;
        this.operation = operation instanceof String text && text.matches("[a-z0-9_.-]{1,128}") ? text : "analysis";
    }

    /** Records an actual capability outcome without changing its classification. / 记录实际能力结果，不更改分类。
     * @param request original bounded request / 原有界请求
     * @param revision frozen route / 冻结路由
     * @return actual model outcome / 实际模型结果
     * @throws ApiException if the original capability rejects / 原能力拒绝时 */
    OperationResult<AiResponse> call(AiRequest request, String revision) throws ApiException {
        int current = ++attempt;
        var started = java.time.Instant.now();
        long start = System.nanoTime();
        try {
            var result = context.ai().call(request, revision, task);
            record(request, current, started, start, result);
            return result;
        } catch (ApiException failed) {
            var attempts = AiGenerationAttempt.fromProblem(failed.problem());
            var outcome = failed.problem().code() == ProblemCode.UNKNOWN || attempts.stream().anyMatch(value -> value.outcome() == TaskResult.UNKNOWN)
                ? TaskResult.UNKNOWN : TaskResult.FAILED;
            record(request, current, started, start, new OperationResult<>(outcome, Optional.empty(), Optional.of(failed.problem())));
            throw failed;
        }
    }

    /** Publishes non-secret accounting independently of diagnostic storage. / 独立于诊断存储发布非秘密核算。
     * @param request original request / 原请求
     * @param current attempt number / 尝试序号
     * @param started wall-clock start / 开始时间
     * @param start monotonic start / 单调开始时间
     * @param result original classified result / 原分类结果 */
    private void record(AiRequest request, int current, java.time.Instant started, long start, OperationResult<AiResponse> result) {
        var values = facts(request, result);
        values.put("startedAt", started.toString());
        values.put("elapsedMillis", Long.toString(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)));
        values.put("invocation", invocation.toString());
        values.put("attempt", Integer.toString(current));
        values.put("retry", Boolean.toString(current > 1));
        values.put("operation", operation);
        try {
            var target = context.logs().register(new LogCategory("deployment", "Deployment", Optional.of("deployment_log")));
            context.logs().write(target, new LogEvent(LogLevel.INFO, "DEPLOYMENT_AI_USAGE", "DEPLOYMENT_AI_USAGE",
                Optional.of(JSON.writeValueAsString(values)), Optional.of(task.id()), Optional.of(invocation),
                Optional.of(result.outcome()).filter(outcome -> outcome != TaskResult.UNKNOWN)));
        } catch (ApiException | java.io.IOException unavailable) {
            // Diagnostic storage remains independent of the authoritative model result.
        }
    }

    /** Builds non-secret request and result metadata, including invalid JSON usage. / 构造非秘密请求/结果元数据，包含无效 JSON 的用量。
     * @param request bounded original request / 有界原请求
     * @param result actual outcome / 实际结果
     * @return mutable numeric accounting facts / 可变数值核算事实 */
    static Map<String, String> facts(AiRequest request, OperationResult<AiResponse> result) {
        var values = new LinkedHashMap<String, String>();
        values.put("purpose", request.purpose().name());
        values.put("outcome", result.outcome().name());
        values.put("visibleMessageBytes", Long.toString(request.messages().stream().mapToLong(message -> message.text().getBytes(StandardCharsets.UTF_8).length).sum()));
        values.put("toolArgumentBytes", Long.toString(request.messages().stream().flatMap(m -> m.toolCalls().stream()).mapToLong(c -> c.arguments().getBytes(StandardCharsets.UTF_8).length).sum()));
        values.put("toolResultBytes", Long.toString(request.messages().stream().flatMap(m -> m.toolResult().stream()).mapToLong(r -> r.text().getBytes(StandardCharsets.UTF_8).length).sum()));
        values.put("continuationBytes", Long.toString(request.messages().stream().flatMap(m -> m.continuation().stream()).mapToLong(AiContinuation::payloadBytes).sum()));
        values.put("maximumRequestBytes", Integer.toString(request.maxRequestBytes()));
        StringBuilder prefix = new StringBuilder();
        for (var message : request.messages()) {
            if (message.role() != AiRole.SYSTEM) break;
            prefix.append(message.role()).append(':').append(message.text().length()).append(':').append(message.text());
        }
        try {
            values.put("fixedPrefixSha256", java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(prefix.toString().getBytes(StandardCharsets.UTF_8))));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        values.put("requestedMaximumOutputTokens", request.maxOutputTokens().map(Object::toString).orElse("UNKNOWN"));
        var response = result.value();
        var usage = response.map(AiResponse::usage);
        var problem = result.problem().map(Problem::context).orElse(Map.of());
        String effective = response.flatMap(AiResponse::effectiveMaxOutputTokens).map(Object::toString)
            .orElse(problem.getOrDefault("effectiveMaxOutputTokens", "UNKNOWN"));
        try {
            int tokens = Integer.parseInt(effective);
            values.put("maximumOutputTokens", tokens > 0 && Integer.toString(tokens).equals(effective) ? effective : "UNKNOWN");
        } catch (NumberFormatException invalid) {
            values.put("maximumOutputTokens", "UNKNOWN");
        }
        response.flatMap(AiResponse::encodedRequestBytes).ifPresent(value -> values.put("encodedRequestBytes", value.toString()));
        if (!values.containsKey("encodedRequestBytes") && problem.getOrDefault("encodedRequestBytes", "").matches("[0-9]{1,8}"))
            values.put("encodedRequestBytes", problem.get("encodedRequestBytes"));
        put(values, "inputTokens", usage.flatMap(AiUsage::inputTokens), problem);
        put(values, "outputTokens", usage.flatMap(AiUsage::outputTokens), problem);
        put(values, "cachedInputTokens", usage.flatMap(AiUsage::cachedInputTokens), problem);
        put(values, "cacheMissInputTokens", usage.flatMap(AiUsage::cacheMissInputTokens), problem);
        put(values, "reasoningOutputTokens", usage.flatMap(AiUsage::reasoningOutputTokens), problem);
        String finish = response.map(AiResponse::finishReason).orElse(problem.getOrDefault("finishReason", "UNKNOWN"));
        values.put("finishReason", java.util.Set.of("stop", "length", "content_filter", "tool_calls", "insufficient_system_resource").contains(finish) ? finish : "UNKNOWN");
        var attempts = result.value().map(AiResponse::generationAttempts)
            .orElseGet(() -> result.problem().map(AiGenerationAttempt::fromProblem).orElse(java.util.List.of()));
        values.put("generationAttemptCount", attempts.isEmpty() ? "UNKNOWN" : Integer.toString(attempts.size()));
        values.put("generationUnknownOutcomeCount", attempts.isEmpty() ? "UNKNOWN" : Long.toString(attempts.stream().filter(value -> value.outcome() == TaskResult.UNKNOWN).count()));
        values.put("generationUnknownUsageCount", attempts.isEmpty() ? "UNKNOWN" : Long.toString(attempts.stream().filter(value -> value.usage().inputTokens().isEmpty()
            || value.usage().outputTokens().isEmpty()).count()));
        values.put("generationKnownInputTokens", attempts.isEmpty() ? "UNKNOWN" : attempts.stream().flatMap(value -> value.usage().inputTokens().stream())
            .map(java.math.BigInteger::valueOf).reduce(java.math.BigInteger.ZERO, java.math.BigInteger::add).toString());
        values.put("generationKnownOutputTokens", attempts.isEmpty() ? "UNKNOWN" : attempts.stream().flatMap(value -> value.usage().outputTokens().stream())
            .map(java.math.BigInteger::valueOf).reduce(java.math.BigInteger.ZERO, java.math.BigInteger::add).toString());
        values.put("generationUsageComplete", Boolean.toString(!attempts.isEmpty() && attempts.stream().allMatch(value -> value.usage().inputTokens().isPresent()
            && value.usage().outputTokens().isPresent())));
        if (!attempts.isEmpty())
            values.putAll(AiGenerationAttempt.attach(new Problem(ProblemCode.UNAVAILABLE, "AI_TRACE", "Physical generation metadata",
                Map.of(), Optional.empty()), attempts).context());
        return values;
    }

    /** Retains reported numeric values, leaving absent or invalid diagnostics unknown. / 保留报告数值，将缺失或无效诊断记为未知。
     * @param values output facts / 输出事实
     * @param key fixed metric key / 固定指标键
     * @param count successful response count / 成功响应计数
     * @param problem rejected response metadata / 被拒响应元数据 */
    private static void put(Map<String, String> values, String key, Optional<Long> count, Map<String, String> problem) {
        String reported = count.map(Object::toString).orElse(problem.getOrDefault(key, "UNKNOWN"));
        values.put(key, reported.matches("[0-9]{1,19}") ? reported : "UNKNOWN");
    }
}
