package gold.debug.windowstolinux.shared.linux.build;

import java.util.Objects;

/**
 * Verified result of a bounded typed deployment source build before a typed runtime is published.
 *
 *  <p>在发布类型化运行时之前，有界部署源码构建的已验证结果。
 *
 * @param succeeded whether the build and artifact verification succeeded / 构建和产物验证是否成功
 * @param sourceSha256 the verified uploaded source archive digest / 已验证上传源码归档摘要
 * @param evidence bounded non-secret build evidence / 有界非秘密构建证据
 * @param toolchains toolchains / 工具链集合
 */
public record DeploymentBuildResult(boolean succeeded, String sourceSha256, String evidence,
        gold.debug.windowstolinux.shared.model.toolchain.ResolvedToolchainSet toolchains) {
    /**
     * Initializes deployment build result through its shared constructor contract.
     * <p>通过共享构造契约初始化部署构建结果。
     *
     * @param succeeded whether the build and artifact verification succeeded / 构建和产物验证是否成功
     * @param sourceSha256 SHA-256 identity of the frozen source snapshot / 已冻结源码快照的 SHA-256 身份
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     */
    public DeploymentBuildResult(boolean succeeded, String sourceSha256, String evidence) {
        this(succeeded, sourceSha256, evidence, new gold.debug.windowstolinux.shared.model.toolchain.ResolvedToolchainSet("legacy", java.util.List.of()));
    }
    /**
     * Validates and binds the inputs required by deployment build result.
     * <p>校验并绑定部署构建结果所需输入。
     *
     * @param succeeded whether the build and artifact verification succeeded / 构建和产物验证是否成功
     * @param sourceSha256 SHA-256 identity of the frozen source snapshot / 已冻结源码快照的 SHA-256 身份
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     * @param toolchains toolchains / 工具链集合
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public DeploymentBuildResult {
        sourceSha256 = Objects.requireNonNull(sourceSha256, "sourceSha256");
        if (!sourceSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sourceSha256 must be a lowercase SHA-256 digest");
        }
        evidence = Objects.requireNonNull(evidence, "evidence");
        toolchains = Objects.requireNonNull(toolchains, "toolchains");
    }

    /**
     * Creates a failed result bound to the reviewed source. / 创建绑定到已审阅源码的失败结果。
     *
     * @param sourceSha256 SHA-256 identity of the frozen source snapshot / 已冻结源码快照的 SHA-256 身份
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     * @return a failed result bound to the reviewed source / 绑定到已审阅源码的失败结果
     */
    public static DeploymentBuildResult failed(String sourceSha256, String evidence) {
        return new DeploymentBuildResult(false, sourceSha256, evidence);
    }

    /**
     * Creates a successful result bound to the reviewed source. / 创建绑定到已审阅源码的成功结果。
     *
     * @param sourceSha256 SHA-256 identity of the frozen source snapshot / 已冻结源码快照的 SHA-256 身份
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     * @return a successful result bound to the reviewed source / 绑定到已审阅源码的成功结果
     */
    public static DeploymentBuildResult succeeded(String sourceSha256, String evidence) {
        return new DeploymentBuildResult(true, sourceSha256, evidence);
    }
}
