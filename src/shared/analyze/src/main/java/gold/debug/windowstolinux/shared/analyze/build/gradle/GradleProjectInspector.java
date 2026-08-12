package gold.debug.windowstolinux.shared.analyze.build.gradle;

import gold.debug.windowstolinux.shared.analyze.source.BoundedProjectMetadata;
import gold.debug.windowstolinux.shared.model.analysis.RejectionReason;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Reads one fixed Gradle build script and Wrapper layout without invoking Gradle.
 *
 * <p>读取一个固定 Gradle 构建脚本与 Wrapper 布局，但不调用 Gradle。
 */
public final class GradleProjectInspector {
    /** Returns fixed Gradle facts or records a rejection. / 返回固定 Gradle 事实或记录拒绝原因。 */
    public Optional<GradleProjectInspection> inspect(Path root, List<RejectionReason> rejections) throws IOException {
        boolean groovy = BoundedProjectMetadata.regular(root.resolve("build.gradle"));
        boolean kotlin = BoundedProjectMetadata.regular(root.resolve("build.gradle.kts"));
        if (groovy && kotlin) {
            rejections.add(rejection("GRADLE_BUILD_SCRIPT_AMBIGUOUS", "analysis.deployment.rejection.gradleBuildAmbiguous"));
            return Optional.empty();
        }
        if (!groovy && !kotlin) {
            rejections.add(rejection("GRADLE_BUILD_SCRIPT_MISSING", "analysis.deployment.rejection.gradleBuildMissing"));
            return Optional.empty();
        }
        Path script = root.resolve(groovy ? "build.gradle" : "build.gradle.kts");
        boolean wrapper = BoundedProjectMetadata.regular(root.resolve("gradlew"))
                && BoundedProjectMetadata.regular(root.resolve("gradle/wrapper/gradle-wrapper.properties"));
        return Optional.of(new GradleProjectInspection(script, BoundedProjectMetadata.read(script), wrapper));
    }

    private static RejectionReason rejection(String code, String key) {
        return new RejectionReason(code, LocalizedMessage.of(key), "deployment");
    }
}
