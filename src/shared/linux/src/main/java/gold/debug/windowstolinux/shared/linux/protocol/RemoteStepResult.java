package gold.debug.windowstolinux.shared.linux.protocol;

import java.util.Objects;

/**
 * Sanitized result from a named, fixed Linux operation.
 *
 *  <p>具名固定 Linux 操作产生的已净化结果。
 *
 * @param succeeded whether the build and artifact verification succeeded / 构建和产物验证是否成功
 * @param timedOut timed out / 超时输出
 * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
 */
public record RemoteStepResult(boolean succeeded, boolean timedOut, String evidence) {
    /**
     * Validates and binds the inputs required by remote step result.
     * <p>校验并绑定远端步骤结果所需输入。
     *
     * @param succeeded whether the build and artifact verification succeeded / 构建和产物验证是否成功
     * @param timedOut timed out / 超时输出
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public RemoteStepResult {
        evidence = Objects.requireNonNull(evidence, "evidence");
        if (succeeded && timedOut) {
            throw new IllegalArgumentException("a successful remote step cannot time out");
        }
    }
}
