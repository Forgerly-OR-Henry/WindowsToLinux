package gold.debug.windowstolinux.shared.model.agent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * Immutable nonsecret review envelope bound by trusted execution code. / 由可信执行代码绑定的不可变非秘密审核信封。
 * @param id unique operation identifier / 唯一操作标识
 * @param taskId owning task / 所属任务
 * @param target fixed server and execution identity / 固定服务器及执行身份
 * @param sourceRevision frozen source digest / 冻结源码摘要
 * @param planRevision current plan revision / 当前计划修订
 * @param tool registered capability / 已登记能力
 * @param parameters exact nonsecret arguments or secret reference digests / 精确非秘密参数或秘密引用摘要
 * @param evidence observed nonsecret evidence keyed by reference / 以引用为键的非秘密观察证据
 * @param risk deterministic local risk / 确定性本地风险
 */
public record AgentAction(String id, String taskId, String target, String sourceRevision, long planRevision,
        AgentToolType tool, Map<String, String> parameters, Map<String, String> evidence, AgentRiskLevel risk) {
    /** Validates bounded identity and freezes arguments. / 验证有界身份并冻结参数。
     * @param id unique operation identifier / 唯一操作标识
     * @param taskId owning task / 所属任务
     * @param target fixed server and execution identity / 固定服务器及执行身份
     * @param sourceRevision frozen source digest / 冻结源码摘要
     * @param planRevision current plan revision / 当前计划修订
     * @param tool registered capability / 已登记能力
     * @param parameters exact nonsecret arguments or secret reference digests / 精确非秘密参数或秘密引用摘要
     * @param evidence observed nonsecret evidence keyed by reference / 以引用为键的非秘密观察证据
     * @param risk deterministic local risk / 确定性本地风险
     */
    public AgentAction {
        for (String value : List.of(id, taskId, target, sourceRevision))
            if (value.isBlank() || value.length() > 1024 || value.chars().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("invalid action identity");
        Objects.requireNonNull(tool);
        Objects.requireNonNull(risk);
        if (planRevision < 1)
            throw new IllegalArgumentException("invalid plan revision");
        parameters = bounded(parameters);
        evidence = bounded(evidence);
    }

    /** Hashes all approval-relevant fields with length framing. / 对全部审批相关字段使用长度边界计算摘要。
     * @return exact action binding / 精确动作绑定
     */
    public String binding() {
        var value = new StringBuilder();
        for (String part : List.of(id, taskId, target, sourceRevision, Long.toString(planRevision), tool.name(),
                risk.name()))
            append(value, part);
        append(value, Integer.toString(parameters.size()));
        new TreeMap<>(parameters).forEach((k, v) -> {
            append(value, k);
            append(value, v);
        });
        append(value, Integer.toString(evidence.size()));
        new TreeMap<>(evidence).forEach((k, v) -> {
            append(value, k);
            append(value, v);
        });
        return digest(value.toString());
    }

    /** Identifies semantic operation scope independently of model/configuration changes. / 独立于模型及配置变更识别操作语义范围。
     * @return stable rejection identity / 稳定拒绝身份
     */
    public String intentBinding() {
        return new AgentAction("intent", taskId, target, sourceRevision, 1, tool, parameters, Map.of(), risk).binding();
    }

    /** Computes a stable nonreversible SHA-256 digest. / 计算稳定不可逆的 SHA-256 摘要。
     * @param value content to bind / 待绑定内容
     * @return hex digest / 十六进制摘要
     */
    public static String digest(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** Copies bounded structured evidence. / 复制有界结构化证据。
     * @param input supplied map / 传入映射
     * @return immutable checked map / 已检查的不可变映射
     */
    private static Map<String, String> bounded(Map<String, String> input) {
        if (input.size() > 128)
            throw new IllegalArgumentException("too many action fields");
        int total = 0;
        for (var entry : input.entrySet()) {
            if (!entry.getKey().matches("[a-zA-Z0-9._/-]{1,160}") || entry.getValue() == null
                    || entry.getValue().length() > 8192)
                throw new IllegalArgumentException("invalid action field");
            total += entry.getValue().length();
        }
        if (total > 32768)
            throw new IllegalArgumentException("action context too large");
        return Map.copyOf(input);
    }

    /** Appends unambiguous length-framed content. / 追加无歧义的长度限定内容。
     * @param target digest input / 摘要输入
     * @param value framed value / 限定值
     */
    private static void append(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value);
    }
}
