package gold.debug.windowstolinux.shared.model.analysis;

import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;

import java.util.Objects;

/**
 * A deterministic reason why a project cannot enter the managed-deployment pipeline.
 *
 * <p>项目无法进入受管部署流程的确定性原因。
 *
 * @param code the {@code code} value / {@code code} 值
 * @param message the {@code message} value / {@code message} 值
 * @param nextPhase the {@code nextPhase} value / {@code nextPhase} 值
 */
public record RejectionReason(String code, LocalizedMessage message, String nextPhase) {
    /**
     * Creates a {@code RejectionReason} instance.
     *
     * <p>创建 {@code RejectionReason} 实例。
     *
     * @param code the {@code code} value / {@code code} 值
     * @param message the {@code message} value / {@code message} 值
     * @param nextPhase the {@code nextPhase} value / {@code nextPhase} 值
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public RejectionReason {
        code = requireText(code, "code");
        message = Objects.requireNonNull(message, "message");
        nextPhase = requireText(nextPhase, "nextPhase");
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
