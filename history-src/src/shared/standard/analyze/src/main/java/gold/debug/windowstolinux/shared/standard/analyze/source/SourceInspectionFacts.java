package gold.debug.windowstolinux.shared.standard.analyze.source;

import java.nio.file.Path;
import java.util.List;

/**
 * Bounded facts collected without executing any project-controlled content.
 *
 *  <p>在不执行任何项目可控内容的前提下采集的有界事实。
 *
 * @param scannedFiles scanned files / 已扫描文件集合
 * @param relativeFiles the bounded relative source paths / 有界相对源码路径
 * @param scannedText scanned text / 已扫描文本
 */
public record SourceInspectionFacts(int scannedFiles, List<Path> relativeFiles, String scannedText) {
    /**
     * Creates immutable bounded source facts. / 创建不可变的有界源码事实。
     *
     * @param scannedFiles scanned files / 已扫描文件集合
     * @param relativeFiles the bounded relative source paths / 有界相对源码路径
     * @param scannedText scanned text / 已扫描文本
     */
    public SourceInspectionFacts {
        relativeFiles = List.copyOf(relativeFiles);
    }
}
