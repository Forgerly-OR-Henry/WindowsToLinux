package gold.debug.windowstolinux.shared.analyze.source;

import java.nio.file.Path;
import java.util.List;

/**
 * Bounded facts collected without executing any project-controlled content.
 *
 * <p>在不执行任何项目可控内容的前提下采集的有界事实。
 *
 * @param scannedFiles the {@code scannedFiles} value / {@code scannedFiles} 值
 * @param hasMavenWrapper the {@code hasMavenWrapper} value / {@code hasMavenWrapper} 值
 * @param hasWindowsMavenWrapper the {@code hasWindowsMavenWrapper} value / {@code hasWindowsMavenWrapper} 值
 * @param hasDatabaseChangeScript the {@code hasDatabaseChangeScript} value / {@code hasDatabaseChangeScript} 值
 * @param scannedText the {@code scannedText} value / {@code scannedText} 值
 */
public record SourceInspection(
        int scannedFiles,
        boolean hasMavenWrapper,
        boolean hasWindowsMavenWrapper,
        boolean hasDatabaseChangeScript,
        List<Path> relativeFiles,
        String scannedText
) {
    /** Creates immutable bounded source facts. / 创建不可变的有界源码事实。 */
    public SourceInspection {
        relativeFiles = List.copyOf(relativeFiles);
    }
}
