package gold.debug.windowstolinux.shared.model.analysis;

import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;

import java.util.Objects;

/**
 * A deterministic reason why a project cannot enter the managed-deployment pipeline.
 *
 *  <p>项目无法进入受管部署流程的确定性原因。
 *
 * @param code stable machine-readable classification code / 稳定的机器可读分类码
 * @param message localized explanation / 本地化说明
 * @param nextAction next action / 下一动作
 */
public record RejectionReason(String code, LocalizedMessage message, String nextAction) {
    /**
     * Validates and binds the inputs required by rejection reason.
     * <p>校验并绑定拒绝原因所需输入。
     *
     * @param code stable machine-readable classification code / 稳定的机器可读分类码
     * @param message localized explanation / 本地化说明
     * @param nextAction next action / 下一动作
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public RejectionReason {
        code = requireText(code, "code");
        message = Objects.requireNonNull(message, "message");
        nextAction = requireText(nextAction, "nextAction");
    }

    /**
     * Requires nonblank text and returns the original content.
     * <p>要求非空白文本，并返回原始内容。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @return require text text / 要求文本文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
