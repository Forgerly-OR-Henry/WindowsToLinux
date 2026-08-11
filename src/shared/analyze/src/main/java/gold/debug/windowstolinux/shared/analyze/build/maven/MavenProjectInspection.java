package gold.debug.windowstolinux.shared.analyze.build.maven;

/**
 * Root Maven facts needed by framework and deployment support checks.
 *
 * <p>框架及部署支持检查所需的根 Maven 事实。
 *
 * @param pomText the {@code pomText} value / {@code pomText} 值
 * @param applicationName the {@code applicationName} value / {@code applicationName} 值
 * @param springBootPlugin the {@code springBootPlugin} value / {@code springBootPlugin} 值
 * @param warPackaging the {@code warPackaging} value / {@code warPackaging} 值
 */
public record MavenProjectInspection(
        String pomText,
        String applicationName,
        boolean springBootPlugin,
        boolean warPackaging
) {
}
