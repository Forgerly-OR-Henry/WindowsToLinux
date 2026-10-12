package gold.debug.windowstolinux.appapi.ai.model;

import java.util.*;

import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;

/** One physical pure-generation outcome, without prompts, response bodies or credentials. / 一次物理纯生成结果，不含提示、响应正文或凭据。
 * @param generationId correlation for one logical generation / 一次逻辑生成的关联身份
 * @param number one-based physical attempt / 从 1 开始的物理尝试序号
 * @param modelId captured platform model identity / 捕获的平台模型身份
 * @param protocol captured pure-generation protocol / 捕获的纯生成协议
 * @param outcome original physical outcome, never rewritten after recovery / 原物理结果，恢复后不重写
 * @param eventCode original safe event code or AI_GENERATION_COMPLETE / 原安全事件码或 AI_GENERATION_COMPLETE
 * @param transportFailureKind bounded transport classification or NONE / 有界传输分类或 NONE
 * @param elapsedMillis physical call elapsed time / 物理调用耗时
 * @param usage usage of this attempt only, missing fields remain unknown / 仅本次用量，缺失字段保持未知
 * @param encodedRequestBytes encoded request length / 编码请求长度
 * @param effectiveMaxOutputTokens captured output budget / 捕获的输出额度 */
public record AiGenerationAttempt(UUID generationId, int number, String modelId, String protocol,
                                  TaskResult outcome, String eventCode, String transportFailureKind,
                                  long elapsedMillis, AiUsage usage, int encodedRequestBytes,
                                  int effectiveMaxOutputTokens) {
    public AiGenerationAttempt {
        Objects.requireNonNull(generationId);
        Objects.requireNonNull(modelId);
        Objects.requireNonNull(outcome);
        Objects.requireNonNull(usage);
        for (String code : List.of(protocol, eventCode, transportFailureKind))
            if (!code.matches("[A-Z0-9_]{1,80}")) throw new IllegalArgumentException("Invalid generation trace code");
        if (number < 1 || number > 17 || elapsedMillis < 0 || encodedRequestBytes < 1 || effectiveMaxOutputTokens < 1)
            throw new IllegalArgumentException("Invalid generation trace bounds");
    }

    /** Copies and validates a complete ordered trace. / 复制并校验完整有序轨迹。
     * @param attempts physical attempts / 物理尝试
     * @return immutable validated trace / 不可变已校验轨迹 */
    public static List<AiGenerationAttempt> validated(List<AiGenerationAttempt> attempts) {
        var values = List.copyOf(attempts);
        if (values.size() > 17) throw new IllegalArgumentException("Excessive generation trace");
        for (int index = 0; index < values.size(); index++)
            if (values.get(index).number() != index + 1 || !values.get(index).generationId().equals(values.getFirst().generationId()))
                throw new IllegalArgumentException("Generation trace identity differs");
        return values;
    }

    /** Carries the same trace on a failure without inventing a response value. / 在失败中携带相同轨迹，不编造响应值。
     * @param problem original terminal failure / 原终态失败
     * @param attempts original physical outcomes / 原物理结果
     * @return failure retaining original classification and usage evidence / 保留原分类及用量依据的失败 */
    public static Problem attach(Problem problem, List<AiGenerationAttempt> attempts) {
        var values = validated(attempts);
        var context = new LinkedHashMap<>(problem.context());
        context.put("generationAttemptCount", Integer.toString(values.size()));
        for (var attempt : values) {
            String prefix = "generationAttempt." + attempt.number() + ".";
            context.put(prefix + "id", attempt.generationId().toString());
            context.put(prefix + "modelId", attempt.modelId());
            context.put(prefix + "protocol", attempt.protocol());
            context.put(prefix + "outcome", attempt.outcome().name());
            context.put(prefix + "eventCode", attempt.eventCode());
            context.put(prefix + "transportFailureKind", attempt.transportFailureKind());
            context.put(prefix + "elapsedMillis", Long.toString(attempt.elapsedMillis()));
            context.put(prefix + "encodedRequestBytes", Integer.toString(attempt.encodedRequestBytes()));
            context.put(prefix + "effectiveMaxOutputTokens", Integer.toString(attempt.effectiveMaxOutputTokens()));
            var usage = attempt.usage();
            context.put(prefix + "inputTokens", usage.inputTokens().map(Object::toString).orElse("UNKNOWN"));
            context.put(prefix + "outputTokens", usage.outputTokens().map(Object::toString).orElse("UNKNOWN"));
            context.put(prefix + "cachedInputTokens", usage.cachedInputTokens().map(Object::toString).orElse("UNKNOWN"));
            context.put(prefix + "cacheMissInputTokens", usage.cacheMissInputTokens().map(Object::toString).orElse("UNKNOWN"));
            context.put(prefix + "reasoningOutputTokens", usage.reasoningOutputTokens().map(Object::toString).orElse("UNKNOWN"));
        }
        return new Problem(problem.code(), problem.eventCode(), problem.message(), context, problem.diagnosticId());
    }

    /** Reads only the typed trace encoding; absent metadata means an uninstrumented caller. / 仅读取类型化轨迹编码，缺失元数据表示未测量调用。
     * @param problem original failure / 原失败
     * @return immutable original physical outcomes / 不可变原物理结果 */
    public static List<AiGenerationAttempt> fromProblem(Problem problem) {
        var context = problem.context();
        if (!context.containsKey("generationAttemptCount")) return List.of();
        int count = Integer.parseInt(context.get("generationAttemptCount"));
        if (count < 0 || count > 17) throw new IllegalArgumentException("Invalid generation trace count");
        var attempts = new ArrayList<AiGenerationAttempt>();
        for (int number = 1; number <= count; number++) {
            String prefix = "generationAttempt." + number + ".";
            attempts.add(new AiGenerationAttempt(UUID.fromString(context.get(prefix + "id")), number,
                context.get(prefix + "modelId"), context.get(prefix + "protocol"), TaskResult.valueOf(context.get(prefix + "outcome")),
                context.get(prefix + "eventCode"), context.get(prefix + "transportFailureKind"), Long.parseLong(context.get(prefix + "elapsedMillis")),
                new AiUsage(token(context.get(prefix + "inputTokens")), token(context.get(prefix + "outputTokens")),
                    token(context.get(prefix + "cachedInputTokens")), token(context.get(prefix + "cacheMissInputTokens")), token(context.get(prefix + "reasoningOutputTokens"))),
                Integer.parseInt(context.get(prefix + "encodedRequestBytes")), Integer.parseInt(context.get(prefix + "effectiveMaxOutputTokens"))));
        }
        return validated(attempts);
    }

    /** Converts a bounded provider count while keeping absent usage unknown. / 转换有界提供方计数，缺失用量保持未知。
     * @param value reported numeric count or UNKNOWN / 报告数值或 UNKNOWN
     * @return exact count when present / 存在时的精确计数 */
    public static Optional<Long> token(String value) {
        if (value == null || value.equals("UNKNOWN")) return Optional.empty();
        long count = Long.parseLong(value);
        if (count < 0) throw new IllegalArgumentException("Negative usage");
        return Optional.of(count);
    }
}
