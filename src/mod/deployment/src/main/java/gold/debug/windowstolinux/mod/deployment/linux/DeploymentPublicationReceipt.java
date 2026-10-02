package gold.debug.windowstolinux.mod.deployment.linux;

import gold.debug.windowstolinux.appapi.task.OperationResult;
import gold.debug.windowstolinux.appapi.task.Outcome;
import gold.debug.windowstolinux.appapi.task.Problem;
import gold.debug.windowstolinux.appapi.task.ProblemCode;

import java.util.Map;
import java.util.Optional;

import gold.debug.windowstolinux.appapi.linux.CommandResult;

/** Classifies complete bounded publication receipts without changing original outcomes. / 分类完整有界发布回执，不改变原结果。 */
final class DeploymentPublicationReceipt {
    /** Prevents construction of the receipt utility. / 禁止构造回执工具。 */
    private DeploymentPublicationReceipt() {
    }

    /** Requires an observed untruncated successful process exit. / 要求已观察且未截断的成功进程退出。
     * @param result original remote result / 原远端结果
     * @return whether the receipt is complete / 回执是否完整 */
    static boolean complete(OperationResult<CommandResult> result) {
        return result.outcome() == Outcome.SUCCEEDED && result.value().isPresent()
            && !result.value().orElseThrow().truncated()
            && result.value().orElseThrow().exitCode().equals(Optional.of(0));
    }

    /** Retains a non-success without inventing a value. / 不补造值而保留非成功结果。
     * @param result original result / 原结果
     * @param <T> requested result type / 所需结果类型
     * @return original outcome and problem / 原结果与问题 */
    static <T> OperationResult<T> withoutValue(OperationResult<?> result) {
        return new OperationResult<>(result.outcome(), Optional.empty(), result.problem());
    }

    /** Reports exact successful evidence. / 报告精确成功证据。
     * @param value verified value / 已核实值
     * @param <T> value type / 值类型
     * @return successful result / 成功结果 */
    static <T> OperationResult<T> success(T value) {
        return new OperationResult<>(Outcome.SUCCEEDED, Optional.of(value), Optional.empty());
    }

    /** Retains an uncertain effect. / 保留未确认副作用。
     * @param event stable event code / 稳定事件码
     * @param <T> requested result type / 所需结果类型
     * @return explicit unknown / 显式未知 */
    static <T> OperationResult<T> unknown(String event) {
        return new OperationResult<>(Outcome.UNKNOWN, Optional.empty(), Optional.of(new Problem(ProblemCode.UNKNOWN,
            event, "Publication effect is unconfirmed", Map.of(), Optional.empty())));
    }

    /** Reports one known non-secret health failure. / 报告一项已知且不含秘密的健康失败。
     * @param code stable category / 稳定类别
     * @param event stable event / 稳定事件
     * @param <T> requested type / 请求类型
     * @return known failure / 已知失败 */
    static <T> OperationResult<T> failure(ProblemCode code, String event) {
        return new OperationResult<>(Outcome.FAILED, Optional.empty(),
            Optional.of(new Problem(code, event, "Reviewed publication step failed", Map.of(), Optional.empty())));
    }

    /** Decodes unique bounded helper key-value lines without accepting conflicting evidence. / 解码唯一有界helper键值行而不接受冲突证据。
     * @param output complete bounded stdout / 完整有界标准输出
     * @return unique fields or empty on malformed input / 唯一字段；畸形输入为空 */
    static Map<String, String> fields(String output) {
        var values = new java.util.HashMap<String, String>();
        if (output.length() > 4096 || !output.endsWith("\n"))
            return Map.of();
        for (String line : output.split("\n")) {
            int separator = line.indexOf('=');
            if (separator < 1 || !line.substring(0, separator).matches("[A-Za-z_]{1,32}")
                || values.containsKey(line.substring(0, separator)))
                return Map.of();
            values.put(line.substring(0, separator), line.substring(separator + 1));
        }
        return Map.copyOf(values);
    }
}
