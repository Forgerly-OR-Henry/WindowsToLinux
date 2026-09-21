package gold.debug.windowstolinux.shared.analyze.ecosystem.java.maven;

/**
 * Root Maven facts needed by framework and deployment support checks.
 *
 *  <p>框架及部署支持检查所需的根 Maven 事实。
 *
 * @param pomText pom text / pom文本
 * @param applicationName application name / 应用名称
 * @param springBootPlugin spring boot plugin / Spring启动Plugin
 * @param warPackaging war packaging / war打包
 */
public record MavenBuildFacts(
        String pomText,
        String applicationName,
        boolean springBootPlugin,
        boolean warPackaging
) {
}
