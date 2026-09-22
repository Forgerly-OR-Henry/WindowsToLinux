package gold.debug.windowstolinux.shared.standard.analyze.ecosystem.java.gradle;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import gold.debug.windowstolinux.shared.model.analysis.RejectionReason;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.standard.analyze.source.BoundedMetadataInspector;
import gold.debug.windowstolinux.shared.standard.analyze.source.ProjectIdentityResolver;

/**
 * Reads one fixed Gradle build script and Wrapper layout without invoking Gradle.
 *
 *  <p>读取一个固定 Gradle 构建脚本与 Wrapper 布局，但不调用 Gradle。
 */
public final class GradleBuildInspector {
    /**
     * Pattern recognizing ROOT PROJECT NAME.
     * <p>用于识别根目录项目名称的匹配模式。
     */
    private static final Pattern ROOT_PROJECT_NAME = Pattern
            .compile("(?m)^\\s*rootProject\\.name\\s*=\\s*['\"]([a-zA-Z0-9][a-zA-Z0-9._-]{0,62})['\"]\\s*$");
    /**
     * Returns fixed Gradle facts or records a rejection. / 返回固定 Gradle 事实或记录拒绝原因。
     *
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @param rejections reasons preventing admission to the next stage / 阻止进入下一阶段的原因
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    public Optional<GradleBuildFacts> inspect(Path root, List<RejectionReason> rejections) throws IOException {
        boolean groovy = BoundedMetadataInspector.regular(root.resolve("build.gradle"));
        boolean kotlin = BoundedMetadataInspector.regular(root.resolve("build.gradle.kts"));
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
        boolean wrapper = BoundedMetadataInspector.regular(root.resolve("gradlew"))
                && BoundedMetadataInspector.regular(root.resolve("gradle/wrapper/gradle-wrapper.properties"))
                && BoundedMetadataInspector.containsZipEntry(root.resolve("gradle/wrapper/gradle-wrapper.jar"),
                        "org/gradle/wrapper/GradleWrapperMain.class");
        return Optional
                .of(new GradleBuildFacts(applicationId(root), script, BoundedMetadataInspector.read(script), wrapper));
    }

    /**
     * Derives the application identity from bounded Gradle settings metadata and the source root.
     * <p>根据有界 Gradle 设置元数据及源码根目录派生应用身份。
     *
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @return application id text / 应用标识文本
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static String applicationId(Path root) throws IOException {
        for (String name : List.of("settings.gradle", "settings.gradle.kts")) {
            Path settings = root.resolve(name);
            if (BoundedMetadataInspector.regular(settings)) {
                return ProjectIdentityResolver.applicationId(root, BoundedMetadataInspector.read(settings),
                        ROOT_PROJECT_NAME);
            }
        }
        return ProjectIdentityResolver.rootApplicationId(root);
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
        return new RejectionReason(code, LocalizedMessage.of(key), "deployment");
    }
}
