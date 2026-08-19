package gold.debug.windowstolinux.shared.analyze.ecosystem.kotlin.kotlinc;

import java.util.List;

/** Fixed dependency-free Kotlin compiler architecture facts. / 固定的无依赖 Kotlin 编译器架构事实。 */
public record KotlinCompilerFacts(
        String compilerVersion,
        String sourceRoot,
        String mainClass,
        List<String> sourceFiles,
        List<String> missingItems,
        List<String> conflicts
) {
    /** Makes Kotlin architecture facts immutable. / 使 Kotlin 架构事实不可变。 */
    public KotlinCompilerFacts {
        sourceFiles = List.copyOf(sourceFiles);
        missingItems = List.copyOf(missingItems);
        conflicts = List.copyOf(conflicts);
    }
}
