package gold.debug.windowstolinux.mod.deployment.service.standard.analyze.ecosystem.java.gradle;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import gold.debug.windowstolinux.mod.deployment.service.core.model.analysis.RejectionReason;
import gold.debug.windowstolinux.mod.deployment.service.core.model.message.DeploymentLocalizedMessage;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.source.BoundedMetadataChecker;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.source.ProjectIdentityResolver;

/**
 * Reads one fixed Gradle build script and Wrapper layout without invoking Gradle.
 *
 *  <p>读取一个固定 Gradle 构建脚本与 Wrapper 布局，但不调用 Gradle。
 */
public final class GradleBuildChecker {
    /**
     * Pattern recognizing ROOT PROJECT NAME.
     * <p>用于识别根目录项目名称的匹配模式。
     */
    private static final Pattern ROOT_PROJECT_NAME = Pattern
        .compile("(?m)^\\s*rootProject\\.name\\s*=\\s*['\"]([a-zA-Z0-9][a-zA-Z0-9._-]{0,62})['\"]\\s*$");

    /** Reads fixed Gradle facts with a reviewed fallback name. / 使用审阅回退名称读取固定Gradle事实。
     * @param root physical source root / 物理源码根
     * @param rejections hard rejection reasons / 硬拒绝原因
     * @param logicalRootName reviewed source name / 经审阅源码名称
     * @return build facts when present / 存在时的构建事实
     * @throws IOException if reading fails / 读取失败时 */
    public Optional<GradleBuildFacts> inspect(Path root, List<RejectionReason> rejections, String logicalRootName)
        throws IOException {
        boolean groovy = BoundedMetadataChecker.regular(root.resolve("build.gradle"));
        boolean kotlin = BoundedMetadataChecker.regular(root.resolve("build.gradle.kts"));
        if (groovy && kotlin) {
            rejections.add(
                rejection("GRADLE_BUILD_SCRIPT_AMBIGUOUS", "analysis.deployment.rejection.gradleBuildAmbiguous"));
            return Optional.empty();
        }
        if (!groovy && !kotlin) {
            rejections
                .add(rejection("GRADLE_BUILD_SCRIPT_MISSING", "analysis.deployment.rejection.gradleBuildMissing"));
            return Optional.empty();
        }
        Path script = root.resolve(groovy ? "build.gradle" : "build.gradle.kts");
        boolean wrapper = BoundedMetadataChecker.regular(root.resolve("gradlew"))
            && BoundedMetadataChecker.regular(root.resolve("gradle/wrapper/gradle-wrapper.properties"))
            && BoundedMetadataChecker.containsZipEntry(root.resolve("gradle/wrapper/gradle-wrapper.jar"),
            "org/gradle/wrapper/GradleWrapperMain.class");
        return Optional.of(new GradleBuildFacts(applicationId(root, logicalRootName), script,
            BoundedMetadataChecker.read(script), wrapper));
    }

    /**
     * Derives the application identity from bounded Gradle settings metadata and the source root.
     * <p>根据有界 Gradle 设置元数据及源码根目录派生应用身份。
     *
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @param logicalRootName reviewed source name / 经审阅源码名称
     * @return application id text / 应用标识文本
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static String applicationId(Path root, String logicalRootName) throws IOException {
        for (String name : List.of("settings.gradle", "settings.gradle.kts")) {
            Path settings = root.resolve(name);
            if (BoundedMetadataChecker.regular(settings)) {
                return ProjectIdentityResolver.applicationId(root, BoundedMetadataChecker.read(settings),
                    ROOT_PROJECT_NAME, logicalRootName);
            }
        }
        return ProjectIdentityResolver.rootApplicationId(logicalRootName);
    }

    /**
     * Builds the admission rejection associated with the supplied reason.
     * <p>构建与所提供原因关联的准入拒绝。
     *
     * @param code stable machine-readable classification code / 稳定的机器可读分类码
     * @param key lookup key within the current contract / 当前契约内的查找键
     * @return the admission rejection associated with the supplied reason / 与所提供原因关联的准入拒绝
     */
    private static RejectionReason rejection(String code, String key) {
        return new RejectionReason(code, DeploymentLocalizedMessage.of(key), "deployment");
    }
}
