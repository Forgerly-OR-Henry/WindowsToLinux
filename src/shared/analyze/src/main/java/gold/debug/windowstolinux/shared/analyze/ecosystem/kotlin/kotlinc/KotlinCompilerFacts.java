package gold.debug.windowstolinux.shared.analyze.ecosystem.kotlin.kotlinc;

import java.util.List;

/**
 * Fixed dependency-free Kotlin compiler architecture facts. / 固定的无依赖 Kotlin 编译器架构事实。
 *
 * @param compilerVersion compiler version / 编译器版本
 * @param sourceRoot root of the reviewed source tree / 已审阅源码树的根目录
 * @param mainClass main class / 主类
 * @param sourceFiles source files / 源码文件集合
 * @param missingItems missing items / 缺失项目集合
 * @param conflicts the observed conflicting facts / 观察到的冲突事实
 */
public record KotlinCompilerFacts(
        String compilerVersion,
        String sourceRoot,
        String mainClass,
        List<String> sourceFiles,
        List<String> missingItems,
        List<String> conflicts
) {
    /**
     * Makes Kotlin architecture facts immutable. / 使 Kotlin 架构事实不可变。
     *
     * @param compilerVersion compiler version / 编译器版本
     * @param sourceRoot root of the reviewed source tree / 已审阅源码树的根目录
     * @param mainClass main class / 主类
     * @param sourceFiles source files / 源码文件集合
     * @param missingItems missing items / 缺失项目集合
     * @param conflicts the observed conflicting facts / 观察到的冲突事实
     */
    public KotlinCompilerFacts {
        sourceFiles = List.copyOf(sourceFiles);
        missingItems = List.copyOf(missingItems);
        conflicts = List.copyOf(conflicts);
    }
}
