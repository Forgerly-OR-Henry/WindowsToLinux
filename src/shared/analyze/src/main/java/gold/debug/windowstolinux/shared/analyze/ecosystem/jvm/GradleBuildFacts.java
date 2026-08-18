package gold.debug.windowstolinux.shared.analyze.ecosystem.jvm;

import java.nio.file.Path;

/**
 * Fixed Gradle build-script and Wrapper facts.
 *
 * <p>固定的 Gradle 构建脚本与 Wrapper 事实。
 *
 * @param applicationId deterministic Gradle root-project identity / 确定性的 Gradle 根项目身份
 * @param script build script path / 构建脚本路径
 * @param text bounded build script text / 有界构建脚本文本
 * @param usableWrapper whether the Linux Wrapper is complete / Linux Wrapper 是否完整
 */
public record GradleBuildFacts(String applicationId, Path script, String text, boolean usableWrapper) {
}
