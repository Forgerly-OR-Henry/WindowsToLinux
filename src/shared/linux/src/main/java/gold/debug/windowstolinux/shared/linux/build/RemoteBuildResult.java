package gold.debug.windowstolinux.shared.linux.build;

import java.util.Objects;
import java.util.Optional;

/**
 * Outcome of a resource-limited remote Maven build and unique artifact check.
 *
 * <p>受资源限制的远程 Maven 构建及唯一制品检查结果。
 *
 * @param succeeded the {@code succeeded} value / {@code succeeded} 值
 * @param executableJarPath the {@code executableJarPath} value / {@code executableJarPath} 值
 * @param executableJarSha256 the {@code executableJarSha256} value / {@code executableJarSha256} 值
 * @param evidence the {@code evidence} value / {@code evidence} 值
 */
public record RemoteBuildResult(
        boolean succeeded,
        Optional<String> executableJarPath,
        Optional<String> executableJarSha256,
        String evidence
) {
    /**
     * Creates a {@code RemoteBuildResult} instance.
     *
     * <p>创建 {@code RemoteBuildResult} 实例。
     *
     * @param succeeded the {@code succeeded} value / {@code succeeded} 值
     * @param executableJarPath the {@code executableJarPath} value / {@code executableJarPath} 值
     * @param executableJarSha256 the {@code executableJarSha256} value / {@code executableJarSha256} 值
     * @param evidence the {@code evidence} value / {@code evidence} 值
     * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public RemoteBuildResult {
        executableJarPath = Objects.requireNonNull(executableJarPath, "executableJarPath");
        executableJarSha256 = Objects.requireNonNull(executableJarSha256, "executableJarSha256");
        evidence = Objects.requireNonNull(evidence, "evidence");
        if (succeeded != executableJarPath.isPresent() || succeeded != executableJarSha256.isPresent()) {
            throw new IllegalArgumentException("successful builds require exactly one verified artifact");
        }
        executableJarPath.ifPresent(path -> {
            if (!path.startsWith("/var/lib/windowstolinux/work/")) {
                throw new IllegalArgumentException("artifact must remain in a managed candidate workspace");
            }
        });
        executableJarSha256.ifPresent(digest -> {
            if (!digest.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("artifact digest must be lowercase SHA-256");
            }
        });
    }

    /**
     * Performs the {@code failed} operation.
     *
     * <p>执行 {@code failed} 操作。
     *
     * @param evidence the {@code evidence} value / {@code evidence} 值
     * @return the operation result / 操作结果
     */
    public static RemoteBuildResult failed(String evidence) {
        return new RemoteBuildResult(false, Optional.empty(), Optional.empty(), evidence);
    }

    /**
     * Performs the {@code succeeded} operation.
     *
     * <p>执行 {@code succeeded} 操作。
     *
     * @param path the {@code path} value / {@code path} 值
     * @param sha256 the {@code sha256} value / {@code sha256} 值
     * @param evidence the {@code evidence} value / {@code evidence} 值
     * @return the operation result / 操作结果
     */
    public static RemoteBuildResult succeeded(String path, String sha256, String evidence) {
        return new RemoteBuildResult(true, Optional.of(path), Optional.of(sha256), evidence);
    }
}
