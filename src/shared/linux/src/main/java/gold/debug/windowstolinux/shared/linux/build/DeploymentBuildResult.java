package gold.debug.windowstolinux.shared.linux.build;

import java.util.Objects;

/**
 * Verified result of a bounded typed deployment source build before a typed runtime is published.
 *
 * <p>在发布类型化运行时之前，有界部署源码构建的已验证结果。
 *
 * @param succeeded whether the build and artifact verification succeeded / 构建和产物验证是否成功
 * @param sourceSha256 the verified uploaded source archive digest / 已验证上传源码归档摘要
 * @param evidence bounded non-secret build evidence / 有界非秘密构建证据
 */
public record DeploymentBuildResult(boolean succeeded, String sourceSha256, String evidence) {
    /** Creates a {@code DeploymentBuildResult} instance. / 创建 {@code DeploymentBuildResult} 实例。 */
    public DeploymentBuildResult {
        sourceSha256 = Objects.requireNonNull(sourceSha256, "sourceSha256");
        if (!sourceSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sourceSha256 must be a lowercase SHA-256 digest");
        }
        evidence = Objects.requireNonNull(evidence, "evidence");
    }

    /** Creates a failed result bound to the reviewed source. / 创建绑定到已审阅源码的失败结果。 */
    public static DeploymentBuildResult failed(String sourceSha256, String evidence) {
        return new DeploymentBuildResult(false, sourceSha256, evidence);
    }

    /** Creates a successful result bound to the reviewed source. / 创建绑定到已审阅源码的成功结果。 */
    public static DeploymentBuildResult succeeded(String sourceSha256, String evidence) {
        return new DeploymentBuildResult(true, sourceSha256, evidence);
    }
}
