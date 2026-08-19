package gold.debug.windowstolinux.shared.analyze.ecosystem.kotlin.gradle;

import java.util.List;

/** Fixed Kotlin Gradle application metadata used by service analysis. / 服务分析使用的固定 Kotlin Gradle 应用元数据。 */
record KotlinGradleFacts(String mainClass, List<String> missingFiles) {
    KotlinGradleFacts {
        missingFiles = List.copyOf(missingFiles);
    }
}
