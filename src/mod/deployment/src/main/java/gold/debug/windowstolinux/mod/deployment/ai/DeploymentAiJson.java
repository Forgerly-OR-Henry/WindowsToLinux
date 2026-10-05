package gold.debug.windowstolinux.mod.deployment.ai;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.appapi.ai.*;
import gold.debug.windowstolinux.appapi.ai.model.AiMessage;
import gold.debug.windowstolinux.appapi.ai.model.AiPurpose;
import gold.debug.windowstolinux.appapi.ai.model.AiRequest;
import gold.debug.windowstolinux.appapi.ai.model.AiResponse;
import gold.debug.windowstolinux.appapi.ai.model.AiRole;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;

/** Sends bounded deployment-only JSON requests through the real platform AI capability. / 通过真实平台 AI 能力发送有界的部署专用 JSON 请求。 */
public final class DeploymentAiJson {
    /** Parser rejecting duplicate keys. / 拒绝重复键的解析器。 */
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** Bound platform capabilities. / 已绑定的平台能力。 */
    private final ModContext context;

    /** Binds the original Mod activation. / 绑定原 Mod 启用。
     * @param context platform capability / 平台能力 */
    public DeploymentAiJson(ModContext context) {
        this.context = Objects.requireNonNull(context);
    }

    /** Carries the original platform outcome without inferring it from a problem code. / 保留平台原结果，不从问题代码反推。 */
    public static final class CapabilityOutcomeException extends Exception {
        /** Original outcome. / 原结果。 */
        private final TaskResult outcome;

        /** Original structured problem. / 原结构化问题。 */
        private final java.util.Optional<Problem> problem;

        /** Preserves the original outcome and optional reason. / 保留原结果及可选原因。
         * @param outcome original outcome / 原结果
         * @param problem optional original reason / 可选原原因 */
        public CapabilityOutcomeException(TaskResult outcome, java.util.Optional<Problem> problem) {
            super("Deployment capability returned " + outcome);
            this.outcome = outcome;
            this.problem = problem;
        }

        /** Returns original outcome. / 返回原结果。
         * @return original outcome / 原结果 */
        public TaskResult outcome() {
            return outcome;
        }

        /** Returns original problem, if supplied. / 返回平台提供的原问题。
         * @return optional original problem / 可选原问题 */
        public java.util.Optional<Problem> problem() {
            return problem;
        }
    }

    /** Requests one bounded object without hiding an uncertain provider outcome. / 请求一个有界对象且不掩盖提供方未知结果。
     * @param purpose platform purpose / 平台用途
     * @param instruction deployment-specific policy / 部署专用策略
     * @param facts sanitized bounded business evidence / 已清理的有界业务依据
     * @param task original task / 原任务
     * @return parsed object / 已解析对象
     * @throws ApiException if capability invocation fails before a classified result / 能力调用在分类结果前失败时
     * @throws CapabilityOutcomeException if the platform returns a non-success outcome / 平台返回非成功结果时
     * @throws IOException if the response violates the required object format / 响应不符合对象格式时 */
    public JsonNode ask(AiPurpose purpose, String instruction, Map<String, ?> facts, TaskContext task)
        throws ApiException, CapabilityOutcomeException, IOException {
        return ask(purpose, instruction, facts, context.ai().configurationRevision(purpose, task), task);
    }

    /** Calls under one frozen platform configuration binding. / 在同一冻结平台配置绑定下调用。
     * @param purpose platform purpose / 平台用途
     * @param instruction deployment policy / 部署策略
     * @param facts bounded evidence / 有界依据
     * @param expectedRevision frozen platform revision / 冻结平台修订
     * @param task original task / 原任务
     * @return parsed exact object / 已解析精确对象
     * @throws ApiException if the platform refuses dispatch / 平台拒绝发送时
     * @throws CapabilityOutcomeException for classified non-success / 分类非成功时
     * @throws IOException for invalid JSON / JSON 无效时 */
    public JsonNode ask(AiPurpose purpose, String instruction, Map<String, ?> facts, String expectedRevision,
                        TaskContext task) throws ApiException, CapabilityOutcomeException, IOException {
        return ask(purpose, instruction, facts, expectedRevision, task, 48000);
    }

