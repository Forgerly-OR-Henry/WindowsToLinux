package gold.debug.windowstolinux.shared.analyze.ecosystem.java.jdk;

import java.util.List;
import java.util.Objects;

/**
 * Fixed dependency-free Java source architecture facts. / 固定的无依赖 Java 源码架构事实。
 *
 * @param sourceRoot root of the reviewed source tree / 已审阅源码树的根目录
 * @param mainClass main class / 主类
 * @param javaVersion java version / Java版本
 * @param sourceFiles source files / 源码文件集合
 * @param missingItems missing items / 缺失项目集合
 * @param conflicts the observed conflicting facts / 观察到的冲突事实
 */
public record JavaJdkFacts(
        String sourceRoot,
        String mainClass,
        String javaVersion,
        List<String> sourceFiles,
        List<String> missingItems,
        List<String> conflicts
) {
    /**
     * Makes bounded Java architecture facts immutable. / 使有界 Java 架构事实不可变。
     *
     * @param sourceRoot root of the reviewed source tree / 已审阅源码树的根目录
     * @param mainClass main class / 主类
     * @param javaVersion java version / Java版本
     * @param sourceFiles source files / 源码文件集合
     * @param missingItems missing items / 缺失项目集合
     * @param conflicts the observed conflicting facts / 观察到的冲突事实
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public JavaJdkFacts {
        sourceRoot = Objects.requireNonNull(sourceRoot, "sourceRoot");
        mainClass = Objects.requireNonNull(mainClass, "mainClass");
        javaVersion = Objects.requireNonNull(javaVersion, "javaVersion");
        sourceFiles = List.copyOf(sourceFiles);
        missingItems = List.copyOf(missingItems);
        conflicts = List.copyOf(conflicts);
    }
}
