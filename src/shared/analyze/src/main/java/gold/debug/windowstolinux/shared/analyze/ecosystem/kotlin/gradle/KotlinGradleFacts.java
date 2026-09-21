package gold.debug.windowstolinux.shared.analyze.ecosystem.kotlin.gradle;

import java.util.List;

/**
 * Fixed Kotlin Gradle application metadata used by service analysis. / 服务分析使用的固定 Kotlin Gradle 应用元数据。
 *
 * @param compilerVersion compiler version / 编译器版本
 * @param mainClass main class / 主类
 * @param missingFiles missing files / 缺失文件集合
 */
record KotlinGradleFacts(String compilerVersion, String mainClass, List<String> missingFiles) {
    /**
     * Binds the supplied dependencies and state for kotlin gradle facts.
     * <p>为KotlinGradle事实绑定传入的依赖及状态。
     *
     * @param compilerVersion compiler version / 编译器版本
     * @param mainClass main class / 主类
     * @param missingFiles missing files / 缺失文件集合
     */
    KotlinGradleFacts {
        missingFiles = List.copyOf(missingFiles);
    }
}
