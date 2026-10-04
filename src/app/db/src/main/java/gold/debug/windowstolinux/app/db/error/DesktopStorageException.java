package gold.debug.windowstolinux.app.db.error;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.Problem;

/**
 * Structured desktop persistence failure. / 结构化桌面持久化失败。
 */
public final class DesktopStorageException extends SQLException {
    /**
     * Structured failure occurrence retained for safe reporting.
     * <p>保留用于安全报告的结构化失败实例。
     */
    private final Problem failure;

    /**
     * Creates one persistence failure occurrence. / 创建一次持久化失败。
     *
     * @param failure structured failure occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
     * @param cause original failure retained as the nested cause / 保留为嵌套原因的原始失败
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public DesktopStorageException(Problem failure, Throwable cause) {
        super(Objects.requireNonNull(failure, "failure").message(), cause);
        this.failure = failure;
    }

    /**
     * Creates a typed persistence failure. / 创建类型化持久化失败。
     *
     * @param type selected member of the supported type set / 受支持类型集合中的所选项
     * @param diagnostic bounded non-secret detail for diagnostic reporting / 用于诊断报告的有界非秘密详情
     * @param cause original failure retained as the nested cause / 保留为嵌套原因的原始失败
     * @return a typed persistence failure / 类型化持久化失败
     */
    public static DesktopStorageException create(DesktopStorageFailureType type, String diagnostic,
                                                 Throwable cause) {
        return new DesktopStorageException(evidence(type, diagnostic, cause),
            cause);
    }

    /**
     * Returns structured failure occurrence retained for safe reporting.
     * <p>返回保留用于安全报告的结构化失败实例。
     *
     * @return structured failure occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
     */
    public Problem problem() {
        return failure;
    }

    /** Preserves SDK evidence and an explicitly known local rollback failure. / 保留SDK依据及明确已知的本地回滚失败。
     * @param type actual persistence classification / 实际持久化分类
     * @param diagnostic nonsecret local diagnostic / 非秘密本地诊断
     * @param cause actual nested failure or absence / 实际嵌套失败或不存在
     * @return exact known event with the original diagnostic identity / 带原诊断身份的精确已知事件
     * @throws NullPointerException if a required local value is absent / 必需本地值缺失时 */
    private static Problem evidence(DesktopStorageFailureType type, String diagnostic, Throwable cause) {
        Objects.requireNonNull(type, "type");
        ApiException api = cause instanceof ApiException direct ? direct
            : cause != null && cause.getCause() instanceof ApiException nested ? nested : null;
        if (api == null) return type.problem(UUID.randomUUID(), diagnostic);
        if (type != DesktopStorageFailureType.ROLLBACK_FAILED) return api.problem();
        var original = api.problem();
        var local = type.problem(original.diagnosticId().orElseGet(UUID::randomUUID), diagnostic);
        var context = new HashMap<>(original.context());
        local.context().forEach((key, value) -> {
            var previous = context.put(key, value);
            if (previous != null && !previous.equals(value)) retainCause(context, "cause." + key, previous);
        });
        retainCause(context, "causeEventCode", original.eventCode());
        return new Problem(local.code(), local.eventCode(), local.message(), context, local.diagnosticId());
    }

    /** Retains a cause value without replacing preexisting diagnostic information. / 不替换已有诊断信息地保留原因值。
     * @param context actual immutable-boundary context being assembled / 正在组装的实际不可变边界上下文
     * @param key preferred cause evidence key / 首选原因依据键
     * @param value original cause evidence / 原原因依据 */
    private static void retainCause(Map<String, String> context, String key, String value) {
        String selected = key;
        int suffix = 0;
        while (context.containsKey(selected) && !context.get(selected).equals(value)) selected = key + "." + ++suffix;
        context.putIfAbsent(selected, value);
    }
}