    /** Reviews complete executable command evidence within the platform's one-MiB text ceiling. / 在平台一MiB文字上限内审核完整可执行命令依据。
     * @param instruction independent command policy / 独立命令策略
     * @param facts complete bounded executable evidence / 完整有界可执行依据
     * @param expectedRevision original frozen model route / 原冻结模型路由
     * @param task authentic original task / 真实原任务
     * @return strict independent decision object / 严格独立决定对象
     * @throws ApiException if the platform refuses the request / 平台拒绝请求时
     * @throws CapabilityOutcomeException for a classified non-success / 分类非成功时
     * @throws IOException if the response is malformed / 响应格式无效时 */
    public JsonNode askCommand(String instruction, Map<String, ?> facts, String expectedRevision, TaskContext task)
        throws ApiException, CapabilityOutcomeException, IOException {
        return ask(AiPurpose.APPROVAL, instruction, facts, expectedRevision, task, 1048576);
    }

    /** Keeps ordinary evidence and complete command evidence in distinct fixed budgets. / 为普通依据和完整命令依据保持不同固定预算。
     * @param purpose original declared role / 原声明角色
     * @param instruction fixed review policy / 固定审核策略
     * @param facts bounded evidence / 有界依据
     * @param expectedRevision original model route / 原模型路由
     * @param task authentic original task / 真实原任务
     * @param maximumCharacters fixed evidence ceiling / 固定依据上限
     * @return parsed strict object / 已解析严格对象
     * @throws ApiException if platform dispatch rejects / 平台发送拒绝时
     * @throws CapabilityOutcomeException for original non-success / 原非成功时
     * @throws IOException if the response cannot be parsed / 响应无法解析时 */
    private JsonNode ask(AiPurpose purpose, String instruction, Map<String, ?> facts, String expectedRevision,
                         TaskContext task, int maximumCharacters) throws ApiException, CapabilityOutcomeException, IOException {
        Objects.requireNonNull(facts);
        if (facts.size() > 64 || instruction.length() > 8000)
            throw new IllegalArgumentException("AI input exceeds deployment budget");
        String data = JSON.writeValueAsString(facts);
        if (data.length() > maximumCharacters
            || data.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
            + instruction.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1048576)
            throw new IllegalArgumentException("AI evidence exceeds deployment budget");
        var request = new AiRequest(purpose, List.of(new AiMessage(AiRole.SYSTEM, instruction, List.of()),
            new AiMessage(AiRole.USER, data, List.of())), 1200, Duration.ofSeconds(60), true);
        OperationResult<AiResponse> result = context.ai().call(request, expectedRevision, task);
        if (result.outcome() != TaskResult.SUCCEEDED)
            throw new CapabilityOutcomeException(result.outcome(), result.problem());
        String response = result.value().orElseThrow().text();
        if (response.length() > 16000)
            throw new IOException("Deployment AI response exceeds budget");
        JsonNode node = JSON.readTree(response);
        if (node == null || !node.isObject())
            throw new IOException("Deployment AI response is not an object");
        return node;
    }

    /** Reads one required bounded scalar. / 读取一个必需的有界标量。
     * @param node parsed object / 已解析对象
     * @param key property name / 属性名
     * @param limit maximum characters / 最大字符数
     * @return exact scalar / 精确标量 */
    public static String text(JsonNode node, String key, int limit) {
        JsonNode value = node.get(key);
        if (value == null || !value.isTextual() || value.textValue().isBlank() || value.textValue().length() > limit
            || value.textValue().indexOf('\0') >= 0)
            throw new IllegalArgumentException("Invalid deployment AI field: " + key);
        return value.textValue();
    }
}
