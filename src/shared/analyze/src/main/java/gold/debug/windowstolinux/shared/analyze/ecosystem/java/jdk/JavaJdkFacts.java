package gold.debug.windowstolinux.shared.analyze.ecosystem.java.jdk;

import java.util.List;
import java.util.Objects;

/** Fixed dependency-free Java source architecture facts. / 固定的无依赖 Java 源码架构事实。 */
public record JavaJdkFacts(
        String sourceRoot,
        String mainClass,
        String javaVersion,
        List<String> sourceFiles,
        List<String> missingItems,
        List<String> conflicts
) {
    /** Makes bounded Java architecture facts immutable. / 使有界 Java 架构事实不可变。 */
    public JavaJdkFacts {
        sourceRoot = Objects.requireNonNull(sourceRoot, "sourceRoot");
        mainClass = Objects.requireNonNull(mainClass, "mainClass");
        javaVersion = Objects.requireNonNull(javaVersion, "javaVersion");
        sourceFiles = List.copyOf(sourceFiles);
        missingItems = List.copyOf(missingItems);
        conflicts = List.copyOf(conflicts);
    }
}
