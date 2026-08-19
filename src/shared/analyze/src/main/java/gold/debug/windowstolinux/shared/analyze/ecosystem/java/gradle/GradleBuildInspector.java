package gold.debug.windowstolinux.shared.analyze.ecosystem.java.gradle;

import gold.debug.windowstolinux.shared.analyze.source.BoundedMetadataInspector;
import gold.debug.windowstolinux.shared.analyze.source.ProjectIdentityResolver;
import gold.debug.windowstolinux.shared.model.analysis.RejectionReason;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Reads one fixed Gradle build script and Wrapper layout without invoking Gradle.
 *
 * <p>读取一个固定 Gradle 构建脚本与 Wrapper 布局，但不调用 Gradle。
 */
public final class GradleBuildInspector {
    private static final Pattern ROOT_PROJECT_NAME = Pattern.compile(
            "(?m)^\\s*rootProject\\.name\\s*=\\s*['\"]([a-zA-Z0-9][a-zA-Z0-9._-]{0,62})['\"]\\s*$");
    /** Returns fixed Gradle facts or records a rejection. / 返回固定 Gradle 事实或记录拒绝原因。 */
    public Optional<GradleBuildFacts> inspect(Path root, List<RejectionReason> rejections) throws IOException {
        boolean groovy = BoundedMetadataInspector.regular(root.resolve("build.gradle"));
        boolean kotlin = BoundedMetadataInspector.regular(root.resolve("build.gradle.kts"));
        if (groovy && kotlin) {
            rejections.add(rejection("GRADLE_BUILD_SCRIPT_AMBIGUOUS", "analysis.deployment.rejection.gradleBuildAmbiguous"));
            return Optional.empty();
        }
        if (!groovy && !kotlin) {
            rejections.add(rejection("GRADLE_BUILD_SCRIPT_MISSING", "analysis.deployment.rejection.gradleBuildMissing"));
            return Optional.empty();
        }
        Path script = root.resolve(groovy ? "build.gradle" : "build.gradle.kts");
        boolean wrapper = BoundedMetadataInspector.regular(root.resolve("gradlew"))
                && BoundedMetadataInspector.regular(root.resolve("gradle/wrapper/gradle-wrapper.properties"))
                && BoundedMetadataInspector.containsZipEntry(root.resolve("gradle/wrapper/gradle-wrapper.jar"),
                "org/gradle/wrapper/GradleWrapperMain.class");
        return Optional.of(new GradleBuildFacts(applicationId(root), script,
                BoundedMetadataInspector.read(script), wrapper));
    }

    private static String applicationId(Path root) throws IOException {
        for (String name : List.of("settings.gradle", "settings.gradle.kts")) {
            Path settings = root.resolve(name);
            if (BoundedMetadataInspector.regular(settings)) {
                return ProjectIdentityResolver.applicationId(root, BoundedMetadataInspector.read(settings), ROOT_PROJECT_NAME);
            }
        }
        return ProjectIdentityResolver.rootApplicationId(root);
    }

    private static RejectionReason rejection(String code, String key) {
        return new RejectionReason(code, LocalizedMessage.of(key), "deployment");
    }
}
