package gold.debug.windowstolinux.shared.ai.redaction;

import gold.debug.windowstolinux.shared.model.project.SourceProjectFacts;

import java.util.Objects;

/**
 * Minimal non-secret project facts permitted to leave the deterministic analysis boundary.
 *
 * <p>允许离开确定性分析边界的最小非秘密项目事实。
 *
 * @param applicationId the {@code applicationId} value / {@code applicationId} 值
 * @param mavenWrapper the {@code mavenWrapper} value / {@code mavenWrapper} 值
 * @param springBootMavenPlugin the {@code springBootMavenPlugin} value / {@code springBootMavenPlugin} 值
 */
public record RedactedProjectFacts(String applicationId, boolean mavenWrapper, boolean springBootMavenPlugin) {
    /**
     * Creates a {@code RedactedProjectFacts} instance.
     *
     * <p>创建 {@code RedactedProjectFacts} 实例。
     *
     * @param applicationId the {@code applicationId} value / {@code applicationId} 值
     * @param mavenWrapper the {@code mavenWrapper} value / {@code mavenWrapper} 值
     * @param springBootMavenPlugin the {@code springBootMavenPlugin} value / {@code springBootMavenPlugin} 值
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public RedactedProjectFacts {
        applicationId = Objects.requireNonNull(applicationId, "applicationId");
    }

    /**
     * Creates a value through {@code from}.
     *
     * <p>通过 {@code from} 创建值。
     *
     * @param facts the {@code facts} value / {@code facts} 值
     * @return the operation result / 操作结果
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public static RedactedProjectFacts from(SourceProjectFacts facts) {
        Objects.requireNonNull(facts, "facts");
        return new RedactedProjectFacts(facts.applicationName(), facts.usesMavenWrapper(), facts.hasSpringBootPlugin());
    }
}
