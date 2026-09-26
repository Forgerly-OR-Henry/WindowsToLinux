package gold.debug.windowstolinux.appapi.task;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Structured failure or uncertainty without implementation exceptions. / 不暴露实现异常的结构化失败或不确定原因。
 * @param code stable public failure category / 稳定的公开失败类别
 * @param eventCode stable diagnostic event code / 稳定的诊断事件码
 * @param message human-readable non-secret diagnostic message / 可读且不含秘密的诊断消息
 * @param context caller context for this capability / 此能力使用的调用方上下文
 * @param diagnosticId optional identifier for retained diagnostics / 可选的已保存诊断标识
 */
public record Problem(ProblemCode code, String eventCode, String message, Map<String, String> context,
        Optional<UUID> diagnosticId) {
    /**
     * Validates the immutable boundary value and copies mutable input. / 校验不可变边界值并复制可变输入。
     * @param code stable public failure category / 稳定的公开失败类别
     * @param eventCode stable diagnostic event code / 稳定的诊断事件码
     * @param message human-readable non-secret diagnostic message / 可读且不含秘密的诊断消息
     * @param context caller context for this capability / 此能力使用的调用方上下文
     * @param diagnosticId optional identifier for retained diagnostics / 可选的已保存诊断标识
     */
    public Problem {
        java.util.Objects.requireNonNull(code, "code");
        java.util.Objects.requireNonNull(eventCode, "eventCode");
        java.util.Objects.requireNonNull(message, "message");
        context = Map.copyOf(context);
        java.util.Objects.requireNonNull(diagnosticId, "diagnosticId");
    }
}
